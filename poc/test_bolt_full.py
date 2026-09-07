#!/usr/bin/env python3
"""
z-graph Bolt POC 完整端到端测试 — 覆盖 7 个 Cypher RETURN literal 子场景。

场景:
1. RETURN 整数 AS alias
2. RETURN 字符串 AS alias
3. RETURN 多列
4. RETURN 负数
5. RETURN 无别名
6. RETURN null
7. RETURN boolean

每个场景独立 TCP 连接,验证幂等性。
"""

import os
import socket
import struct
import sys

HOST = os.environ.get("Z_GRAPH_HOST", "localhost")
PORT = int(os.environ.get("Z_GRAPH_PORT", "7687"))


# ==================== 帧构造工具 ====================

def make_chunk(payload: bytes) -> bytes:
    return struct.pack(">HH", len(payload), 0x0E00) + payload


def tiny_str(s: str) -> bytes:
    """Bolt TINY_STRING(N) + s,N=len(s),必须 0<=N<=15。"""
    b = s.encode("utf-8")
    n = len(b)
    if n > 15:
        raise ValueError(f"tiny_str only supports up to 15 bytes, got {n}")
    return bytes([0x80 + n]) + b


def string8(s: str) -> bytes:
    """STRING_8(N) + s,N=len(s),0<=N<=255。"""
    b = s.encode("utf-8")
    n = len(b)
    assert n <= 255
    return b"\xD0" + bytes([n]) + b


def int_value(v: int) -> bytes:
    if -16 <= v <= 127:
        return bytes([0xF0 + (v + 16)]) if False else bytes([v])  # 简化:不走 tiny int
    if -128 <= v <= 127:
        return b"\xC8" + v.to_bytes(1, "big", signed=True)
    if -32768 <= v <= 32767:
        return b"\xC9" + v.to_bytes(2, "big", signed=True)
    return b"\xCA" + v.to_bytes(4, "big", signed=True)


def make_hello() -> bytes:
    payload = (
        b"\x01"  # MSG_HELLO
        + b"\xA2"  # MAP_2
        + tiny_str("scheme")
        + tiny_str("basic")
        + tiny_str("user_agent")
        + tiny_str("z-graph-poc")  # 12 chars
    )
    return make_chunk(payload)


def make_run(cypher: str) -> bytes:
    cypher_bytes = cypher.encode("utf-8")
    n = len(cypher_bytes)
    if n <= 255:
        cypher_encoded = b"\xD0" + bytes([n]) + cypher_bytes
    else:
        cypher_encoded = b"\xD1" + n.to_bytes(2, "big") + cypher_bytes
    payload = (
        b"\x10"  # MSG_RUN
        + cypher_encoded
        + b"\xA0"  # MAP_0 (空 parameters)
    )
    return make_chunk(payload)


def make_pull(qid: int = 0, n: int = -1) -> bytes:
    if 0 <= qid <= 127:
        qid_encoded = b"\xC8" + bytes([qid])
    elif 0 <= qid <= 32767:
        qid_encoded = b"\xC9" + qid.to_bytes(2, "big")
    else:
        qid_encoded = b"\xCA" + qid.to_bytes(4, "big")
    if 0 <= n <= 127:
        n_encoded = b"\xC8" + bytes([n])
    else:
        n_encoded = b"\xC9" + (n & 0xFFFF).to_bytes(2, "big")
    payload = (
        b"\x3F"  # MSG_PULL
        + b"\xA2"  # MAP_2
        + tiny_str("qid") + qid_encoded
        + tiny_str("n") + n_encoded
    )
    return make_chunk(payload)


def make_goodbye() -> bytes:
    return make_chunk(b"\x02")


# ==================== 帧读取工具 ====================

def recv_chunk(sock: socket.socket, timeout: float = 5.0):
    """读取一个 chunk,返回 (signature, field_count, raw_payload)。"""
    sock.settimeout(timeout)
    header = b""
    while len(header) < 4:
        chunk = sock.recv(4 - len(header))
        if not chunk:
            raise ConnectionError("Connection closed before chunk header complete")
        header += chunk
    length = struct.unpack(">H", header[:2])[0]
    _marker = struct.unpack(">H", header[2:4])[0]
    payload = b""
    while len(payload) < length:
        chunk = sock.recv(length - len(payload))
        if not chunk:
            raise ConnectionError("Connection closed before payload complete")
        payload += chunk
    if len(payload) >= 3 and payload[0] == 0xDC:
        return payload[1], payload[2], payload
    return payload[0], -1, payload


def parse_record_field_values(payload: bytes):
    """解析 RECORD struct 后的 List 值(POC 简化:假定 list 是 [int_value])。"""
    # payload[0] = 0xDC marker
    # payload[1] = 0x71 signature (RECORD)
    # payload[2] = N field count
    # payload[3] = list marker (e.g. 0x90-0x9F tiny list, 0xD4 LIST_8)
    # payload[4+] = field values
    assert payload[0] == 0xDC and payload[1] == 0x71, f"Not a RECORD: {payload.hex()}"
    # Skip to list marker
    idx = 3
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
    values = []
    for _ in range(n):
        m = payload[idx]
        idx += 1
        if m == 0xC8:
            values.append(int.from_bytes(payload[idx:idx+1], "big", signed=True))
            idx += 1
        elif m == 0xC9:
            values.append(int.from_bytes(payload[idx:idx+2], "big", signed=True))
            idx += 2
        elif m == 0xCA:
            values.append(int.from_bytes(payload[idx:idx+4], "big", signed=True))
            idx += 4
        elif m == 0xCB:
            values.append(int.from_bytes(payload[idx:idx+8], "big", signed=True))
            idx += 8
        elif 0x80 <= m <= 0x8F:
            ln = m - 0x80
            values.append(payload[idx:idx+ln].decode("utf-8"))
            idx += ln
        elif m == 0xD0:
            ln = payload[idx]; idx += 1
            values.append(payload[idx:idx+ln].decode("utf-8"))
            idx += ln
        elif m == 0xC3:
            values.append(True)
        elif m == 0xC2:
            values.append(False)
        elif m == 0xC0:
            values.append(None)
        else:
            raise ValueError(f"Unknown value marker: 0x{m:02X} in RECORD")
    return values


# ==================== 测试用例 ====================

def execute_cypher(cypher: str, expected_field_count: int):
    """执行一条 Cypher,返回解析后的 record values(只支持 POC 子集)。"""
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect((HOST, PORT))
    try:
        sock.sendall(make_hello())
        sig, _, _ = recv_chunk(sock)
        assert sig == 0x70, f"HELLO response not SUCCESS: 0x{sig:02X}"

        sock.sendall(make_run(cypher))
        sig, fc, _ = recv_chunk(sock)
        assert sig == 0x70, f"RUN response not SUCCESS: 0x{sig:02X}"

        sock.sendall(make_pull(0))
        records = []
        # 期望:SUCCESS(开始) + RECORD + SUCCESS(完成)
        # 第 1 个 SUCCESS:qid
        sig, _, _ = recv_chunk(sock)
        assert sig == 0x70
        # 第 2 个:RECORD 或 SUCCESS
        sig, _, payload = recv_chunk(sock)
        if sig == 0x71:
            values = parse_record_field_values(payload)
            records.append(values)
            # 第 3 个:SUCCESS(完成)
            sig, _, _ = recv_chunk(sock)
            assert sig == 0x70
        elif sig == 0x70:
            pass  # 没有记录(空结果集)
        else:
            raise AssertionError(f"Expected RECORD or SUCCESS, got 0x{sig:02X}")

        sock.sendall(make_goodbye())
        return records
    finally:
        sock.close()


def run_all_tests():
    print("=" * 70)
    print("  z-graph Bolt POC 7-Scenario End-to-End Test")
    print("=" * 70)
    print(f"Target: {HOST}:{PORT}")
    print()

    test_cases = [
        ("RETURN 1 AS n", 1, "integer+alias", [[1]]),
        ('RETURN "hello" AS msg', 1, "string+alias", [["hello"]]),
        ('RETURN 1 AS x, "y" AS y, true AS n, false AS m', 4, "multi-types", [[1, "y", True, False]]),
        ("RETURN -42 AS n", 1, "negative", [[-42]]),
        ("RETURN 1, 2, 3", 3, "no-alias", [[1, 2, 3]]),
        ("RETURN null AS x", 1, "null", [[None]]),
        ("RETURN true AS flag", 1, "true", [[True]]),
        ("RETURN false AS flag", 1, "false", [[False]]),
        ("RETURN 1 AS n, 2 AS m", 2, "multi-col", [[1, 2]]),
        ('RETURN "abc" AS s, 100 AS n, true AS b', 3, "all-types", [["abc", 100, True]]),
    ]

    passed = 0
    failed = 0
    for cypher, expected_n, desc, expected_rows in test_cases:
        try:
            records = execute_cypher(cypher, expected_n)
            if records == expected_rows:
                print(f"  ✅ {desc:20s} | {cypher}")
                passed += 1
            else:
                print(f"  ❌ {desc:20s} | {cypher}")
                print(f"      Expected: {expected_rows}")
                print(f"      Got:      {records}")
                failed += 1
        except Exception as e:
            print(f"  ❌ {desc:20s} | {cypher}")
            print(f"      Exception: {type(e).__name__}: {e}")
            failed += 1

    print()
    print("=" * 70)
    print(f"  Total: {passed + failed}, Passed: {passed}, Failed: {failed}")
    print("=" * 70)
    return failed == 0


if __name__ == "__main__":
    success = run_all_tests()
    sys.exit(0 if success else 1)