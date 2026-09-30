# z-graph

> 独立图数据库 —— MVCC 版本化图（Git 风格 commit / branch / merge）+ Nebula 风格 Tag/EdgeType schema
> + OpenCypher 子集 + Bolt 风格二进制协议 + HTTP 控制面 + React 控制台。Java 8 · Netty 4 · Spring Boot 2.7（仅 starter）

它要解决的问题：图数据的一次写入不该覆盖历史。`z-graph` 把每个 commit 存成**相对第一父的增量**
（`GraphDelta`）外加每条节点/边的版本链（`GraphEntityVersion`），因此写成本是 O(本次变更量) 而不是整图复制，
同时 `checkout(<commitId>)` 仍能给出任意历史时刻的完整视图。上层再挂一个 OpenCypher 子集解析器、
一个 Bolt 风格 socket 服务、一个 JDK `HttpServer` 控制面，就得到一个可以嵌进 JVM、也可以单容器跑 demo 的图库。

架构分层参考 NebulaGraph 的 Meta / Query / Storage 划分（对应 `GraphMetaService` / `GraphQueryService` /
`GraphVersionStore`+`InMemoryGraphStore`），协议消息面参考 Bolt 4.4 规范；**只采用公开架构与协议资料，
不复制上游代码**（详见文末「开源参考」）。

---

## 📋 基本信息

| 字段 | 值 |
|------|-----|
| **仓库** | `z-graph`（remote: `github.com/z-opc-foundation/z-graph`，分支 `main`） |
| **Maven 坐标** | `io.github.yuku123:z-graph`（聚合 POM）+ 5 个 reactor 子坐标 |
| **当前版本** | `1.0.8`（根 POM 与 5 个子 POM **逐字面写 1.0.8**，本仓没用 `${revision}`；发布形状由 flatten-maven-plugin 1.5.0 `oss` 模式自包含） |
| **父项目** | `io.github.yuku123:z-boot-parent:1.0.21`（`<relativePath/>` 留空，parent 在 repo1 不在磁盘） |
| **Maven Central** | **已发布**：`z-graph` / `-api` / `-protocol` / `-core` / `-bolt-server` / `-spring-boot-starter` 的 `1.0.8` pom 与 jar 均可从 repo1 取到（ranged GET 实测 206）；`1.0.7` 同样可读。⚠ POM 注释里写明 `1.0.6` 是半成品（api 已是 class-file 52、core/bolt-server 仍是 61），**该号作废不要用** |
| **默认端口** | Bolt `7687` · HTTP 控制面 `8090`（`ZGraphServer` 两个都起）；控制台 dev `5173` / 容器 `3333` / all-in-one `3000` |
| **运行口径** | Java 8（class-file 52）· Spring Boot 2.7.18（只在 `z-graph-spring-boot-starter`）· Netty 4.1.138.Final |
| **最近更新** | 2026-09-30 |

---

## 🎯 能力清单（逐条对应到代码）

| 能力 | 实现位置 | 说明 |
|------|----------|------|
| 节点 / 边 CRUD、多标签节点 | `z-graph-api` `GraphStore` · `InMemoryGraphStore.addNode(long, Collection<String>, Map)` · `Node.getLabels()` | 边带类型与属性；`getOutEdges` / `getInEdges` / `getEdgesByType` |
| Tag / EdgeType schema | `TagSchema` / `EdgeTypeSchema` + `CypherEngine` 的 `CREATE TAG` / `CREATE EDGE [TYPE]` / `DROP TAG` / `DROP EDGE` / `ALTER TAG` / `ALTER EDGE` / `REBUILD` | Nebula 风格声明式属性、类型与 NOT NULL 校验；覆盖前按旧 schema 校验存量数据 |
| 属性索引 + 索引下推（**窄口径**） | `InMemoryGraphStore.createPropertyIndex` · `CypherEngine#tryIndexSeed` | 只有模式恰为 `(v:Label)`、WHERE 恰为 `v.prop = 字面量`、且 `(Label, prop)` 索引存在时才替换全表扫描；其余仍走标签扫描 |
| OpenCypher 子集 | `CypherEngine`（2255 行，手写解析）+ `CypherExecutor`（`RETURN` 字面量旧路径） | 见下方「Cypher 支持矩阵」 |
| 变长路径 | `CypherEngine` 的 `(a)-[:TYPE*min..max]->(b)` | 反向 `<-[]->`、无方向 `-[]-`、类型过滤均支持 |
| N 跳邻居 | `GraphStore.traverse(startNodeId, maxDepth, edgeType)` | 返回可达节点集合 |
| MVCC 版本化图 | `GraphVersionStore`：`beginWrite` / `commit` / `checkout` / `checkoutBranch` / `createBranch` / `log` / `merge` / `nodeVersions` / `nodeVersionAt` / `garbageCollect` / `versionStats` / `reachableCommitCount` | 写事务在 `VersionOverlayStore` 覆盖层里累积增量；提交后把该覆盖层认领为新 commit 的视图 |
| 并发控制 | `GraphVersionStore.StaleHeadException`（提交时 base head 已被推进） | 控制面对应返回 `409`，Bolt 侧 `RUN` 写路径重试一次 |
| 三方合并与冲突 | `GraphVersionStore.merge(target, source, author, message)` → `GraphMergeResult`（`isMerged` / `getCommit` / `getConflicts` / `hasConflicts`） | 只自动合并单侧变化；同字段双侧改动进冲突列表且**不移动** target head |
| 文件持久化 | `GraphVersionStore(Path storageDirectory)`：`objects/<commitId>.bin` + `repository.bin` | `GraphCodec.STORAGE_VERSION=4`（按 commit 存增量），`LEGACY_STORAGE_VERSION=2` 的旧文件仍可读；重启可恢复提交图、版本链与历史视图 |
| 视图缓存调参 | `withCheckpointInterval` / `withMaxRetainedViews` / `withRetainedWholeGraphViews` / `withViewLayerLimit` / `withEagerCheckpoints` | 默认常量 32 / 64 / 4 份整图 / 8 层，都是 `GraphVersionStore` 的 public 常量 |
| Bolt 风格协议服务 | `BoltServer`（Netty pipeline）+ `z-graph-protocol`（`BoltConstants` / `BoltFrames` / `BoltMessageDecoder`）+ `BoltMessageHandler` | 消息面见下方「Bolt 消息矩阵」 |
| HTTP 控制面 | `GraphControlServer`（JDK `com.sun.net.httpserver`，13 个 context） | 认证 / 限流 / 安全响应头 / CORS / GZIP / 请求日志 / 指标 |
| Spring Boot 自动装配 | `ZGraphAutoConfiguration` + `ZGraphProperties`（前缀 `z.graph`，`AutoConfiguration.imports` 已注册） | `z.graph.enabled` **默认 false**；装的是 `GraphVersionStore` + 内嵌 HTTP 控制面，**不起 BoltServer** |
| 可视化控制台 | `z-graph-console`（React 18 + Vite 5，`src/App.jsx` 8 个 tab） | 无 AntD 依赖，样式是自写的 `src/styles.css` |
| 观测 | `/meta/metrics`（JSON）、`/meta/logs`（500 条环形缓冲）、`X-Request-ID` 复用/生成 | Prometheus 文本格式**未实现**，别按 exposition 接 |

### Cypher 支持矩阵（读源码得出现状）

| 类别 | 已实现 | 边界 |
|------|--------|------|
| 读写 | `MATCH` / `OPTIONAL MATCH` / `CREATE` / `MERGE`（upsert）/ `DELETE` / `DETACH DELETE` / `SET` / `REMOVE` | — |
| 过滤 | `WHERE`：`AND` / `OR` / `NOT` / `IN` / `CONTAINS` / `STARTS WITH` / `ENDS WITH` / `IS [NOT] NULL` / `EXISTS` / `NOT EXISTS` | 复杂嵌套谓词按子集解析 |
| 返回 | `RETURN` / `DISTINCT` / `WITH`（投影 + `WITH ... ORDER BY`）/ `ORDER BY` / `SKIP` / `LIMIT` | `WITH` 里带聚合会抛 `CypherException` |
| 聚合 | `count` / `sum` / `avg` / `min` / `max`（自动分组） | **`collect()` 未实现**（`parseAggregate` 正则只收这 5 个函数） |
| 路径 | 变长 `(a)-[:TYPE*min..max]->(b)`、任意方向、类型过滤 | **`shortestPath()` / `allShortestPaths()` 全仓 0 处实现** |
| 其他 | `UNWIND [..] AS x` / 多语句 `;` / 参数绑定（`$name`，走 `CypherEngine.execute(cypher, parameters)`） | `UNWIND` 只接字面量列表 |
| 元数据 | `SHOW TAGS` / `SHOW EDGES` / `SHOW INDEXES` / `SHOW TAG <n>` / `SHOW EDGE <n>` / `SHOW STATS` / `DESCRIBE TAG\|EDGE` | `SHOW STATS` 要求底层是 `InMemoryGraphStore` |
| 内置过程 | `CALL db.version` / `db.stats` / `db.tags` / `db.edges` / `db.indexes` / `db.branches` / `db.commits` / `db.head` | 后三类需要引擎绑到 `GraphVersionStore`，纯 `InMemoryGraphStore` 调用会抛错 |
| DDL | `CREATE TAG` / `CREATE EDGE [TYPE]` / `CREATE TAG INDEX` / `CREATE EDGE INDEX` / `DROP TAG\|EDGE\|INDEX` / `ALTER TAG\|EDGE` / `REBUILD` / `EXPLAIN` | — |

### Bolt 消息矩阵

`BoltMessageHandler` 分发的消息签名：

| 请求 | 响应 | 备注 |
|------|------|------|
| `HELLO` (0x01) | `SUCCESS` | 回 `connection_id` / `server=z-graph/1.0.0-SNAPSHOT` / `edition=community`；**不校验 scheme/credentials** |
| `RUN` (0x10) | `SUCCESS`(qid, fields, t_first) | 读查询绑定当前 head 快照；写查询自行开事务并 commit 一次（冲突重试一次） |
| `PULL` (0x3F) | `SUCCESS` + `RECORD`*N + `SUCCESS`(has_more) | 用普通 `SUCCESS` 代替 `PULL_SUCCESS`，POC 简化 |
| `BEGIN` / `COMMIT` / `ROLLBACK` | `SUCCESS` / `FAILURE` | 连接级事务落在 `main` 分支的 `GraphWriteTransaction` |
| `DISCARD` (0x2F) / `RESET` (0x0F) / `GOODBYE` (0x02) | `SUCCESS` / 关连接 | `RESET` 会回滚事务并清空该连接所有流 |
| 其他 | `FAILURE`（`code=z-graph.POC.Failure`） | — |

**协议边界（重要）**：`z-graph-protocol` 的帧头是工程自定义的 `2 字节长度 + 2 字节 chunk 标记`，
值编码走 PackStream 子集（NULL / BOOL / INT_8..64 / STRING_8..32 / LIST_8..32 / MAP_8..16 / STRUCT_8，
TINY_* 变体只在读侧识别）；建连前的 4 字节 magic `0x6060B007` 与版本协商段**没有任何处理代码**
（全仓 grep 无 magic / handshake），`ROUTE`、`ACKS_REQUIRED`、多 chunk 分片、大 chunk 头 `0xFF…` 也都没实现。
所以**不能宣称"Neo4j 官方 Java/Python/Go/JS Driver 或 Neo4j Browser 可直连 bolt://"**：仓内 E2E 用的是自写的
`z-graph-bolt-server/src/test/java/com/zifang/z/graph/bolt/BoltTestClient.java`，`_doc/003_script/` 里的
Python 驱动同样是手写帧。要接官方 driver，得先补 handshake 与版本协商。

---

## 🏗️ 项目结构

```
z-graph/
├── pom.xml                        # 聚合 POM：parent z-boot-parent:1.0.21，6 条自家坐标 DM，flatten 常开
├── z-graph-api/                   # 抽象与值类型：GraphStore / Node / Edge / GraphCommit / GraphMergeResult
│                                  #   / TagSchema / EdgeTypeSchema / Colls（Java 8 没有 Map.of 的替代品）
├── z-graph-protocol/              # Bolt 风格帧与值编解码：BoltConstants / BoltFrames / BoltMessageDecoder
├── z-graph-core/                  # 引擎：CypherEngine / InMemoryGraphStore / GraphVersionStore(MVCC)
│                                  #   / VersionOverlayStore / GraphDelta / GraphEntityVersion / GraphCodec
│                                  #   / GraphWriteTransaction / GraphCheckout / GraphQueryService / GraphMetaService
│                                  #   / ReadOnlyGraphStore / CypherExecutor
├── z-graph-bolt-server/           # 服务端：BoltServer(Netty) + BoltMessageHandler
│                                  #   + GraphControlServer(JDK HttpServer，13 端点)
│                                  #   + 三个 main：ZGraphServer / BoltServer / GraphControlServerMain
├── z-graph-spring-boot-starter/   # @AutoConfiguration + z.graph.* 配置（默认 enabled=false）
├── z-graph-console/               # React 18 + Vite 5 控制台（npm 工程，不在 Maven reactor）
├── deploy/
│   ├── docker/                    # server.Dockerfile / frontend.Dockerfile / all-in-one.Dockerfile / docker-compose.yml
│   ├── kubernetes/z-graph.yaml    # Namespace + ConfigMap + PVC + 2 Deployment + 2 Service
│   └── nginx/                     # frontend.conf（容器，envsubst upstream）/ frontend-local.conf（本机 3333）
├── poc/                           # 目前为空目录，且未被 git 跟踪
│                                  #   —— 历史 Bolt 实验脚本已收口到 _doc/003_script/
├── _doc/                          # 文档收口，见文末「文档目录」
└── .github/workflows/             # java-tests.yml（mvn -B -ntp verify）+ build-images.yml（三镜像推 GHCR）
```

Maven reactor 只有 **5 个模块**（`z-graph-api` / `z-graph-protocol` / `z-graph-core` / `z-graph-bolt-server` /
`z-graph-spring-boot-starter`）；`z-graph-console` 是独立 npm 工程（`package.json` 里 `private: true`），
`poc/` 不参与构建。5 个子 POM **都没有** `maven.deploy.skip`，全部参与发布；1.0.8 实测中央可读。

---

## 🔧 技术栈

| 层级 | 技术（实测版本） |
|------|------------------|
| 语言 / 运行时 | Java 8（`z-boot-parent` 下发 source/target 8 ⇒ class-file 52；1.0.7 起全仓降档） |
| 二进制协议 | Netty 4.1.138.Final（`netty-all`，版本由地板 `z-boot-dependencies` 的 netty-bom 供给） |
| HTTP 控制面 | JDK `com.sun.net.httpserver.HttpServer`（**不是** Spring MVC / WebFlux） |
| Spring 集成 | `spring-boot-autoconfigure:2.7.18`（仅 starter 模块依赖它，web 层不在依赖里） |
| 日志 | `log4j-api:2.25.4` + 运行期 `slf4j-simple:2.0.13`（bolt-server） |
| 有意的版本分歧 | `slf4j-api` 2.0.13、`junit` 5.10.2 / `junit-platform` 1.10.2、`objenesis` 3.2 —— 根 POM 按坐标写**直接**条目顶住地板下压，注释里逐条写了原因 |
| 测试 | JUnit 5（Jupiter）；172 个 `@Test`，分布在 20 个测试类 |
| 前端 | React 18.3.1 + ReactDOM + Vite 5.4.10；自写 CSS，**无 AntD / 无组件库** |
| 构建 / 发布 | Maven（flatten-maven-plugin 1.5.0 `oss` + `updatePomFile`）；`central` profile 走 central-publishing-maven-plugin 0.8.0 |
| 部署 | Docker / docker-compose profiles / k8s 清单 / nginx；GHCR 由 `build-images.yml` 构建推送 |

---

## 🚀 快速开始

### 编译

```bash
git clone https://github.com/z-opc-foundation/z-graph.git
cd z-graph
mvn clean install -DskipTests
```

第三方版本一律由 `z-boot-parent:1.0.21` → `z-boot-dependencies`（地板）+ `z-boot-fleet`（兄弟仓权威表）供给，
模块 POM 里不该再出现字面版本钉。构建解析不到 `io.github.yuku123:z-boot-parent:1.0.21` 时先确认能连 repo1。
注意 flatten 绑在 `process-resources`、`flatten.clean` 绑在 `clean`，所以判定"跑过"要以带 `clean` 的构建为准。

### 起一个进程（Bolt + HTTP 控制面同时起）

```bash
# 方式 A：Maven exec（_doc/003_script/run_t1_verify.sh 就是这么起的）
mvn -B -pl z-graph-bolt-server exec:java \
  -Dexec.mainClass=com.zifang.z.graph.bolt.ZGraphServer \
  -Dexec.args="7687 8090" \
  -Dexec.cleanupDaemonThreads=false

# 方式 B：Docker 运行时同款 classpath（server.Dockerfile 的 ENTRYPOINT）
java $JAVA_OPTS -Dz.graph.dataDir=/tmp/z-graph-data \
  -cp 'z-graph-bolt-server/target/lib/*:z-graph-bolt-server/target/classes' \
  com.zifang.z.graph.bolt.ZGraphServer

curl http://localhost:8090/health
# {"status":"UP","head":"<commitId>","nodeCount":0,"edgeCount":0}
```

端口优先级：命令行参数 > 环境变量 `Z_GRAPH_BOLT_PORT` / `Z_GRAPH_HTTP_PORT` > 系统属性
`z.graph.boltPort` / `z.graph.httpPort` > 默认 7687 / 8090。只跑控制面就换
`-Dexec.mainClass=...GraphControlServerMain`，只跑 Bolt 就用 `...BoltServer`（默认 7687）。
不指定数据目录时全在内存，进程退出即丢。

### 嵌入式（Java 8 可编译的真实 API）

```java
import com.zifang.z.graph.api.*;
import com.zifang.z.graph.core.*;
import java.util.*;

GraphVersionStore repo = new GraphVersionStore();        // 纯内存；new GraphVersionStore(Paths.get(dir)) 落盘
GraphWriteTransaction tx = repo.beginWrite("main");      // 构造时已建好 main 分支的 root commit
Node alice = tx.addNode("Person",  Colls.mapOf("name", "Alice", "age", 30));
Node acme  = tx.addNode("Company", Colls.mapOf("name", "Acme Corp"));
tx.addEdge("WORKS_AT", alice.getId(), acme.getId(), Colls.mapOf("role", "Engineer"));
GraphCommit base = tx.commit("alice", "add Alice + Acme");   // 提交即推进 head

// 多标签：InMemoryGraphStore 上有 addNode(id, Collection<String> labels, props)，Node#getLabels() 返回 Set
// Cypher 走 CypherEngine —— GraphStore 接口本身没有 executeCypher 方法
List<Map<String, Object>> rows = new CypherEngine(repo.checkoutBranch("main").getStore(), repo)
        .execute("MATCH (p:Person)-[:WORKS_AT]->(c:Company) RETURN p.name AS person, c.name AS company");
```

> 属性字面量用 `Colls.mapOf(k1,v1,...)`（最多 5 对）与 `Colls.listOf(...)`：本仓口径是 Java 8，
> `Map.of` / `List.of` 与文本块 `"""` 都编译不过。

### 分支 / 合并 / 历史读取

```java
repo.createBranch("feature/promote-alice", base.getId());     // 第二个参数是 commit ID，返回该 base GraphCommit
GraphWriteTransaction featureTx = repo.beginWrite("feature/promote-alice");
featureTx.updateNode(alice.getId(), Colls.mapOf("role", "senior"));
featureTx.commit("alice", "promote alice to senior");

GraphMergeResult merge = repo.merge("main", "feature/promote-alice", "alice", "merge promote");
if (merge.isMerged()) {
    String newHead = merge.getCommit().getId();               // 未移动 head（含纯冲突情形）时 getCommit() 可为 null
} else {
    List<String> conflicts = merge.getConflicts();            // 同字段双侧改动 → 人工处理，head 不动
}

List<GraphCommit> history = repo.log("main");                 // ref 可以是分支名或 commit ID（沿第一父链）
List<Map<String, Object>> asOf = repo.checkout(base.getId())
        .query("MATCH (n:Person) RETURN n.name AS name");
```

审计场景按 `checkout(commitId)` 读历史快照即可，不需要为时间点复制整图；`nodeVersions(id)` /
`nodeVersionAt(id, ref)` 给单节点多版本链，`garbageCollect(keepBranches...)` 回收不可达 commit，
`exportSnapshot(commitId, target)` / `importSnapshot(...)` 做仓库级搬运。

### 控制台

```bash
cd z-graph-console
npm install && npm run dev        # http://localhost:5173，/api 由 Vite 代理到 8090
npm run build                     # 产物 dist/；npm run preview 走 4173
```

代理目标用 `VITE_API_PROXY_TARGET` 覆盖（默认 `http://127.0.0.1:8090`）；也可用 `VITE_API_BASE`
或页面注入 `window.__Z_GRAPH_API__` 改成绝对地址。8 个 tab：总览、图视图、分支、提交历史、Cypher 查询、
Schema、请求日志、API 文档（`src/App.jsx` 的 `TABS`，页面实现在 `src/pages/`）。

---

## 🔌 HTTP API

`GraphControlServer` 注册 **13 个 context**（控制台的 `src/pages/ApiDocs.jsx` 文档化了其中 12 个业务端点，
不含 `/options`）：

| 路径 | 方法 | 说明 |
|------|------|------|
| `/health` | GET | `status` / `head` / `nodeCount` / `edgeCount`；**认证与限流均豁免** |
| `/query` | GET / POST | `?cypher=&branch=&commit=` 或同名字段的 JSON body；返回**裸数组**，耗时写 `X-Response-Time` |
| `/query/batch` | POST | 多条语句顺序执行，逐条回 `statementIndex` + `elapsedMs`；非 POST → 405 |
| `/query/explain` | POST | 查询计划（读写判定、是否可走索引、是否含变长路径）；不执行写操作；非 POST → 405 |
| `/meta/branches` | GET | 分支名列表 |
| `/meta/commits` | GET | commit 列表（id / parents / branch / author / message / node / edge 计数） |
| `/meta/schema` | GET | `?branch=main`，内部用 `SHOW TAGS` / `SHOW EDGES` |
| `/meta/stats` | GET | `?branch=main`，内部 `CALL db.stats()` |
| `/meta/metrics` | GET | JSON：uptimeMs / uptimeSeconds / uptimeFormatted / totalRequests / errorResponses / errorRate / rateLimitedRequests / authFailures / apiTokenEnabled / rateLimitPerMinute / activeRateBuckets / jvmMemory / availableProcessors |
| `/meta/logs` | GET | 环形缓冲最多 500 条；`?limit&offset&method&status=4xx\|5xx\|2xx\|精确码&path&requestId` |
| `/meta/export` | GET | `?branch=` 导出节点 + 按类型取的边 + schema |
| `/meta/import` | POST | body 的 `nodes` / `edges` 段以 Cypher `CREATE` 落地；非 POST → 405 |
| `/options` | OPTIONS | 204 + CORS 头；其他方法 405 |

统一行为：安全响应头 `X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、
`X-XSS-Protection: 1; mode=block`、`Referrer-Policy: strict-origin-when-cross-origin`；
`X-Request-ID` 缺省生成 16 字符、客户端传入则复用；`Accept-Encoding: gzip` 且响应 >256 字节才压缩；
CORS 白名单命中时回显具体 origin 并带 `Vary: Origin`，`*` 时直接 `*`。

状态码如实分类（1.0.3 的修正）：分支 / commit 不存在 → `404`，参数错 → `400`，写冲突 → `409`，
限流 → `429` + `Retry-After: 60`，未授权 → `401`，其余才 `500`；判据是异常类型而非消息文本。

注意 `/query` **不做参数绑定**（body 只按字符串解析，执行时传空参数表）；`$name` 绑定要在
`CypherEngine.execute(cypher, parameters)` 或 Bolt `RUN` 那一层用。

---

## ⚙️ 配置与安全

### 进程 / 控制面环境变量

| 环境变量 | 默认 | 说明 |
|----------|------|------|
| `Z_GRAPH_BOLT_PORT` | `7687` | Bolt 端口（首个命令行参数可覆盖） |
| `Z_GRAPH_HTTP_PORT` | `8090` | 控制面端口（第二个命令行参数可覆盖） |
| `Z_GRAPH_DATA_DIR` | 未设 = 纯内存 | 版本仓库目录；容器里默认 `/var/lib/z-graph` |
| `Z_GRAPH_API_TOKEN` | 未设 = 不鉴权 | 设置后启用 Bearer 校验（`Authorization: Bearer <token>` 或 `?token=`）。**值一律由部署侧注入，禁止写进 yml / 镜像 / 文档** |
| `Z_GRAPH_RATE_LIMIT` | `0`（不限） | 每 IP 每分钟请求上限，1 分钟窗口，超限返回 429 |
| `Z_GRAPH_CORS_ALLOWED_ORIGINS` | `*` | 逗号分隔 origin；系统属性 `z.graph.cors.allowedOrigins` 优先级更高 |
| `JAVA_OPTS` | 空 | 容器 ENTRYPOINT 透传给 `java` |
| `Z_GRAPH_API_UPSTREAM` | `z-graph-server:8090` | 前端镜像 nginx upstream（`deploy/nginx/frontend.conf` 里 envsubst） |

对应系统属性：`z.graph.boltPort` / `z.graph.httpPort` / `z.graph.dataDir` / `z.graph.cors.allowedOrigins`。

### Spring Boot starter

```yaml
z:
  graph:
    enabled: false                 # 默认 false：不装任何 Bean、不碰磁盘和网络
    port: 8090                     # 0 = 让内核分配，此时必须读 graphControlServer.port()
    data-dir:                      # 留空 = 内存仓库，不落盘、不建目录
    checkpoint-interval: 32
    max-retained-views: 64
    retained-whole-graph-views: 4
    view-layer-limit: 8
```

这 7 个键就是 `ZGraphProperties` 的全部字段。**没有** `mode`、`host`、`bolt-port`、`rest-port`、
`persistence.enabled`、`persistence.data-dir`、`cache.max-size`、`cache.ttl-seconds` 这些键
（旧 README 的 yml 样例是模板猜的：控制面 bind 通配地址所以没有 host，starter 也压根不起 Bolt）。
`GraphControlServer` 在构造函数里就 bind，端口被占则容器启动直接失败；Bean 配 `destroyMethod = "stop"`。

### 优雅关闭

`GraphControlServer.start()` 注册 shutdown hook，SIGTERM/SIGINT 时 `server.stop(5)`（最多等 5 秒）；
`ZGraphServer` 的 hook 同时 `bolt.shutdown()`（Netty `shutdownGracefully()`）+ `control.stop()`（`stop(0)`，
用于测试里的即时释放）。

---

## 🧪 测试

```bash
mvn test                     # 172 个 @Test / 20 个测试类，不需要外部依赖
mvn -pl z-graph-core test    # 只跑引擎：138 个用例
```

分布：`z-graph-core` 13 个测试类共 138 个用例（`MvccVersioningTest` 17、`CypherEngineTest` 15、
`ExplainAndDescribeTest` 13、`DdlAndPathTest` 13、`ShowAndAggregationTest` 12、`SchemaManagementTest` 12、
`IndexOptimizerAndAlterTest` 12、`InMemoryGraphStoreTest` 12、`CypherExecutorTest` 11、
`OrderLimitWithAndSnapshotTest` 9、`GraphVersionStoreTest` 9、`VersionedSchemaTest` 3，另有无用例的
`MvccStressHarness`）；`z-graph-bolt-server` 28 个（`BoltFramesTest` 14、`BoltServerE2ETest` 11、
`GraphControlServerTest` 2、`ZGraphServerTest` 1；`BoltTestClient` 是测试客户端不是用例）；
`z-graph-spring-boot-starter` 6 个。`z-graph-api` / `z-graph-protocol` 没有自己的测试源码目录，
协议编解码用例放在 bolt-server 模块下。

MVCC 压力/性能台是**带 verdict 的门禁**，不是报告生成器（任一场景判红即非 0 退出，可当 CI 卡口）：

```bash
java -Xms1g -Xmx8g -cp target/classes:target/test-classes \
  com.zifang.z.graph.bench.MvccStressHarness --profile=full \
  --workdir=/tmp/zgraph-stress --json=/tmp/zgraph-stress/results.jsonl
```

Bolt 端到端（需要本机 `python3`，并且要先起服务；`neo4j` driver 那一支还需要 `pip install neo4j`）：

```bash
python3 _doc/003_script/test_bolt_raw.py            # HELLO → RUN(RETURN 1 AS n) → PULL 最小往返
python3 _doc/003_script/test_bolt_full.py           # 7 类 RETURN 字面量场景，每场景独立 TCP 连接验幂等
python3 _doc/003_script/test_bolt_error.py          # 错误路径：未知签名 / 非法 qid / DISCARD / RESET / GOODBYE
NUM_CLIENTS=100 QUERIES_PER_CLIENT=5 python3 _doc/003_script/test_bolt_concurrent.py
```

驱动脚本读 `Z_GRAPH_HOST` / `Z_GRAPH_PORT`（默认 `localhost:7687`）。

⚠ 两个编排脚本目前跑不通，是**脚本债不是代码债**：`run_e2e.sh` 把 `cd "$(dirname "$0")/.."` 当仓库根、
`run_t1_verify.sh` 把 `$(dirname "$0")/.."` 当仓库根并拼 `$Z_GRAPH_DIR/poc/test_bolt_*.py` —— 两者都还假设自己
躺在根 `poc/` 下，而脚本现在住在 `_doc/003_script/`，于是工作目录落到 `_doc`、驱动路径也指空。修好之前请按上面的
启动命令起服务，再直跑 `python3 _doc/003_script/test_bolt_*.py`。

`_doc/003_script/test_bolt_poc.py` 走官方 `neo4j` driver 连 `bolt://localhost:7687`；按上面的协议边界
（服务端不处理 magic 与版本协商），这一支现在**预期失败**，它保留的是"官方 driver 接不进来"这个待办，
别把它当兼容性证明。

---

## 🐳 部署

三个 Dockerfile（都在 `deploy/docker/`，多阶段构建，build context 是仓库根）：

| 文件 | 产物 | 运行时 |
|------|------|--------|
| [`server.Dockerfile`](deploy/docker/server.Dockerfile) | `z-graph-server`：bolt-server jar + `target/lib/` 依赖 | `eclipse-temurin:17-jre-jammy`，非 root（uid 10001），`EXPOSE 7687 8090`，`HEALTHCHECK` 打 `/health`，ENTRYPOINT 跑 `ZGraphServer` |
| [`frontend.Dockerfile`](deploy/docker/frontend.Dockerfile) | `z-graph-frontend`：nginx + 控制台静态资源 + `/api` 反代 | `node:20-alpine` 构建 → `nginx:1.27-alpine`，模板 `deploy/nginx/frontend.conf` 经 envsubst 注入 `${Z_GRAPH_API_UPSTREAM}` |
| [`all-in-one.Dockerfile`](deploy/docker/all-in-one.Dockerfile) | `z-graph-all-in-one`：Java 服务 + nginx 同容器 | 入口脚本 [`_doc/003_script/all-in-one-entrypoint.sh`](_doc/003_script/all-in-one-entrypoint.sh)（拷前端产物并并行拉起两个进程） |

> 基础镜像是 JDK 17 运行时，而**字节码口径是 Java 8**（class-file 52），两者不矛盾：产物跑在 8 与 17 上都行。

```bash
# 分布式：server 无 profile 恒启，frontend 属于 distributed / frontend profile
docker compose -f deploy/docker/docker-compose.yml --profile distributed up -d
# 单容器 demo：控制台 http://localhost:3000，同时暴露 7687 / 8090
docker compose -f deploy/docker/docker-compose.yml --profile all-in-one up -d
# 多副本拓扑示例（node-2 → 7688/8091，node-3 → 7689/8092；只是并列实例，没有集群协商）
docker compose -f deploy/docker/docker-compose.yml --profile cluster up -d
```

compose 实测：项目名 `z-graph`；镜像名 `ghcr.io/z-opc-foundation/z-graph-{server,frontend,all-in-one}:${Z_GRAPH_IMAGE_TAG:-latest}`；
server 限 `2.0 CPU / 1024M`、frontend `0.5 CPU / 128M`；日志 `json-file 10m × 3`；
`Z_GRAPH_DATA_DIR=/var/lib/z-graph` 挂 named volume；`JAVA_OPTS` 带
`-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:MaxGCPauseMillis=200`；
frontend `depends_on: z-graph-server (service_healthy)`；健康检查 10s 间隔 / 15s 启动宽限。

k8s 清单 [`deploy/kubernetes/z-graph.yaml`](deploy/kubernetes/z-graph.yaml)：`Namespace z-graph` +
`ConfigMap z-graph-config` + `PersistentVolumeClaim z-graph-data` + `Deployment z-graph-server`（replicas 1，
容器端口 bolt 7687 / http 8090）+ `Service z-graph-server` + `Deployment z-graph-frontend`（replicas 2，
`Z_GRAPH_API_UPSTREAM` 指向 server）+ `Service z-graph-frontend`。

```bash
kubectl apply -f deploy/kubernetes/z-graph.yaml
kubectl -n z-graph port-forward svc/z-graph-frontend 8080:80
```

本机只想给控制台接一个已起的 8090，用 [`deploy/nginx/frontend-local.conf`](deploy/nginx/frontend-local.conf)
（监听 3333，`/api/` → `127.0.0.1:8090`，nginx 侧同样开了 gzip + `/assets/` 长缓存 + `index.html` no-cache）。

CI：[`.github/workflows/java-tests.yml`](.github/workflows/java-tests.yml)（push / PR 跑 `mvn -B -ntp verify`，
上传 surefire 报告）、[`.github/workflows/build-images.yml`](.github/workflows/build-images.yml)
（先 `mvn -B -ntp test` 再并行构建三个镜像；push 到 `ghcr.io/<repository_owner>/z-graph-*`，
tag 为 `latest` 或 tag 名或 `pr-<n>`，另附 `github.sha` tag；PR 只构建不推送）。
除 Maven Central 那 6 个坐标是实测可读之外，GHCR 某 tag 此刻能否拉取请按 workflow 运行记录现查，README 不作承诺。

---

## 📈 性能：只留量得出的数

历史 T1 报告（[`_doc/001_arch/TEST_REPORT.md`](_doc/001_arch/TEST_REPORT.md)，2026-08-31，Bolt POC 路径）：
100 连接 × 5 查询 = 500/500 通过，**QPS ≈ 5278**；补测 200 客户端 × 10 查询时约 1000 条在 0.19s 完成，
之后开始队列堆积（Netty NIO 默认 IO 线程 ≈ 核数 × 2）。同批修复了 `nextQid` 非线程安全与测试硬编码 qid
两个并发 bug（后者会让第二次 PULL 找不到流、客户端卡死）。

视图缓存默认档在 1.0.4 量出过反例（记在 `GraphVersionStore` 注释里）：20 万节点的图按实体计费 300,299，
而当时预算 200,000 —— 淘汰退化成"只留各分支 head 那份豁免视图"，首读 656ms、复读 650ms，复读相对首读零收益。
1.0.5 起预算改按**整图份数**换算（`DEFAULT_RETAINED_WHOLE_GRAPH_VIEWS = 4`，20 万节点约当 120 万实体），
对图规模自适应：图越大留的视图越少。

旧 README 里两张表**已从本文删除**："100 万节点 / 500 万边：单节点 add 80,000 QPS、shortestPath 3,500 QPS、
版本化 commit 1,200 QPS"，以及"macOS 实测健康检查 5.2ms / 查询 6.0ms / 进程内存 73MB / 单实例 10,000 req/s"。
仓内找不到产生这些数字的可复现用例（其中 `shortestPath` 根本没有实现），留着就是让下一个读者拿它当承诺。
要基准就现跑上面的 `MvccStressHarness`，它每条场景都带可判红的 verdict。

---

## 🩺 故障排查

| 现象 | 判法 |
|------|------|
| `Address already in use: 7687` | `lsof -i :7687`；或改 `Z_GRAPH_BOLT_PORT` / 传首个命令行参数 |
| starter 起不来 / 端口被占 | `GraphControlServer` 构造即 bind，异常直接冒到容器启动；先确认 8090 上是不是已有实例 |
| 写请求返回 409 | `StaleHeadException`：base head 已被别的连接推进；控制面重读 head 后重试，Bolt 侧 `RUN` 写路径已自动重试一次 |
| 历史读很慢 | 看 `repo.versionStats()`；套叠层数超 `view-layer-limit` 或检查点过深会回放物化，必要时调小 `checkpoint-interval` |
| 官方 Neo4j driver 连不上 | 预期行为：服务端不处理 magic + 版本协商段；请用手写帧驱动，或先补 handshake |
| 前端 404 / 接口全红 | `docker logs z-graph-frontend`；`curl http://localhost:3333/api/health` 验反代（nginx `/api/` 会剥掉前缀） |
| CORS 收紧无效 | 系统属性 `z.graph.cors.allowedOrigins` 优先于 `Z_GRAPH_CORS_ALLOWED_ORIGINS`，两处都设时后者被盖掉 |
| 想看请求轨迹 | `curl 'http://localhost:8090/meta/logs?limit=20&status=4xx'`，或按 `requestId=` 精确捞（环形缓冲只保最近 500 条） |

---

## 📄 License

MIT，见根 [`LICENSE`](LICENSE)（首行即 "MIT License"，版权方 2026 z-opc-foundation）与根 POM 的
`<license>MIT License`。旧 README 结尾写的 "Apache License 2.0" 与文件不符，已纠正。

## 🔗 开源参考

架构分层（Meta / Query / Storage）与 Bolt 消息语义参考 [NebulaGraph](https://github.com/vesoft-inc/nebula)
与 [Bolt 协议规范](https://neo4j.com/docs/bolt/current/bolt-protocol/)；本工程只采用公开架构思想与协议资料，
不复制上游代码，并保留上游链接与许可证边界。

_Maintained by the z-opc-foundation organization._

---

## 文档目录

本项目文档统一收口在 `_doc/` 下：

- [`_doc/001_arch/`](_doc/001_arch/) — 架构与测试记录：
  - [`TEST_REPORT.md`](_doc/001_arch/TEST_REPORT.md) — T1 阶段（2026-08-31）Bolt POC 端到端报告：单元 25/25、
    正常路径 10/10、错误路径 9/9、并发 500/500 与 QPS 5278 的原始输出，后半节补记版本化图增量验证
    （`GraphVersionStore` 9/9、`CypherEngine` 15/15、`InMemoryGraphStore` 12/12、Bolt 事务 E2E）。
    ⚠ 它是**历史快照**：里面的 `poc/` 路径、"BEGIN 不支持只回 IGNORED"、"仅 RETURN 字面量"等结论
    已被后续实现推翻，其中 Java 源码行数表对应的还是 Java 17 时代的骨架。

- [`_doc/002_deploy/`](_doc/002_deploy/) — 目前为空目录（部署资产实际躺在根 `deploy/`，见「部署」一节）

- [`_doc/003_script/`](_doc/003_script/) — Bolt 协议测试驱动与运维脚本（原 `poc/` 实验收口于此）：
  - [`test_bolt_raw.py`](_doc/003_script/test_bolt_raw.py) — 手写帧最小往返：HELLO → RUN(`RETURN 1 AS n`) → PULL
  - [`test_bolt_full.py`](_doc/003_script/test_bolt_full.py) — 7 类 `RETURN` 字面量子场景，每场景独立 TCP 连接
  - [`test_bolt_error.py`](_doc/003_script/test_bolt_error.py) — 错误路径：未知签名、非法 qid、DISCARD、RESET、GOODBYE
  - [`test_bolt_concurrent.py`](_doc/003_script/test_bolt_concurrent.py) — 并发压力（`NUM_CLIENTS` / `QUERIES_PER_CLIENT`）
  - [`test_bolt_poc.py`](_doc/003_script/test_bolt_poc.py) — 官方 `neo4j` Python driver 版用例（现预期失败，见「测试」）
  - [`run_e2e.sh`](_doc/003_script/run_e2e.sh) — 编译 + 启 `BoltServer` + 跑 `test_bolt_poc.py` 的编排（路径假设仍是 `poc/`）
  - [`run_t1_verify.sh`](_doc/003_script/run_t1_verify.sh) — T1 全流程：环境检查 → `mvn test` → 启服务 → 三类 E2E（`full` / `unit-only` / `e2e-only`；同样待修路径）
  - [`all-in-one-entrypoint.sh`](_doc/003_script/all-in-one-entrypoint.sh) — all-in-one 容器入口：拷前端产物、并行拉起 Java 服务与 nginx
  - [`deploy_maven_center.sh`](_doc/003_script/deploy_maven_center.sh) — Central 发布：`publish`（`mvn deploy -Pcentral`）/ `verify` / `gpg-init` / `readme`

- [`_doc/004_skill/`](_doc/004_skill/) — 目前为空目录（暂无 skill 定义）

根 `poc/` 现为**空目录**且未被 git 跟踪，历史 Bolt 实验已迁到 `_doc/003_script/`。
