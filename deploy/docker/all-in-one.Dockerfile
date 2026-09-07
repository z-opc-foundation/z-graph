# 多阶段构建 z-graph all-in-one 镜像:在同一容器内同时提供
# z-graph-server (Bolt 4.4 + HTTP 控制面) 和 z-graph-frontend (Nginx + React 控制台)。
# 适用于单机 demo、内网部署、单实例测试等场景。
# 生产分布式部署建议分别使用 server.Dockerfile 与 frontend.Dockerfile。

# ===== 第一阶段:用 Maven 编译后端 =====
FROM maven:3.9.9-eclipse-temurin-17 AS server-build
WORKDIR /workspace

COPY pom.xml z-graph-api/pom.xml z-graph-protocol/pom.xml \
     z-graph-core/pom.xml z-graph-bolt-server/pom.xml \
     z-graph-spring-boot-starter/pom.xml \
     ./

RUN mvn -B -ntp -DskipTests dependency:go-offline || \
    mvn -B -ntp -DskipTests -pl z-graph-bolt-server -am dependency:go-offline

COPY . .
RUN mvn -B -ntp -DskipTests \
    -pl z-graph-bolt-server -am package \
    -Dmaven.javadoc.skip=true -Dassembly.skipAssembly=true

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

# 安装 nginx 与 envsubst 用于端口反向代理
RUN apt-get update && apt-get install -y --no-install-recommends \
        nginx wget gettext-base tini && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --system zgraph && useradd --system --gid zgraph --uid 10001 zgraph

# 数据持久化目录
RUN mkdir -p /var/lib/z-graph /var/log/z-graph /var/lib/nginx /var/log/nginx /run/nginx && \
    chown -R zgraph:zgraph /var/lib/z-graph /var/log/z-graph && \
    chown -R www-data:www-data /var/lib/nginx /var/log/nginx /run/nginx

WORKDIR /opt/z-graph

# 后端 jar
COPY --from=server-build /workspace/z-graph-bolt-server/target/z-graph-bolt-server-*.jar /opt/z-graph/server.jar

# 前端静态资源
COPY --from=frontend-build /workspace/z-graph-console/dist /opt/z-graph/console

# Nginx 配置 + 入口脚本
COPY deploy/nginx/frontend.conf /etc/nginx/templates/default.conf.template
COPY deploy/docker/all-in-one-entrypoint.sh /usr/local/bin/all-in-one-entrypoint.sh
COPY deploy/docker/all-in-one-nginx.conf /etc/nginx/nginx.conf
RUN chmod +x /usr/local/bin/all-in-one-entrypoint.sh

USER zgraph
EXPOSE 80 7687 8090

ENV Z_GRAPH_BOLT_PORT=7687 \
    Z_GRAPH_HTTP_PORT=8090 \
    Z_GRAPH_DATA_DIR=/var/lib/z-graph \
    Z_GRAPH_API_UPSTREAM=127.0.0.1:8090 \
    Z_GRAPH_CORS_ALLOWED_ORIGINS=*

# 顶层入口:tini 转发信号给 nginx + z-graph-server
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/all-in-one-entrypoint.sh"]
