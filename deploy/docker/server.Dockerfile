# 多阶段构建:第一阶段用 Maven 编译并打包 z-graph 服务端 jar,
# 第二阶段用 JRE 镜像做运行时,只保留 jar 与运行时依赖。
# 适用于分布式部署:仅部署 z-graph-server,前端由 z-graph-frontend 镜像独立提供。

# ===== 构建阶段 =====
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# 先复制 pom 文件,让依赖层独立缓存
# 注意:每个模块的 <parent><relativePath> 已设为 ../pom.xml,
# 所以我们必须保持 src 目录布局原样,不要把所有 pom 都压平到同一目录。
COPY pom.xml /workspace/pom.xml
COPY z-graph-api/pom.xml             /workspace/z-graph-api/pom.xml
COPY z-graph-protocol/pom.xml        /workspace/z-graph-protocol/pom.xml
COPY z-graph-core/pom.xml            /workspace/z-graph-core/pom.xml
COPY z-graph-bolt-server/pom.xml     /workspace/z-graph-bolt-server/pom.xml
COPY z-graph-spring-boot-starter/pom.xml /workspace/z-graph-spring-boot-starter/pom.xml

# 安装 parent POM 到本地仓库,让后续步骤能找到父依赖
RUN mvn -B -ntp -f /workspace/pom.xml -N install -DskipTests

# 仅下载 bolt-server 与传递依赖,利用 Docker 缓存
RUN mvn -B -ntp -DskipTests -f /workspace/pom.xml -pl z-graph-bolt-server -am dependency:go-offline

# 复制源代码并打包
COPY . /workspace/
# 先 install 所有模块到本地仓库,dependency:copy-dependencies 才能解析兄弟模块
RUN mvn -B -ntp -DskipTests \
    -f /workspace/pom.xml \
    -pl z-graph-bolt-server -am install \
    -Dmaven.javadoc.skip=true -Dassembly.skipAssembly=true

# 把 bolt-server 与传递依赖 jar 拷到 staging 目录,运行时只依赖 JRE
RUN mvn -B -ntp -f /workspace/pom.xml \
    -pl z-graph-bolt-server dependency:copy-dependencies \
    -DoutputDirectory=/workspace/z-graph-bolt-server/target/lib \
    -DincludeScope=runtime

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
COPY --from=build /workspace/z-graph-bolt-server/target/lib/             /opt/z-graph/lib/

USER zgraph

# 暴露 Bolt 4.4 与 HTTP 控制面端口
EXPOSE 7687 8090

ENV Z_GRAPH_BOLT_PORT=7687 \
    Z_GRAPH_HTTP_PORT=8090 \
    Z_GRAPH_DATA_DIR=/var/lib/z-graph \
    Z_GRAPH_CORS_ALLOWED_ORIGINS=* \
    JAVA_OPTS=""

# 健康检查 — 通过 HTTP /health
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8090/health >/dev/null 2>&1 || exit 1

# 统一启动入口(Bolt + HTTP 控制面),支持 JAVA_OPTS 环境变量注入 JVM 参数
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dz.graph.dataDir=/var/lib/z-graph -cp '/opt/z-graph/lib/*:/opt/z-graph/server.jar' com.zifang.z.graph.bolt.ZGraphServer"]
