# z-graph

> 独立图数据库 —— 引擎原生 MVCC 版本链（Git 思想：内容哈希 commit / branch / merge / GC，无 Git 依赖）
> + Nebula 风格 Tag/EdgeType schema + OpenCypher 子集 + Bolt 风格二进制协议 + HTTP 控制面 + React 控制台。
> Java 8 · Netty 4 · Spring Boot 2.7（仅 starter）

它要解决的问题：图数据的一次写入不该覆盖历史。v5 存储引擎把图数据**只**存成磁盘上的追加式版本链——
每条节点/边/schema 一条版本链（undo log 语义，沿 `prevVersionId` 回溯）+ 四族版本化倒排 postings
（labels / adj-out / adj-in / edge-types），**没有"当前态镜像"、没有应用层物化**：任何 ref（main head、
分支 head、任意历史 commit）都由引擎按「commitSeq ∈ 该 ref 的祖先闭包」即时解析出完整图视图，
所以"在面板上切到任意 commit"是引擎读路径本身，不是某个缓存命中。commit id 是整个 commit object
规范化字节的 SHA-256 截 40 hex（对齐 Git：同对象必同 id、落盘即不可变），分支是纯指针文件，
ref 原子替换就是提交点。写成本 = O(本次变更量)。

架构分层参考 NebulaGraph 的 Meta / Query / Storage 划分（对应 `GraphMetaService` / `GraphQueryService` /
`GraphVersionStore`+storage 包），版本化思想对齐 Git（思想而非实现，零 Git 依赖），协议消息面参考
Bolt 4.4 规范；**只采用公开架构与协议资料，不复制上游代码**（详见文末「开源参考」）。

---

## 📋 基本信息

| 字段 | 值 |
|------|-----|
| **仓库** | `z-graph`（remote: `github.com/z-opc-foundation/z-graph`，分支 `main`） |
| **Maven 坐标** | `io.github.yuku123:z-graph`（聚合 POM）+ 5 个 reactor 子坐标 |
| **当前版本** | `1.1.0`（v5 存储引擎版本；根 POM 与 5 个子 POM **逐字面写 1.1.0**，本仓没用 `${revision}`；发布形状由 flatten-maven-plugin 1.5.0 `oss` 模式自包含） |
| **父项目** | `io.github.yuku123:z-boot-parent:1.1.0`（`<relativePath/>` 留空，parent 在 repo1 不在磁盘；旧 README 写的 1.0.21 是过期记录） |
| **Maven Central** | 实测 2026-10-09：`1.0.8` 及更早在 repo1 可读（ranged GET 200）；**`1.1.0` 尚未发布**（repo1 直连 404），发布走 `deploy_maven_center.sh` 并以 `/deployments` 状态为准 |
| **默认端口** | Bolt `7687` · HTTP 控制面 `8090`（`ZGraphServer` 两个都起）；控制台 dev `5173` / 容器 `3333` / all-in-one `3000` |
| **运行口径** | Java 8（class-file 52）· Spring Boot 2.7.18（只在 `z-graph-spring-boot-starter`）· Netty 4.1.138.Final |
| **最近更新** | 2026-10-09 |

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
| MVCC 版本化图 | `GraphVersionStore`：`beginWrite` / `commit` / `checkout` / `checkoutBranch` / `createBranch` / `log` / `merge` / `nodeVersions` / `nodeVersionAt` / `garbageCollect` / `versionStats` / `reachableCommitCount` | 写事务（`TxBuffer`，原 VersionOverlayStore）只是暂存指令集的缓冲，**永不充当视图**；提交 = 版本链追加 + 内容哈希 commit object 落盘 + ref 原子移动（提交点）。读一律由 `RefViewGraphStore` 沿链按 ancestry 解析，引擎堆内不存在整图状态对象（`NoMirrorTest` 结构断言常驻） |
| 并发控制 | `GraphVersionStore.StaleHeadException`（提交时 base head 已被推进） | 控制面对应返回 `409`，Bolt 侧 `RUN` 写路径重试一次 |
| 三方合并与冲突 | `GraphVersionStore.merge(target, source, author, message)` → `GraphMergeResult`（`isMerged` / `getCommit` / `getConflicts` / `hasConflicts`） | commit 级 replay：只对两侧自 merge-base 以来**触碰过的实体**做三方字段判定（不物化任何整图）；单侧变化自动合并，同字段双侧改动进冲突列表且不移动 target head；target 无分叉时 fast-forward，source 无新工作时原地不动 |
| 磁盘存储（追加式版本链） | `com.zifang.z.graph.core.storage` 包：`StorageEngine` 门面 + `VersionStore` / `PostingStore` / `FixedRecordFile`（mmap 窗口）/ `AppendSegment` / `EntityHeadTable` 槽表 / `NameDictionary` / `CommitObjectStore` / `RefStore` / `AncestryIndex` | 布局见下方「存储架构（v5）」；`GraphCodec.STORAGE_VERSION=5`；v4 仓（`objects/`+`repository.bin`）open 时自动迁移并保留 legacyId 反查 |
| GC 与压实 | `garbageCollect(keepBranches...)` | 不可达 commit object 删除 + 可达 delta 按 commitSeq 序重放进新引擎目录、原子换名；`versionRecordCount`/`payloadBytes` 回落到可达历史真实规模，保留分支的全部历史视图照常可读 |
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
| 元数据 | `SHOW TAGS` / `SHOW EDGES` / `SHOW INDEXES` / `SHOW TAG <n>` / `SHOW EDGE <n>` / `SHOW STATS` / `DESCRIBE TAG\|EDGE` | `SHOW STATS` 走视图计数与引擎统计，任何 `GraphStore` 视图上都可用 |
| 内置过程 | `CALL db.version` / `db.stats` / `db.tags` / `db.edges` / `db.indexes` / `db.branches` / `db.commits` / `db.head` | 后三类需要引擎绑到 `GraphVersionStore`；checkout 视图同样携带仓库引用，`db.branches` / `db.head` 在历史视图上照常可用 |
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
`z-graph-bolt-server/src/test/java/com/zifang/z/graph/bolt/BoltTestClient.java`。要接官方 driver，得先补 handshake 与版本协商（v1 明确不做）。

---

## 🗄️ 存储架构（v5）

思想对齐 Git、实现全自管：**不用 JGit/libgit2，不用外部数据库**，就是一批带布局约定的本地文件。

```
<dataDir>/
├── store/                        # 引擎文件（唯一图数据基底，没有任何"当前态镜像"）
│   ├── header.bin                # 64B 定长头：magic + layout v5 + id/序号分配器水位 + CRC32（原子重写）
│   ├── versions.idx              # 64B 定长版本记录区（offset = versionId × 64）
│   ├── versions.bin              # 变长 payload 追加区（完整实体记录：labels + properties / 边四元组）
│   ├── node-heads.tbl / edge-heads.tbl / label-schema-heads.tbl / etype-schema-heads.tbl / index-schema-heads.tbl
│   │                             # 定长槽表：slot = entityId×8 → headVersionId（纯索引，不是数据）
│   ├── labels.post / adj-out.post / adj-in.post / edge-types.post
│   │                             # 四族版本化倒排：40B 定长条目追加，删除 = 墓碑条目（undo log），
│   │                             # 解析 = ancestry 内 commitSeq 最大且非墓碑者胜（latest-wins）
│   ├── *.dict / *-heads.tbl      # 名称字典与 posting 槽表
├── commits/<commitId>.bin        # commit object（Git 式：整体规范化字节，id = SHA-256 截 40 hex）
├── refs/heads/<branch>           # 纯文本指针文件；ref 临时文件 + ATOMIC_MOVE = 提交点
└── HEAD                          # ref: refs/heads/main
```

读路径（引擎原生，唯一路径）：`RefViewGraphStore` 绑定 ref + `AncestryIndex` 闭包。`getNode` 走
entity-heads 槽取链头、沿 `prevVersionId` 回溯取第一条 ancestry 内可见的版本；邻接/label/属性索引
走 postings 段过滤；frontier head 读因链头必在自身闭包内而 O(1)/实体。**main head 与任意历史 commit
走同一条解析路径**——没有第二套"最新态"代码。

写路径：`TxBuffer` 读穿透绑定的 head 视图、写进暂存 delta；提交时校验 head 未被推进（`StaleHeadException`
乐观并发）→ 版本记录 + postings 追加 → commit object 内容寻址落盘 → 数据 force → ref 原子移动。
崩溃序：ref 指向的 commit 必完整，孤儿对象由 GC 收。

GC：不可达 commit 删除 + 可达 delta 按 commitSeq 序**重放进新引擎目录**、原子换名（两步 move 之间的
崩溃由 open 守卫收尾）。压实后墓碑与不可达版本消失，可达历史逐 commit 仍可读、reopen 幂等。

v4 → v5 迁移：open 见 `repository.bin`（v4 布局）而无 `store/header.bin` 时自动执行——v4 的每个 commit
delta 按 commitSequence 升序重放，commit object 以内容哈希重写（parents 映射到新 id，**旧 id 记进
legacyId**）；`checkout(<v4旧id>)` / HTTP `?commit=<旧id>` 按 legacyId 反查继续可用。v4 源归档为
`objects.v4.bak` / `repository.v4.bak`，不删。中途崩溃的下次 open 从 v4 源整体重来（幂等）。

---

## 🏗️ 项目结构

```
z-graph/
├── pom.xml                        # 聚合 POM：parent z-boot-parent:1.1.0，6 条自家坐标 DM，flatten 常开
├── z-graph-api/                   # 抽象与值类型：GraphStore / Node / Edge / GraphCommit / GraphMergeResult
│                                  #   / TagSchema / EdgeTypeSchema / Colls（Java 8 没有 Map.of 的替代品）
├── z-graph-protocol/              # Bolt 风格帧与值编解码：BoltConstants / BoltFrames / BoltMessageDecoder
├── z-graph-core/                  # 引擎：CypherEngine / InMemoryGraphStore（测试镜像与等价对照基线，不再是视图载体）
│                                  #   / storage 包（VersionStore / PostingStore / StorageEngine / CommitObjectStore
│                                  #   / RefStore / AncestryIndex / RefViewGraphStore / PayloadCodec / FixedRecordFile
│                                  #   / AppendSegment / StoreHeader / NameDictionary / Visibility / LegacyMigrator）
│                                  #   / TxBuffer / GraphDelta / GraphCommit / GraphCodec / LegacyMigrator
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

第三方版本一律由 `z-boot-parent:1.1.0` → `z-boot-dependencies`（地板）+ `z-boot-fleet`（兄弟仓权威表）供给，
模块 POM 里不该再出现字面版本钉。构建解析不到 `io.github.yuku123:z-boot-parent:1.1.0` 时先确认能连 repo1。
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
不指定数据目录时引擎落到进程临时目录（磁盘追加式，进程退出即弃），不占用户指定路径。

### 嵌入式（Java 8 可编译的真实 API）

```java
import com.zifang.z.graph.api.*;
import com.zifang.z.graph.core.*;
import java.util.*;

GraphVersionStore repo = new GraphVersionStore();        // 不传目录 = 进程临时目录（引擎是磁盘追加式）；new GraphVersionStore(Path) 落指定目录
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
| `Z_GRAPH_DATA_DIR` | 未设 = 进程临时目录 | 版本仓库目录；容器里默认 `/var/lib/z-graph` |
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
    data-dir:                      # 留空 = 进程临时目录（引擎是磁盘追加式，不占用户指定路径）
```

这 3 个键就是 `ZGraphProperties` 的全部字段。**1.1.0 起删掉了** `checkpoint-interval` /
`max-retained-views` / `retained-whole-graph-views` / `view-layer-limit` 四个视图缓存键——视图缓存与
检查点在 v5 引擎里已不存在（读一律即时解析），留着只会骗人；也没有 `mode`、`host`、`bolt-port`、
`rest-port`、`persistence.*`、`cache.*` 这些键（控制面 bind 通配地址所以没有 host，starter 也压根不起
Bolt）。`GraphControlServer` 在构造函数里就 bind，端口被占则容器启动直接失败；Bean 配
`destroyMethod = "stop"`。

### 优雅关闭

`GraphControlServer.start()` 注册 shutdown hook，SIGTERM/SIGINT 时 `server.stop(5)`（最多等 5 秒）；
`ZGraphServer` 的 hook 同时 `bolt.shutdown()`（Netty `shutdownGracefully()`）+ `control.stop()`（`stop(0)`，
用于测试里的即时释放）。

---

## 🧪 测试

```bash
mvn test                     # 195 个 @Test / 25 个测试类，不需要外部依赖
mvn -pl z-graph-core test    # 只跑引擎：161 个用例
```

分布：`z-graph-core` 19 个测试类共 161 个用例（`MvccVersioningTest` 16、`CypherEngineTest` 15、
`GraphVersionStoreTest` 13（含 FF/删除传播/modify-delete 冲突等 merge 语义）、`ExplainAndDescribeTest` 13、
`DdlAndPathTest` 13、`InMemoryGraphStoreTest` 12、`IndexOptimizerAndAlterTest` 12、`SchemaManagementTest` 12、
`ShowAndAggregationTest` 12、`CypherExecutorTest` 11、`DiskStoreTest` 10（record 往返/reopen 幂等/链回溯/
postings 墓碑 latest-wins/remap 跨界/ancestry 过滤矩阵）、`OrderLimitWithAndSnapshotTest` 9、
`ContentHashTest` 4（同对象必同 id / 改元数据必换 id / 篡改即拒读）、`VisibilityMatrixTest` 3
（分叉 A/B 互不可见、merge 后互见）、`LegacyMigrationTest` 2（v4 逐字节构造→无损迁移→legacyId 反查）、
`VersionedSchemaTest` 3、`NoMirrorTest` 1（反射扫 `GraphVersionStore` 对象图，断言堆内不存在整图状态集合，
防物化回潮）、另有无用例的 `MvccStressHarness`）；`z-graph-bolt-server` 28 个（`BoltFramesTest` 14、
`BoltServerE2ETest` 11、`GraphControlServerTest` 2、`ZGraphServerTest` 1）；`z-graph-spring-boot-starter`
6 个。`z-graph-api` / `z-graph-protocol` 没有自己的测试源码目录，协议编解码用例放在 bolt-server 模块下。

MVCC 压力/性能台是**带 verdict 的门禁**（12 条，任一判红即非 0 退出，可当 CI 卡口）：
提交成本平坦（S2 双口径 ≤3x）、时间旅行正确（S3 采样回放 0 错）、并发 StaleHead 乐观失败（S5 三臂）、
落盘重启一致（S6 抽检 + 追加量）、GC 真回收（S7 released>0）、**读等价铁律**（S8：同一条 delta 序列
喂给版本仓库与 `InMemoryGraphStore` 镜像，13 个 ref 上节点属性/标签/邻接/label 索引逐实体比对，
0 走样；`--sabotage=equivalence` 故意错位一轮可自证该闸必红）。

```bash
java -Xms1g -Xmx8g -cp z-graph-core/target/classes:z-graph-core/target/test-classes:z-graph-api/target/classes:z-graph-protocol/target/classes \
  com.zifang.z.graph.bench.MvccStressHarness --profile=full \
  --workdir=/tmp/zgraph-stress --json=/tmp/zgraph-stress/results.jsonl
```

HTTP 控制面端到端（标准库，无第三方依赖；先起 `ZGraphServer`，覆盖 /health、写查询、commit 图、
`?commit=` 时间旅行、DDL schema、kill -9 重启幂等）：

```bash
Z_GRAPH_SERVER_PID=<pid> Z_GRAPH_RESTART_CMD='<重启命令模板，含 {port}>（kill -9 重启腿用）' \
  python3 _doc/003_script/test_http_control_plane.py 8090
```

Bolt 端到端（`_doc/003_script/test_bolt_*.py`）当前**全部跑不通，且是历史债不是 v5 回归**：协议模块与
这批脚本自仓初始化以来零改动（`git diff 97f99c2` 可证），死因是服务端从未实现 Bolt handshake（v1
非目标）——官方 `neo4j` driver 卡死在版本协商，手写帧脚本的 HELLO 载荷也与 `BoltMessageDecoder`
的 struct 契约对不上。Bolt 协议面的回归保障是 bolt-server 单元/E2E 测试（`BoltTestClient` 直灌正确帧）。

**重启幂等与时间旅行的线级证据**见 `test_http_control_plane.py`：kill -9 后 reopen 的 head 指纹逐字节
一致（内容寻址的直接推论），历史 commit 视图读数与重启前一致。

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

v5 引擎口径（2026-10-09，`MvccStressHarness --profile=full` 12/12 PASS，M3 Max / JDK 24 跑 Java 8 字节码；
每条数字背后都有可复现场景与 verdict，重新跑一遍即可对账）：

| 场景 | 实测 |
|------|------|
| 批量导入（10 万节点 + 5 万边，201 commits） | 无索引 48,327 ent/s；带 (Person,name) 索引 49,629 ent/s——索引下推不是平方级（保留比 103%） |
| 提交成本随图规模（1k→20 万节点） | commit p50 增长 1.1x、整事务 1.2x（门槛 ≤3x）：写成本平坦，不随图变大 |
| 时间旅行（2 万节点 × 2000 commits） | 采样 25 个深度回放，节点数不符 0 次；历史视图打开即解析，无缓存预热 |
| 落盘重启（500 提交 / 2 万节点） | 每提交追加约 208B（对照：每 commit 全量副本约 1.4MiB，追加量差约 7000x）；重启加载 21ms，抽检 25 个历史视图 0 不符 |
| GC 压实 | 丢 6 条分支 240 个 commit 后回收，版本记录 30,239 → 29,999，保留分支视图与分支指针无损 |
| 并发 | 8 线程同分支乐观写：冲突如实拒绝、0 丢失更新；8 分支并行 400/400 成功 |
| 读等价 | 13 个 ref 上引擎解析视图与内存镜像逐实体比对 0 走样 |

历史 T1 报告（[`_doc/001_arch/TEST_REPORT.md`](_doc/001_arch/TEST_REPORT.md)，2026-08-31，Bolt POC 路径）
的 500/500 与 QPS 5278 属于**旧协议路径**的历史快照，不描述当前引擎，别拿它当现况。

旧 README 里两张表**已从本文删除**："100 万节点 / 500 万边：单节点 add 80,000 QPS、shortestPath 3,500 QPS、
版本化 commit 1,200 QPS"，以及"macOS 实测健康检查 5.2ms / 查询 6.0ms / 进程内存 73MB / 单实例 10,000 req/s"。
仓内找不到产生这些数字的可复现用例（其中 `shortestPath` 根本没有实现），留着就是让下一个读者拿它当承诺。
要基准就现跑上面的 `MvccStressHarness`，它每条场景都带可判红的 verdict。

---

## 🩺 故障排查

| 现象 | 判法 |
|------|------|
| `Address already in use: 7687` / `8090` | `lsof -i :<port>`——注意 Docker 端口映射的属主进程是 `com.docke`；或改 `Z_GRAPH_BOLT_PORT` / 传首个命令行参数 |
| starter 起不来 / 端口被占 | `GraphControlServer` 构造即 bind，异常直接冒到容器启动；先确认 8090 上是不是已有实例 |
| 写请求返回 409 | `StaleHeadException`：base head 已被别的连接推进；控制面重读 head 后重试，Bolt 侧 `RUN` 写路径已自动重试一次 |
| 历史 commit 打不开（Unknown commit） | v4→v5 迁移后历史 id 全变（内容哈希）；用新 id 或直接用旧 id——`requireCommit` 会按 legacyId 反查，反查不到说明该 commit 已被 GC |
| 磁盘上出现 `objects.v4.bak` / `repository.v4.bak` | 正常：v4 仓迁移成功后的归档，确认无误后可手动删 |
| `store.compacting/` 残留 | 上次 GC 压实中途崩溃的孤儿目录，下次 open 会自动清理；`store.retired.*` 同理 |
| 官方 Neo4j driver 连不上 | 预期行为：服务端不处理 magic + 版本协商段（v1 非目标）；HTTP 控制面不受影响 |
| 前端 404 / 接口全红 | `docker logs z-graph-frontend`；`curl http://localhost:3333/api/health` 验反代（nginx `/api/` 会剥掉前缀） |
| CORS 收紧无效 | 系统属性 `z.graph.cors.allowedOrigins` 优先于 `Z_GRAPH_CORS_ALLOWED_ORIGINS`，两处都设时后者被盖掉 |
| 想看请求轨迹 | `curl 'http://localhost:8090/meta/logs?limit=20&status=4xx'`，或按 `requestId=` 精确捞（环形缓冲只保最近 500 条） |

## ⚠️ 1.1.0（v5 引擎）breaking 清单

- **磁盘布局换代**：`objects/` + `repository.bin` → `store/` + `commits/` + `refs/`。v4 仓 open 时自动迁移
  （见「存储架构」），无需手工操作，但**升级前请照常备份**。
- **历史 commit id 全变**：v4 的 id 是应用层拼的，v5 是内容哈希。迁移器把旧 id 存进 legacyId，
  `checkout(旧id)` / HTTP `?commit=旧id` 自动反查；但自己持久化过 v4 id 的下游系统要换成新 id。
- **starter 配置键删除**：`checkpoint-interval` / `max-retained-views` / `retained-whole-graph-views` /
  `view-layer-limit` 四键随视图缓存一起消失；挂着它们的 yml 不报错（Spring 忽略未知键），但不再有任何效果。
- **`withCheckpointInterval` 等五个调参器删除**：`GraphVersionStore` 不再有视图缓存可调。
- **`versionStats()` 字段语义更换**：`commitCount` / `branchCount` / `versionRecordCount` /
  `nextNodeId` / `nextEdgeId` / `nextCommitSeq` / `versionPayloadBytes` / `ancestryCachedClosures`；
  不再有缓存命中类字段。
- **属性值不再有有损兜底**：payload 编码认 NULL / BOOL / INT(Integer/Short/Byte→int、Long) /
  DOUBLE(Float/Double) / STRING / MAP（嵌套，自包含长度前缀）/ LIST / BINARY，未知类型 fail-fast
  抛异常，不再 `toString()` 塞字符串。

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

- `_doc/002_deploy/` — 目前为空目录（部署资产实际躺在根 `deploy/`，见「部署」一节）

- [`_doc/003_script/`](_doc/003_script/) — E2E 驱动与运维脚本：
  - [`test_http_control_plane.py`](_doc/003_script/test_http_control_plane.py) — HTTP 控制面端到端（标准库，
    无第三方依赖）：/health、写查询、commit 图、`?commit=` 时间旅行、DDL schema、kill -9 重启幂等
  - [`test_bolt_raw.py`](_doc/003_script/test_bolt_raw.py) — 手写帧驱动（当前跑不通，死因见「测试」节）
  - [`test_bolt_full.py`](_doc/003_script/test_bolt_full.py) — 7 类 `RETURN` 字面量子场景（同上，历史债）
  - [`test_bolt_error.py`](_doc/003_script/test_bolt_error.py) — 错误路径用例（同上，历史债）
  - [`test_bolt_concurrent.py`](_doc/003_script/test_bolt_concurrent.py) — 并发压力（同上，历史债）
  - [`test_bolt_poc.py`](_doc/003_script/test_bolt_poc.py) — 官方 `neo4j` Python driver 版用例（预期失败：
    服务端无 handshake，v1 非目标）
  - [`run_e2e.sh`](_doc/003_script/run_e2e.sh) — 编排脚本（路径假设过期为 `poc/` 时代，历史债）
  - [`run_t1_verify.sh`](_doc/003_script/run_t1_verify.sh) — T1 全流程编排（同上，历史债）
  - [`all-in-one-entrypoint.sh`](_doc/003_script/all-in-one-entrypoint.sh) — all-in-one 容器入口：拷前端产物、并行拉起 Java 服务与 nginx
  - [`deploy_maven_center.sh`](_doc/003_script/deploy_maven_center.sh) — Central 发布：`publish`（`mvn deploy -Pcentral`）/ `verify` / `gpg-init` / `readme`

- `_doc/004_skill/` — 目前为空目录（暂无 skill 定义）

根 `poc/` 现为**空目录**且未被 git 跟踪，历史 Bolt 实验已迁到 [`_doc/003_script/`](_doc/003_script/)。
