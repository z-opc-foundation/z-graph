#!/bin/bash
# z-graph T1 阶段完整验证脚本
# 流程:环境检查 → Maven compile/test → 启 BoltServer → 跑 7 场景 E2E → 清理
#
# Usage:
#   ./run_t1_verify.sh             # 完整流程(默认)
#   ./run_t1_verify.sh unit-only   # 只跑单元测试
#   ./run_t1_verify.sh e2e-only    # 只跑端到端(假定 server 已启动)

set -uo pipefail

Z_GRAPH_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$Z_GRAPH_DIR"

PORT="${PORT:-7687}"
MODE="${1:-full}"

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

PASS_COUNT=0
FAIL_COUNT=0

step() { echo -e "${YELLOW}▶ $*${NC}"; }
pass() { echo -e "${GREEN}✅ $*${NC}"; PASS_COUNT=$((PASS_COUNT+1)); }
fail() { echo -e "${RED}❌ $*${NC}"; FAIL_COUNT=$((FAIL_COUNT+1)); }

cleanup() {
    if [ -n "${SERVER_PID:-}" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
        echo ""
        step "清理:kill BoltServer (PID=$SERVER_PID)"
        kill "$SERVER_PID" 2>/dev/null
        wait "$SERVER_PID" 2>/dev/null || true
    fi
    rm -f "$Z_GRAPH_DIR/poc/bolt-server.pid"
    rm -f /tmp/zgraph-e2e-final.log
}
trap cleanup EXIT

# ==================== 1. 环境检查 ====================

if [ "$MODE" != "e2e-only" ]; then
    step "[1/5] 环境检查"
    command -v mvn >/dev/null 2>&1 || { fail "mvn 未安装"; exit 1; }
    command -v java >/dev/null 2>&1 || { fail "java 未安装"; exit 1; }
    command -v python3 >/dev/null 2>&1 || { fail "python3 未安装"; exit 1; }
    pass "mvn / java / python3 都已安装"
fi

# ==================== 2. 单元测试 ====================

if [ "$MODE" = "full" ] || [ "$MODE" = "unit-only" ]; then
    step "[2/5] Maven 单元测试"
    mvn -B -q test 2>&1 | tail -20
    if [ ${PIPESTATUS[0]} -eq 0 ]; then
        UNIT_TEST_LINE=$(mvn -B test 2>&1 | grep "Tests run:" | tail -2)
        echo ""
        echo "  $UNIT_TEST_LINE"
        pass "所有单元测试通过"
    else
        fail "单元测试失败"
        exit 1
    fi
fi

# ==================== 3. 启动 BoltServer ====================

step "[3/5] 启动 z-graph BoltServer (port=$PORT)"
mvn -B -q install -DskipTests > /tmp/zgraph-install.log 2>&1
if [ ${PIPESTATUS[0]} -ne 0 ]; then
    fail "mvn install 失败"
    tail -30 /tmp/zgraph-install.log
    exit 1
fi

mvn -B -pl z-graph-bolt-server exec:java \
    -Dexec.mainClass="com.zifang.z.graph.bolt.BoltServer" \
    -Dexec.args="$PORT" \
    -Dexec.cleanupDaemonThreads=false \
    > /tmp/zgraph-e2e-final.log 2>&1 &
SERVER_PID=$!
echo "$SERVER_PID" > "$Z_GRAPH_DIR/poc/bolt-server.pid"
echo "  Server PID: $SERVER_PID"

# 等服务起来(最多 30 秒)
for i in 1 2 3 4 5 6; do
    sleep 5
    if ! kill -0 $SERVER_PID 2>/dev/null; then
        fail "BoltServer 启动失败"
        tail -30 /tmp/zgraph-e2e-final.log
        exit 1
    fi
    if lsof -i :$PORT >/dev/null 2>&1; then
        pass "BoltServer 在 port $PORT 监听"
        break
    fi
    if [ "$i" = "6" ]; then
        fail "BoltServer 30s 内未监听 port $PORT"
        tail -30 /tmp/zgraph-e2e-final.log
        exit 1
    fi
done

# ==================== 4. 端到端测试 ====================

step "[4/6] 7 场景 RETURN literal E2E"
python3 "$Z_GRAPH_DIR/poc/test_bolt_full.py"
PY_RESULT=$?
if [ $PY_RESULT -eq 0 ]; then
    pass "全部 7 个 RETURN literal 场景通过"
else
    fail "RETURN literal E2E 失败"
    echo ""
    echo "=== Server log (last 30 lines) ==="
    tail -30 /tmp/zgraph-e2e-final.log
fi

step "[5/6] 9 场景错误路径 E2E"
python3 "$Z_GRAPH_DIR/poc/test_bolt_error.py"
ERR_RESULT=$?
if [ $ERR_RESULT -eq 0 ]; then
    pass "全部 9 个错误路径场景通过"
else
    fail "错误路径 E2E 失败"
fi

step "[6/6] 100 连接 × 5 查询并发压力"
NUM_CLIENTS="${CONCURRENCY_CLIENTS:-100}" \
QUERIES_PER_CLIENT="${CONCURRENCY_QUERIES:-5}" \
    python3 "$Z_GRAPH_DIR/poc/test_bolt_concurrent.py"
CONC_RESULT=$?
if [ $CONC_RESULT -eq 0 ]; then
    pass "并发压力测试 100 连接 × 5 查询 全部成功"
else
    fail "并发压力测试失败"
fi

# ==================== 7. 三方合规扫描 ====================

step "[7/7] 三方合规扫描"
set +e
# 用临时关键字文件避免脚本自身被命中
KEYWORDS_FILE=$(mktemp)
cat > "$KEYWORDS_FILE" <<'EOF'
galaxy
Galaxy
kapi-base
cfuture.shop
FtGalaxy
GalaxyOntology
future-team
FutureTeam
future_team
ft_
com.c2f.
EOF
KEYWORD_PATTERN=$(tr '\n' '|' < "$KEYWORDS_FILE" | sed 's/|$//')
rm -f "$KEYWORDS_FILE"

VIOLATIONS=$(grep -rlE "$KEYWORD_PATTERN" \
    "$Z_GRAPH_DIR/../_doc/001_feature/062_z-graph 自研图数据库服务调研与方案/" \
    "$Z_GRAPH_DIR/../_doc/001_feature/FEATURE_MANAGER.md" \
    "$Z_GRAPH_DIR/../_doc/000_arch/06-active-work.md" \
    --include="*.md" \
    2>/dev/null)
JAVA_VIOLATIONS=$(grep -rlE "$KEYWORD_PATTERN" \
    "$Z_GRAPH_DIR" \
    "$Z_GRAPH_DIR/../z-vector/" \
    --include="*.java" --include="*.py" --include="*.xml" \
    2>/dev/null)
set -u
if [ -z "$VIOLATIONS" ] && [ -z "$JAVA_VIOLATIONS" ]; then
    pass "FEATURE062 + z-graph + z-vector 0 命中公司内部命名"
else
    fail "发现违规关键字:"
    echo "  Docs: $VIOLATIONS"
    echo "  Code: $JAVA_VIOLATIONS"
fi

# ==================== 总结 ====================

echo ""
echo "==============================================="
if [ $FAIL_COUNT -eq 0 ] && [ $PY_RESULT -eq 0 ] && [ $ERR_RESULT -eq 0 ] && [ $CONC_RESULT -eq 0 ]; then
    echo -e "${GREEN}  🎉 T1 阶段验证全部通过${NC}"
    echo "==============================================="
    echo "  Pass: $PASS_COUNT | Fail: $FAIL_COUNT"
    exit 0
else
    echo -e "${RED}  ❌ T1 阶段验证有失败${NC}"
    echo "==============================================="
    echo "  Pass: $PASS_COUNT | Fail: $FAIL_COUNT | full: $PY_RESULT | err: $ERR_RESULT | conc: $CONC_RESULT"
    exit 1
fi