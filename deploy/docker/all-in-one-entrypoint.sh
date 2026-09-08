#!/bin/sh
# z-graph all-in-one 容器入口:并行启动 z-graph-server (Java) 与 nginx (前端 + API 代理)。
# nginx.conf 和站点配置已在 Dockerfile 构建阶段由 root 写入,此处无需再创建。

set -e

echo "==> z-graph all-in-one starting"
echo "==> bolt port=${Z_GRAPH_BOLT_PORT:-7687}"
echo "==> http port=${Z_GRAPH_HTTP_PORT:-8090}"
echo "==> data dir=${Z_GRAPH_DATA_DIR:-/var/lib/z-graph}"

# 将前端静态资源复制到 nginx 根目录
if [ -d /opt/z-graph/console ]; then
    cp -a /opt/z-graph/console/* /usr/share/nginx/html/ 2>/dev/null || true
fi

# 启动 nginx(后台)
nginx

# 启动 z-graph-server(前台),tini 会把 SIGTERM 转发到这里
cd /opt/z-graph
exec java \
    -Xms256m -Xmx1024m \
    -Dz.graph.dataDir="${Z_GRAPH_DATA_DIR:-/var/lib/z-graph}" \
    -cp "/opt/z-graph/lib/*:/opt/z-graph/server.jar" \
    com.zifang.z.graph.bolt.ZGraphServer
