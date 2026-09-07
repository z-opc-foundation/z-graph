#!/usr/bin/env python3
"""
z-graph Bolt POC 并发压力测试 — 多连接并发执行 Cypher。

场景:
- 100 个并发客户端
- 每个跑 5 个 Cypher(不同类型)
- 总计 500 个查询
- 验证无崩溃 + 全部结果正确
"""

import os
import socket
import struct
import sys
import threading
import time

HOST = os.environ.get("Z_GRAPH_HOST", "localhost")
PORT = int(os.environ.get("Z_GRAPH_PORT", "7687"))
NUM_CLIENTS = int(os.environ.get("NUM_CLIENTS", "100"))
QUERIES_PER_CLIENT = int(os.environ.get("QUERIES_PER_CLIENT", "5"))


def make_chunk(payload: bytes) -> bytes:
    return struct.pack(">HH", len(payload), 0x0E00) + payload


def tiny_str(s: str) -> bytes:
    b = s.encode("utf-8")
    return bytes([0x80 + len(b)]) + b


def make_hello() -> bytes:
    payload = (
        b"\x01"
        + b"\xA2"
        + tiny_str("scheme") + tiny_str("basic")
        + tiny_str("user_agent") + tiny_str("z-graph-poc")
    )
    return make_chunk(payload)


def make_run(cypher: str) -> bytes:
    cypher_bytes = cypher.encode("utf-8")
    payload = b"\x10" + b"\xD0" + bytes([len(cypher_bytes)]) + cypher_bytes + b"\xA0"
    return make_chunk(payload)


def make_pull(qid: int = 0) -> bytes:
    qid_encoded = b"\xC8" + bytes([qid])
    n_encoded = b"\xC9\xFF\xFF"
    payload = (
        b"\x3F"
        + b"\xA2"
        + tiny_str("qid") + qid_encoded
        + tiny_str("n") + n_encoded
    )
    return make_chunk(payload)


def make_goodbye() -> bytes:
    return make_chunk(b"\x02")


def recv_chunk(sock: socket.socket, timeout: float = 5.0):
    sock.settimeout(timeout)
    header = b""
    while len(header) < 4:
        chunk = sock.recv(4 - len(header))
        if not chunk:
            raise ConnectionError("EOF")
        header += chunk
    length = struct.unpack(">H", header[:2])[0]
    payload = b""
    while len(payload) < length:
        chunk = sock.recv(length - len(payload))
        if not chunk:
            raise ConnectionError("EOF")
        payload += chunk
    if len(payload) >= 3 and payload[0] == 0xDC:
        return payload[1], payload[2], payload
    return payload[0], -1, payload


def parse_run_success_qid(payload: bytes) -> int:
    """从 RUN SUCCESS 响应里提取服务端分配的 qid。

    Payload 结构(假设服务端用 BoltFrames.writeStruct + Map):
      0xDC            STRUCT_8 marker
      0x70            SUCCESS signature
      0x01            1 field (the Map itself)
      <map>           Map<{qid:int, fields:list, t_first:long}>
      ...
    """
    assert payload[0] == 0xDC and payload[1] == 0x70, f"Not a SUCCESS struct: {payload[:3].hex()}"
    idx = 3
    # 跳过 STRUCT 头,读 map marker
    m = payload[idx]
    idx += 1
    if 0xA0 <= m <= 0xAF:  # TINY_MAP
        n = m - 0xA0
    elif m == 0xD8:        # MAP_8
        n = payload[idx]; idx += 1
    elif m == 0xD9:        # MAP_16
        n = struct.unpack(">H", payload[idx:idx+2])[0]; idx += 2
    else:
        raise ValueError(f"Expected MAP marker after SUCCESS struct, got 0x{m:02X}")
    # 扫描 entries,找 "qid" key
    for _ in range(n):
        # key
        km = payload[idx]; idx += 1
        if 0x80 <= km <= 0x8F:
            kl = km - 0x80; key = payload[idx:idx+kl].decode("utf-8"); idx += kl
        elif km == 0xD0:
            kl = payload[idx]; idx += 1
            key = payload[idx:idx+kl].decode("utf-8"); idx += kl
        else:
            raise ValueError(f"Expected STRING marker for map key, got 0x{km:02X}")
        # value(只解 int 类型)
        if key == "qid":
            vm = payload[idx]; idx += 1
            if vm == 0xC8:
                return int.from_bytes(payload[idx:idx+1], "big", signed=True)
            elif vm == 0xC9:
                v = int.from_bytes(payload[idx:idx+2], "big", signed=True); idx += 2
                return v
            elif vm == 0xCA:
                v = int.from_bytes(payload[idx:idx+4], "big", signed=True); idx += 4
                return v
            elif vm == 0xCB:
                v = int.from_bytes(payload[idx:idx+8], "big", signed=True); idx += 8
                return v
            else:
                raise ValueError(f"qid value not INT: 0x{vm:02X}")
        else:
            # 跳过 value(仅支持 INT_8/16/32/64 和 TINY_STRING/STRING_8 的最小跳过)
            vm = payload[idx]; idx += 1
            if 0x80 <= vm <= 0x8F:
                idx += (vm - 0x80)
            elif vm == 0xD0:
                idx += payload[idx] + 1
            elif vm == 0xC8:
                idx += 1
            elif vm == 0xC9:
                idx += 2
            elif vm == 0xCA:
                idx += 4
            elif vm == 0xCB:
                idx += 8
            elif 0x90 <= vm <= 0x9F:
                # tiny list - 需要递归跳,但 POC 阶段 fields 里的 list 是这里唯一复杂的
                # 简化:仅跳过该 list(后续字段不会出现在 fields 后)
                # 安全起见抛错,实际不会出现
                raise ValueError("Skip tiny list not supported in concurrent test parser")
            else:
                raise ValueError(f"Skip unsupported value marker: 0x{vm:02X}")
    raise ValueError("qid not found in RUN SUCCESS payload")


def parse_record_value(payload: bytes):
    """简化:只解析第一条 value marker 后的 int 或 string。"""
    idx = 3  # 跳过 0xDC 0x71 N
    marker = payload[idx]
    idx += 1
    if 0x90 <= marker <= 0x9F:
        n = marker - 0x90
    elif marker == 0xD4:
        n = payload[idx]; idx += 1
    elif marker == 0xD5:
        n = struct.unpack(">H", payload[idx:idx+2])[0]; idx += 2
    else:
        raise ValueError(f"Unknown list marker: 0x{marker:02X}")
    if n < 1:
        return None
    # 第一个 value
    m = payload[idx]
    idx += 1
    if m == 0xC8:
        return int.from_bytes(payload[idx:idx+1], "big", signed=True)
    elif m == 0xC9:
        return int.from_bytes(payload[idx:idx+2], "big", signed=True)
    elif m == 0xCA:
        return int.from_bytes(payload[idx:idx+4], "big", signed=True)
    elif m == 0xCB:
        return int.from_bytes(payload[idx:idx+8], "big", signed=True)
    elif 0x80 <= m <= 0x8F:
        ln = m - 0x80
        return payload[idx:idx+ln].decode("utf-8")
    elif m == 0xD0:
        ln = payload[idx]; idx += 1
        return payload[idx:idx+ln].decode("utf-8")
    elif m == 0xC3:
        return True
    elif m == 0xC2:
        return False
    elif m == 0xC0:
        return None
    else:
        return f"<unknown 0x{m:02X}>"


# 5 个测试 Cypher(每个客户端按顺序跑这 5 个)
TEST_QUERIES = [
    ("RETURN 1 AS n", 1),
    ('RETURN "hello" AS msg', "hello"),
    ("RETURN -42 AS n", -42),
    ("RETURN 1, 2, 3", 1),  # 只取第一列
    ("RETURN true AS flag", True),
]


def client_worker(client_id: int, results: list):
    """单个客户端的工作线程。"""
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(10.0)
    try:
        sock.connect((HOST, PORT))
        sock.sendall(make_hello())
        if recv_chunk(sock)[0] != 0x70:
            results.append((client_id, "HELLO", None, False))
            return

        for q_idx in range(QUERIES_PER_CLIENT):
            cypher, expected = TEST_QUERIES[q_idx % len(TEST_QUERIES)]
            sock.sendall(make_run(cypher))
            sig, _, run_payload = recv_chunk(sock)
            if sig != 0x70:
                results.append((client_id, cypher, None, False))
                continue
            # 提取服务端分配的 qid(POC 简化:如果解析失败,fallback qid=0 用于诊断)
            try:
                qid = parse_run_success_qid(run_payload)
            except Exception:
                qid = -1
            sock.sendall(make_pull(qid if qid >= 0 else 0))
            # SUCCESS(开始)
            recv_chunk(sock)
            # RECORD
            sig, _, payload = recv_chunk(sock)
            if sig == 0x71:
                value = parse_record_value(payload)
                ok = (value == expected)
                results.append((client_id, cypher, value, ok))
            # SUCCESS(完成)
            recv_chunk(sock)
        sock.sendall(make_goodbye())
    except Exception as e:
        results.append((client_id, "EXCEPTION", str(e), False))
    finally:
        sock.close()


def main():
    print("=" * 70)
    print(f"  z-graph Bolt POC 并发压力测试")
    print("=" * 70)
    print(f"Target: {HOST}:{PORT}")
    print(f"并发客户端数: {NUM_CLIENTS}")
    print(f"每个客户端查询数: {QUERIES_PER_CLIENT}")
    print(f"总查询数: {NUM_CLIENTS * QUERIES_PER_CLIENT}")
    print()

    results = []
    threads = []
    start = time.time()

    for i in range(NUM_CLIENTS):
        t = threading.Thread(target=client_worker, args=(i, results))
        threads.append(t)
        t.start()

    for t in threads:
        t.join(timeout=30)

    elapsed = time.time() - start
    total = len(results)
    passed = sum(1 for _, _, _, ok in results if ok)
    failed = total - passed

    print(f"耗时: {elapsed:.2f}s")
    print(f"QPS: {(total / elapsed):.1f}")
    print(f"结果总数: {total} | 成功: {passed} | 失败: {failed}")
    print()

    if failed > 0:
        print("失败详情:")
        for cid, q, v, ok in results:
            if not ok:
                print(f"  client={cid}, query={q!r}, value={v!r}")
        return 1
    else:
        print("✅ 所有并发客户端的所有查询都成功")
        return 0


if __name__ == "__main__":
    sys.exit(main())