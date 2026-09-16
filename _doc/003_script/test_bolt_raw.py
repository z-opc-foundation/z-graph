#!/usr/bin/env python3
"""
z-graph Bolt POC 简化端到端测试 — 不依赖 neo4j Python driver,直接走 TCP socket。

验证流程:
1. TCP 连接(验证服务监听)
2. 发送最小 Bolt HELLO 帧(0x01 0x00 0x0E 0x00 + 简单 metadata map)
3. 读取响应(应该是 SUCCESS chunk)
4. 发送最小 RUN 帧(0x10 0x00 0x0E 0x00 + Cypher 字符串 "RETURN 1 AS n")
5. 读取响应(应该是 SUCCESS + RECORD + SUCCESS)
"""

import os
import socket
import struct
import sys

HOST = os.environ.get("Z_GRAPH_HOST", "localhost")
PORT = int(os.environ.get("Z_GRAPH_PORT", "7687"))


def make_chunk(payload: bytes) -> bytes:
    """构造一个完整的 chunk:2-byte length + 2-byte end marker + payload"""
    return struct.pack(">HH", len(payload), 0x0E00) + payload


def make_hello() -> bytes:
    """Bolt HELLO 消息:signature 0x01 + 一个 metadata map(scheme + scheme_data + user_agent)"""
    # POC 简化:metadata 是 MAP_1 (0xA1) + 2 个键值对
    # key "scheme" -> string "basic"
    # key "principal" -> string "neo4j"
    # key "credentials" -> string "neo4j"
    # 用 TINY_STRING + STRING_8 marker
    scheme_str = b"\x86scheme"  # TINY_STRING(6 bytes) + 'scheme' (s,c,h,e,m,e = 6 chars)
    principal_str = b"\x89principal"  # TINY_STRING(9) + 'principal'
    credentials_str = b"\x8bcredentials"  # 'credentials' = 11 chars (0x8b = 11)
    user_agent_str = b"\x89user_agent"  # TINY_STRING(9) + 'user_agent'
    basic_str = b"\x85basic"  # 'basic' = 5 chars (0x85 = 5)
    neo4j_str = b"\x85neo4j"  # 'neo4j' = 5 chars

    # MAP_3(3 个键值对):0xA3 + key1+val1 + key2+val2 + key3+val3
    # 我们用 4 个键值对:scheme / principal / credentials / user_agent
    # 实际 POC 阶段最简,只发 scheme
    # MAP_1 (0xA1)
    payload = (
        b"\x01"  # MSG_HELLO signature
        + b"\xA1"  # MAP_1
        + scheme_str  # key: scheme
        + basic_str   # value: basic
        + user_agent_str  # key: user_agent
        + b"\x8Dz-graph-poc/"  # value: "z-graph-poc/" = 12 字符 (0x8C = 12)
    )
    return make_chunk(payload)


def make_run(cypher: str) -> bytes:
    """Bolt RUN 消息:signature 0x10 + cypher 字符串 + parameters map(空)"""
    cypher_bytes = cypher.encode("utf-8")
    # STRING_8 + length byte + cypher
    cypher_encoded = b"\xD0" + bytes([len(cypher_bytes)]) + cypher_bytes
    # MAP_0 (空 parameters)
    payload = b"\x10" + cypher_encoded + b"\xA0"
    return make_chunk(payload)


def make_pull(qid: int = 0, n: int = -1) -> bytes:
    """Bolt PULL 消息:signature 0x3F + metadata map {qid: N, n: M}"""
    # INT_8 for small values, INT_16 for medium
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
        + b"\x83qid" + qid_encoded
        + b"\x81n" + n_encoded
    )
    return make_chunk(payload)


def make_goodbye() -> bytes:
    """Bolt GOODBYE 消息:signature 0x02"""
    return make_chunk(b"\x02")


def recv_chunk(sock: socket.socket, timeout: float = 5.0) -> tuple:
    """读取一个 chunk:4 字节头 + payload。

    返回:(signature, field_count, raw_payload)
    - signature:SUCCESS=0x70, RECORD=0x71, FAILURE=0x7F 等
    - field_count:struct 字段数(>= 0)
    - raw_payload:完整的 chunk payload bytes
    """
    sock.settimeout(timeout)
    header = b""
    while len(header) < 4:
        chunk = sock.recv(4 - len(header))
        if not chunk:
            raise ConnectionError("Connection closed before chunk header complete")
        header += chunk
    length = struct.unpack(">H", header[:2])[0]
    marker = struct.unpack(">H", header[2:4])[0]
    if marker != 0x0E00:
        print(f"⚠️  Non-end chunk marker: 0x{marker:04X} (length={length})")
    payload = b""
    while len(payload) < length:
        chunk = sock.recv(length - len(payload))
        if not chunk:
            raise ConnectionError("Connection closed before payload complete")
        payload += chunk
    # 解析 struct:0xDC marker + 1 byte signature + 1 byte field count
    if len(payload) >= 3 and payload[0] == 0xDC:
        signature = payload[1]
        field_count = payload[2]
    else:
        signature = payload[0]
        field_count = -1
    return signature, field_count, payload


def run():
    print(f"=== z-graph Bolt POC simplified E2E test ===")
    print(f"Target: {HOST}:{PORT}")
    print()

    # 1) TCP 连接
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect((HOST, PORT))
    print(f"[1] ✅ TCP connection established")

    # 2) 发送 HELLO
    sock.sendall(make_hello())
    print(f"[2] ✅ Sent HELLO ({len(make_hello())} bytes)")

    # 3) 接收 HELLO response(应该是 SUCCESS chunk,signature 0x70)
    try:
        sig, fc, payload = recv_chunk(sock, timeout=5.0)
        print(f"[3] Received HELLO response: signature=0x{sig:02X}, field_count={fc}, payload_len={len(payload)}")
        if sig == 0x70:
            print(f"    → SUCCESS (HELLO accepted)")
        elif sig == 0x7F:
            print(f"    → FAILURE (HELLO rejected) — see server log")
            return False
        else:
            print(f"    → Unknown signature 0x{sig:02X}")
    except Exception as e:
        print(f"[3] ❌ Failed to receive HELLO response: {e}")
        return False

    # 4) 发送 RUN
    sock.sendall(make_run("RETURN 1 AS n"))
    print(f"[4] ✅ Sent RUN 'RETURN 1 AS n' ({len(make_run('RETURN 1 AS n'))} bytes)")

    # 5) 接收 RUN response(应该是 SUCCESS signature=0x70, 含 qid + fields)
    try:
        sig, fc, payload = recv_chunk(sock, timeout=5.0)
        print(f"[5] Received RUN response: signature=0x{sig:02X}, field_count={fc}, payload_len={len(payload)}")
        if sig == 0x70:
            print(f"    → SUCCESS (RUN accepted, stream opened)")
        else:
            print(f"    → Non-SUCCESS signature 0x{sig:02X}")
    except Exception as e:
        print(f"[5] ❌ Failed to receive RUN response: {e}")
        return False

    # 6) 发送 PULL(qid=0 即第一个 RUN 的 stream)
    sock.sendall(make_pull(0))
    print(f"[6] ✅ Sent PULL (qid=0)")

    # 7) 接收 PULL response(SUCCESS + RECORD + SUCCESS)
    records = []
    for i in range(5):
        try:
            sig, fc, payload = recv_chunk(sock, timeout=5.0)
            print(f"[7.{i+1}] Received: signature=0x{sig:02X}, field_count={fc}, payload_len={len(payload)}")
            if sig == 0x70:
                print(f"      → SUCCESS")
            elif sig == 0x71:
                print(f"      → RECORD (data)")
                records.append(payload)
            elif sig == 0x7F:
                print(f"      → FAILURE")
                return False
            else:
                print(f"      → Other")
        except Exception as e:
            print(f"[7.{i+1}] Failed: {e}")
            break

    # 8) GOODBYE
    sock.sendall(make_goodbye())
    sock.close()
    print(f"[8] ✅ Sent GOODBYE, connection closed")

    # 结果评估
    print()
    if records:
        print(f"=== ✅ E2E TEST PASSED: {len(records)} record(s) received ===")
        return True
    else:
        print(f"=== ⚠️  E2E TEST INCOMPLETE: no RECORD received (protocol negotiation issue) ===")
        return False


if __name__ == "__main__":
    success = run()
    sys.exit(0 if success else 1)