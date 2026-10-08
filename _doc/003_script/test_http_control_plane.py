#!/usr/bin/env python3
"""
z-graph HTTP 控制面端到端测试 — 只用标准库，不依赖任何第三方包。

前置: ZGraphServer 已在 HTTP_PORT 起来（bolt 端口不参与本测试）。
覆盖场景:
1. /health 返回 UP 且带 head 指纹
2. /query POST 写查询（CREATE 自动落 commit）
3. /meta/commits 的 commit 图（root + 写入历史）
4. /meta/stats 计数与 head 一致
5. /query?commit= 时间旅行: 历史 commit 上读不到后来的数据
6. /meta/schema 读回 TAG 定义
7. kill -9 后重启: head 与读值逐字节一致（落盘重启幂等）

用法: python3 test_http_control_plane.py [http_port]
"""
import json
import os
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request

HOST = os.environ.get("Z_GRAPH_HOST", "127.0.0.1")
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else int(os.environ.get("Z_GRAPH_HTTP_PORT", "8090"))
BASE = f"http://{HOST}:{PORT}"

failures = []


def check(name, condition, detail=""):
    mark = "PASS" if condition else "FAIL"
    print(f"[{mark}] {name}" + (f"  ({detail})" if detail and not condition else ""))
    if not condition:
        failures.append(name)


def request(method, path, body=None):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        return resp.status, json.loads(resp.read().decode())


def query(cypher, commit=None):
    body = {"cypher": cypher, "branch": "main"}
    if commit:
        body["commit"] = commit
    return request("POST", "/query", body)


def wait_health(seconds=15):
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            status, body = request("GET", "/health")
            if status == 200 and body.get("status") == "UP":
                return body
        except Exception:
            pass
        time.sleep(0.3)
    raise RuntimeError(f"server at {BASE} did not become healthy")


def find_commits_by_message(commits, needle):
    return [c for c in commits if needle in c["message"]]


def main():
    health = wait_health()
    check("health UP with head fingerprint",
          bool(health.get("head")) and health.get("nodeCount") == 0, str(health))

    # 写入两轮，每轮一个 commit。引擎契约：CREATE..RETURN 返回节点属性字典（含 id/label）。
    status, rows = query("CREATE (n:Person {name: 'Alice'}) RETURN n.name AS name")
    check("create Alice via /query", status == 200 and rows and rows[0].get("name") == "Alice", str(rows))
    status, rows = query("CREATE (n:Person {name: 'Bob'}) RETURN n.name AS name")
    check("create Bob via /query", status == 200 and rows and rows[0].get("name") == "Bob", str(rows))

    status, commits = request("GET", "/meta/commits")
    check("commit graph has root + 2 writes", status == 200 and len(commits) == 3, str(len(commits)))
    root = find_commits_by_message(commits, "Initial graph")
    alice_commit = find_commits_by_message(commits, "Alice")
    bob_commit = find_commits_by_message(commits, "Bob")
    check("root and write commits present",
          len(root) == 1 and len(alice_commit) == 1 and len(bob_commit) == 1)
    root_id = root[0]["id"]
    check("commit graph parents link",
          alice_commit[0]["parents"] == [root_id]
          and bob_commit[0]["parents"] == [alice_commit[0]["id"]],
          f"alice={alice_commit[0]['parents']} bob={bob_commit[0]['parents']}")

    status, stats = request("GET", "/meta/stats")
    check("stats head counts == 2 nodes", status == 200 and stats.get("nodeCount") == 2, str(stats))

    # 时间旅行: 根 commit 上必须读不到后来写入的两个节点。
    root_id = root[0]["id"]
    status, rows = query("MATCH (n:Person) RETURN count(n) AS c", commit=root_id)
    check("time travel: root sees 0 nodes", status == 200 and rows == [{"c": 0}], str(rows))
    alice_id = alice_commit[0]["id"]
    status, rows = query("MATCH (n:Person) RETURN count(n) AS c", commit=alice_id)
    check("time travel: Alice commit sees 1 node", status == 200 and rows == [{"c": 1}], str(rows))
    status, rows = query("MATCH (n:Person) RETURN n.name AS name ORDER BY name")
    check("head sees both nodes", status == 200
          and [r["name"] for r in rows] == ["Alice", "Bob"], str(rows))

    status, rows = query("CREATE TAG Person (name STRING, age INT)")
    check("create Person tag via /query DDL", status == 200, f"{status} {rows}")
    status, schema = request("GET", "/meta/schema")
    tag_names = [(t.get("name") or t.get("Name")) for t in schema.get("tags", [])] if isinstance(schema.get("tags"), list) else []
    check("schema lists declared Person tag", status == 200 and "Person" in tag_names, str(schema)[:200])

    # kill -9 后重启：head 指纹与读值必须逐字节一致。
    head_before = request("GET", "/health")[1]["head"]
    pid = int(os.environ["Z_GRAPH_SERVER_PID"])
    os.kill(pid, signal.SIGKILL)
    time.sleep(1)
    # 模板形如: java -cp <cp> com.zifang.z.graph.bolt.ZGraphServer 7688 {port}
    # {port} 会被替换成 HTTP 端口（ZGraphServer 首参是 bolt 端口，模板里自己写死）。
    cmd = os.environ["Z_GRAPH_RESTART_CMD"].replace("{port}", str(PORT))
    proc = subprocess.Popen(["sh", "-c", cmd],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        health2 = wait_health()
        check("restart: head id stable (content-addressed reopen)",
              health2["head"] == head_before, f"{health2['head']} != {head_before}")
        status, rows = query("MATCH (n:Person) RETURN n.name AS name ORDER BY name")
        check("restart: reads identical", status == 200
              and [r["name"] for r in rows] == ["Alice", "Bob"], str(rows))
    finally:
        proc.terminate()

    print()
    if failures:
        print(f"FAILED: {len(failures)} -> {failures}")
        sys.exit(1)
    print("ALL HTTP CONTROL-PLANE E2E PASSED")


if __name__ == "__main__":
    main()
