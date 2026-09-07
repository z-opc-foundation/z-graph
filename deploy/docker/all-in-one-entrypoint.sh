#!/bin/sh
# z-graph all-in-one 容器入口:envsubst 注入 nginx upstream,然后并行启动
# z-graph-server (Java) 与 nginx (前端静态 + API 反向代理)。
# 通过 tini 接收 SIGTERM 并优雅关闭两个进程。

set -e

# 写入默认 nginx 主配置 + 处理模板
cat > /etc/nginx/nginx.conf <<'EOF'
user www-data;
worker_processes auto;
pid /run/nginx.pid;
events { worker_connections 1024; }
http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;
    sendfile on;
    keepalive_timeout 65;
    access_log /dev/stdout;
    error_log /dev/stderr;
    include /etc/nginx/conf.d/*.conf;
}
EOF

# 处理 upstream 模板,envsubst 注入 Z_GRAPH_API_UPSTREAM
envsubst '${Z_GRAPH_API_UPSTREAM}' < /etc/nginx/templates/default.conf.template \
    > /etc/nginx/conf.d/default.conf

echo "==> z-graph all-in-one starting"
echo "==> upstream=${Z_GRAPH_API_UPSTREAM:-127.0.0.1:8090}"
echo "==> bolt port=${Z_GRAPH_BOLT_PORT:-7687}"
echo "==> http port=${Z_GRAPH_HTTP_PORT:-8090}"
echo "==> data dir=${Z_GRAPH_DATA_DIR:-/var/lib/z-graph}"

# 启动 nginx(后台)
nginx

# 启动 z-graph-server(前台),tini 会把 SIGTERM 转发到这里
cd /opt/z-graph
exec java \
    -Xms256m -Xmx1024m \
    -Dz.graph.dataDir="${Z_GRAPH_DATA_DIR:-/var/lib/z-graph}" \
    -cp /opt/z-graph/server.jar \
    com.zifang.z.graph.bolt.ZGraphServer
