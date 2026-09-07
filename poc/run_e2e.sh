#!/bin/bash
# z-graph Bolt POC 端到端测试编排脚本
# 流程:编译 Java → 启动 BoltServer → 跑 Python 端到端测试 → 关闭服务
set -e

Z_GRAPH_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$Z_GRAPH_DIR/.."

PORT="${PORT:-7687}"

echo "==============================================="
echo "  z-graph Bolt POC E2E Test Orchestrator"
echo "==============================================="
echo "Workspace: $Z_GRAPH_DIR/.."
echo "Bolt port: $PORT"
echo ""

# 1) 检查 neo4j python driver 是否已安装
echo "[Step 1] Checking neo4j Python driver..."
python3 -c "import neo4j" 2>/dev/null || {
    echo "  neo4j Python driver not found, installing..."
    pip3 install neo4j==5.20.0 --quiet
}

# 2) mvn 编译 + 单元测试
echo ""
echo "[Step 2] mvn compile + test..."
cd "$Z_GRAPH_DIR/.."
mvn -B -q -pl z-graph-bolt-server,z-graph-core,z-graph-protocol,z-graph-api \
    -am compile test 2>&1 | tail -50

if [ ${PIPESTATUS[0]} -ne 0 ]; then
    echo "❌ mvn compile/test failed"
    exit 1
fi
echo "✅ Unit tests passed"

# 3) 启动 BoltServer(后台)
echo ""
echo "[Step 3] Starting z-graph BoltServer on port $PORT..."
SERVER_LOG="$Z_GRAPH_DIR/bolt-server.log"
SERVER_PID_FILE="$Z_GRAPH_DIR/bolt-server.pid"

# 用 maven exec 跑(避免 classpath 问题)
nohup mvn -B -q exec:java \
    -pl z-graph-bolt-server \
    -Dexec.mainClass="com.zifang.z.graph.bolt.BoltServer" \
    -Dexec.args="$PORT" \
    -Dexec.cleanupDaemonThreads=false \
    > "$SERVER_LOG" 2>&1 &
SERVER_PID=$!
echo "$SERVER_PID" > "$SERVER_PID_FILE"
echo "  Server PID: $SERVER_PID"

# 等服务起来
sleep 5
if ! kill -0 $SERVER_PID 2>/dev/null; then
    echo "❌ BoltServer failed to start, see $SERVER_LOG"
    cat "$SERVER_LOG"
    exit 1
fi
echo "✅ BoltServer is up"

# 4) 跑 Python 端到端测试
echo ""
echo "[Step 4] Running Python E2E tests..."
Z_GRAPH_BOLT_URL="bolt://localhost:$PORT" \
    python3 "$Z_GRAPH_DIR/test_bolt_poc.py"
PY_RESULT=$?

# 5) 清理
echo ""
echo "[Step 5] Cleaning up..."
kill $SERVER_PID 2>/dev/null || true
rm -f "$SERVER_PID_FILE"
sleep 1

# 6) 输出结果
echo ""
echo "==============================================="
if [ $PY_RESULT -eq 0 ]; then
    echo "  ✅ ALL TESTS PASSED"
else
    echo "  ❌ E2E TESTS FAILED"
    echo "  Server log: $SERVER_LOG"
fi
echo "==============================================="
exit $PY_RESULT