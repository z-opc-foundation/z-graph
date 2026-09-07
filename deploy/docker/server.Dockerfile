# 多阶段构建:第一阶段用 Maven 编译并打包 z-graph 服务端 jar,
# 第二阶段用 JRE 镜像做运行时,只保留 jar 与运行时依赖。
# 适用于分布式部署:仅部署 z-graph-server,前端由 z-graph-frontend 镜像独立提供。

# ===== 构建阶段 =====
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# 先复制 pom 文件,让依赖层独立缓存
COPY pom.xml z-graph-api/pom.xml z-graph-protocol/pom.xml \
     z-graph-core/pom.xml z-graph-bolt-server/pom.xml \
     z-graph-spring-boot-starter/pom.xml \
     ./

# 仅下载依赖,利用 Docker 缓存
RUN mvn -B -ntp -DskipTests dependency:go-offline || \
    mvn -B -ntp -DskipTests -pl z-graph-bolt-server -am dependency:go-offline

# 复制源代码并打包
COPY . .
RUN mvn -B -ntp -DskipTests \
    -pl z-graph-bolt-server -am package \
    -Dmaven.javadoc.skip=true -Dassembly.skipAssembly=true

# ===== 运行时阶段 =====
FROM eclipse-temurin:17-jre-jammy AS runtime
LABEL org.opencontainers.image.title="z-graph-server" \
      org.opencontainers.image.description="z-graph 图数据库服务端(Bolt 4.4 + HTTP 控制面)" \
      org.opencontainers.image.source="https://github.com/z-opc-foundation/z-graph"

# 非 root 用户运行
RUN groupadd --system zgraph && useradd --system --gid zgraph --uid 10001 zgraph

# 数据持久化目录
RUN mkdir -p /var/lib/z-graph && chown -R zgraph:zgraph /var/lib/z-graph
VOLUME ["/var/lib/z-graph"]
WORKDIR /opt/z-graph

# 复制打包后的 jar 与依赖
COPY --from=build /workspace/z-graph-bolt-server/target/z-graph-bolt-server-*.jar /opt/z-graph/server.jar

USER zgraph

# 暴露 Bolt 4.4 与 HTTP 控制面端口
EXPOSE 7687 8090

ENV Z_GRAPH_BOLT_PORT=7687 \
    Z_GRAPH_HTTP_PORT=8090 \
    Z_GRAPH_DATA_DIR=/var/lib/z-graph \
    Z_GRAPH_CORS_ALLOWED_ORIGINS=*

# 健康检查 — 通过 HTTP /health
HEALTHCHECK --interval=15s --timeout=3s --start-period=15s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8090/health >/dev/null 2>&1 || exit 1

# 统一启动入口(Bolt + HTTP 控制面)
ENTRYPOINT ["java", \
    "-Dz.graph.dataDir=/var/lib/z-graph", \
    "-cp", "/opt/z-graph/server.jar", \
    "com.zifang.z.graph.bolt.ZGraphServer"]
