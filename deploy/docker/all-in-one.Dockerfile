# 多阶段构建 z-graph all-in-one 镜像:在同一容器内同时提供
# z-graph-server (Bolt 4.4 + HTTP 控制面) 和 z-graph-frontend (Nginx + React 控制台)。
# 适用于单机 demo、内网部署、单实例测试等场景。
# 生产分布式部署建议分别使用 server.Dockerfile 与 frontend.Dockerfile。

# ===== 第一阶段:用 Maven 编译后端 =====
FROM maven:3.9.9-eclipse-temurin-17 AS server-build
WORKDIR /workspace

# 注意:每个模块的 <parent><relativePath> 已设为 ../pom.xml,
# 所以必须保持 src 目录布局原样。
COPY pom.xml /workspace/pom.xml
COPY z-graph-api/pom.xml             /workspace/z-graph-api/pom.xml
COPY z-graph-protocol/pom.xml        /workspace/z-graph-protocol/pom.xml
COPY z-graph-core/pom.xml            /workspace/z-graph-core/pom.xml
COPY z-graph-bolt-server/pom.xml     /workspace/z-graph-bolt-server/pom.xml
COPY z-graph-spring-boot-starter/pom.xml /workspace/z-graph-spring-boot-starter/pom.xml

RUN mvn -B -ntp -f /workspace/pom.xml -N install -DskipTests
RUN mvn -B -ntp -DskipTests -f /workspace/pom.xml -pl z-graph-bolt-server -am dependency:go-offline

COPY . /workspace/
# 先 install 所有模块到本地仓库
RUN mvn -B -ntp -DskipTests \
    -f /workspace/pom.xml \
    -pl z-graph-bolt-server -am install \
    -Dmaven.javadoc.skip=true -Dassembly.skipAssembly=true

# 复制依赖 jar 到 staging 目录
RUN mvn -B -ntp -f /workspace/pom.xml \
    -pl z-graph-bolt-server dependency:copy-dependencies \
    -DoutputDirectory=/workspace/z-graph-bolt-server/target/lib \
    -DincludeScope=runtime

# ===== 第二阶段:用 Node 构建前端 =====
FROM node:20-alpine AS frontend-build
WORKDIR /workspace
COPY z-graph-console/package.json z-graph-console/package-lock.json* ./z-graph-console/
WORKDIR /workspace/z-graph-console
RUN npm install --no-audit --no-fund
COPY z-graph-console ./
RUN npm run build

# ===== 第三阶段:运行时镜像 =====
FROM eclipse-temurin:17-jre-jammy AS runtime
LABEL org.opencontainers.image.title="z-graph-all-in-one" \
      org.opencontainers.image.description="z-graph 单实例:后端 + 前端一起部署" \
      org.opencontainers.image.source="https://github.com/z-opc-foundation/z-graph"

# 安装 nginx 与 tini
RUN apt-get update && apt-get install -y --no-install-recommends \
        nginx wget tini && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --system zgraph && useradd --system --gid zgraph --uid 10001 zgraph

# 数据持久化目录
RUN mkdir -p /var/lib/z-graph /var/log/z-graph /var/lib/nginx /var/log/nginx /run/nginx && \
    chown -R zgraph:zgraph /var/lib/z-graph /var/log/z-graph && \
    chown -R www-data:www-data /var/lib/nginx /var/log/nginx /run/nginx && \
    rm -f /usr/share/nginx/html/index.html 2>/dev/null; true

WORKDIR /opt/z-graph

# 后端 jar
COPY --from=server-build /workspace/z-graph-bolt-server/target/z-graph-bolt-server-*.jar /opt/z-graph/server.jar
COPY --from=server-build /workspace/z-graph-bolt-server/target/lib/                    /opt/z-graph/lib/

# 前端静态资源
COPY --from=frontend-build /workspace/z-graph-console/dist /opt/z-graph/console

# Nginx 主配置(静态,由 root 在构建阶段写入)
RUN printf 'user www-data;\nworker_processes auto;\npid /run/nginx.pid;\nevents { worker_connections 1024; }\nhttp {\n    include /etc/nginx/mime.types;\n    default_type application/octet-stream;\n    sendfile on;\n    keepalive_timeout 65;\n    access_log /dev/stdout;\n    error_log /dev/stderr;\n    include /etc/nginx/conf.d/*.conf;\n}\n' > /etc/nginx/nginx.conf

# Nginx 站点配置(静态)
RUN printf 'upstream z_graph_api {\n    server 127.0.0.1:8090;\n    keepalive 16;\n}\nserver {\n    listen 3000;\n    server_name _;\n    root /usr/share/nginx/html;\n    index index.html;\n    location / { try_files $uri $uri/ /index.html; add_header Cache-Control no-cache; }\n    location /api/ { proxy_pass http://z_graph_api/; proxy_set_header Host $host; proxy_set_header X-Real-IP $remote_addr; proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for; proxy_http_version 1.1; proxy_set_header Connection ""; proxy_read_timeout 60s; }\n    location = /healthz { access_log off; return 200 "ok\\n"; }\n}\n' > /etc/nginx/conf.d/default.conf

# 入口脚本
COPY deploy/docker/all-in-one-entrypoint.sh /usr/local/bin/all-in-one-entrypoint.sh
RUN chmod +x /usr/local/bin/all-in-one-entrypoint.sh

# all-in-one 单机部署以 root 运行(nginx 需要 master 进程为 root)
EXPOSE 3000 7687 8090

ENV Z_GRAPH_BOLT_PORT=7687 \
    Z_GRAPH_HTTP_PORT=8090 \
    Z_GRAPH_DATA_DIR=/var/lib/z-graph \
    Z_GRAPH_CORS_ALLOWED_ORIGINS=*

# 顶层入口:tini 转发信号给 nginx + z-graph-server
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/all-in-one-entrypoint.sh"]
