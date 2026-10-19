# 项目总览

## 1. 项目简介

z-graph 是一座**独立图数据库**：引擎原生 MVCC 版本链 + Nebula 风格 Tag/EdgeType schema + OpenCypher 子集 + Bolt 风格二进制协议 + HTTP 控制面 + React 控制台。

它要解决的问题是「图数据的一次写入不该覆盖历史」。v5 存储引擎把图数据**只**存成磁盘上的追加式版本链 —— 每条节点 / 边 / schema 一条版本链（undo log 语义，沿 `prevVersionId` 回溯）+ 四族版本化倒排 postings（labels / adj-out / adj-in / edge-types）。**没有「当前态镜像」、没有应用层物化**：任何 ref（main head、分支 head、任意历史 commit）都由引擎按「commitSeq ∈ 该 ref 的祖先闭包」即时解析出完整图视图 —— 所以「在面板上切到任意 commit」是引擎读路径本身，不是某个缓存命中。

commit id 是整个 commit object 规范化字节的 **SHA-256 截 40 hex**（对齐 Git：同对象必同 id、落盘即不可变），分支是纯指针文件，ref 原子替换就是提交点。写成本 = O(本次变更量)。

**技术栈：** Java 8（class-file 52）· Netty 4.1.138.Final（`netty-all`）· JDK 内置 `com.sun.net.httpserver`（HTTP 控制面，**不是** Spring MVC / WebFlux）· Spring Boot 2.7.18（仅 `z-graph-spring-boot-starter` 依赖 `spring-boot-autoconfigure`）· React 18.3.1 + Vite 5.4.10（控制台，自写 CSS、无 AntD）

**版本化思想对齐 Git、架构分层参考 NebulaGraph、消息面参考 Bolt 4.4 —— 只采用公开架构思想与协议资料，不复制上游代码。** 零 Git 依赖（不用 JGit / libgit2）、零外部数据库。

## 2. 模块架构

```
z-graph/
├── pom.xml                        # 聚合 POM：parent z-boot-parent:1.1.0，6 条自家坐标 DM，flatten 常开
├── z-graph-api/                   # 抽象与值类型（8 个类）
│                                  #   GraphStore / Node / Edge / GraphCommit / GraphMergeResult
│                                  #   / TagSchema / EdgeTypeSchema / Colls（Java 8 没有 Map.of 的替代品）
├── z-graph-protocol/              # Bolt 风格帧与值编解码（3 个类）
│                                  #   BoltConstants / BoltFrames / BoltMessageDecoder
├── z-graph-core/                  # 引擎（27 个类）
│   ├── core/                      # CypherEngine / InMemoryGraphStore / GraphVersionStore / TxBuffer
│   │                              #   / GraphDelta / GraphCommit / GraphCodec / LegacyMigrator
│   │                              #   / GraphWriteTransaction / GraphCheckout / GraphQueryService
│   │                              #   / GraphMetaService / ReadOnlyGraphStore / CypherExecutor
│   └── core/storage/              # 磁盘版引擎（13 个类）
│                                  #   StorageEngine / VersionStore / PostingStore / CommitObjectStore
│                                  #   / RefStore / AncestryIndex / RefViewGraphStore / PayloadCodec
│                                  #   / FixedRecordFile / AppendSegment / StoreHeader / NameDictionary
│                                  #   / Visibility
├── z-graph-bolt-server/           # 服务端（5 个类）
│                                  #   BoltServer(Netty) / BoltMessageHandler
│                                  #   / GraphControlServer(JDK HttpServer，13 个 context)
│                                  #   / 三个 main：ZGraphServer / BoltServer / GraphControlServerMain
├── z-graph-spring-boot-starter/   # 5 个类：@AutoConfiguration + z.graph.* 配置（默认 enabled=false）
│                                  #   ZGraphAutoConfiguration / ZGraphProperties / GraphEmbeddedServerConfig
│                                  #   / GraphHostAutoConfiguration / GraphProxyController
├── z-graph-console/               # React 18 + Vite 5 控制台（npm 工程，private，不在 Maven reactor）
├── deploy/
│   ├── docker/                    # server.Dockerfile / frontend.Dockerfile / all-in-one.Dockerfile / docker-compose.yml
│   ├── kubernetes/z-graph.yaml    # Namespace + ConfigMap + PVC + 2 Deployment + 2 Service
│   └── nginx/                     # frontend.conf（容器，envsubst upstream）/ frontend-local.conf（本机 3333）
├── _frontend/                     # z-graph-component + z-graph-suit（另两套前端工程）
├── _doc/                          # 003_script（10 个脚本）+ 005_testing（1 份测试报告）
└── .github/workflows/             # java-tests.yml（mvn -B -ntp verify）+ build-images.yml（三镜像推 GHCR）
```

Maven reactor 只有 **5 个模块**；`z-graph-console` 是独立 npm 工程（`package.json` 里 `private: true`）。5 个子 POM **都没有** `maven.deploy.skip`，全部参与发布。

## 3. 核心功能

### 3.1 存储架构（v5）

思想对齐 Git、实现全自管，就是一批带布局约定的本地文件：

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
│   └── *.dict / *-heads.tbl      # 名称字典与 posting 槽表
├── commits/<commitId>.bin        # commit object（Git 式：整体规范化字节，id = SHA-256 截 40 hex）
├── refs/heads/<branch>           # 纯文本指针文件；ref 临时文件 + ATOMIC_MOVE = 提交点
└── HEAD                          # ref: refs/heads/main
```

`GraphCodec.STORAGE_VERSION = 5`（旧布局常量 `LEGACY_STORAGE_VERSION = 2`）。

**读路径（唯一路径）：** `RefViewGraphStore` 绑定 ref + `AncestryIndex` 闭包。`getNode` 走 entity-heads 槽取链头、沿 `prevVersionId` 回溯取第一条 ancestry 内可见的版本；邻接 / label / 属性索引走 postings 段过滤；frontier head 读因链头必在自身闭包内而 O(1)/实体。**main head 与任意历史 commit 走同一条解析路径** —— 没有第二套「最新态」代码。

**写路径：** `TxBuffer`（写事务缓冲，原 `VersionOverlayStore`）读穿透绑定的 head 视图、写进暂存 delta；提交时校验 head 未被推进（`StaleHeadException` 乐观并发）→ 版本记录 + postings 追加 → commit object 内容寻址落盘 → 数据 force → ref 原子移动。崩溃序：ref 指向的 commit 必完整，孤儿对象由 GC 收。

**GC 与压实：** `garbageCollect(keepBranches...)` 做不可达 commit 删除 + 可达 delta 按 commitSeq 序**重放进新引擎目录**、原子换名（两步 move 之间的崩溃由 open 守卫收尾）。

**v4 → v5 迁移：** open 见 `repository.bin`（v4 布局）而无 `store/header.bin` 时自动执行 —— v4 的每个 commit delta 按 commitSequence 升序重放，commit object 以内容哈希重写（parents 映射到新 id，**旧 id 记进 legacyId**）；`checkout(<v4旧id>)` / HTTP `?commit=<旧id>` 按 legacyId 反查继续可用。v4 源归档为 `objects.v4.bak` / `repository.v4.bak`，不删；中途崩溃的下次 open 从 v4 源整体重来（幂等）。

### 3.2 MVCC 版本化图

`GraphVersionStore` 提供 `beginWrite` / `commit` / `checkout` / `checkoutBranch` / `createBranch` / `log` / `merge` / `nodeVersions` / `nodeVersionAt` / `garbageCollect` / `versionStats` / `reachableCommitCount`。

- **写事务**（`TxBuffer`）只是暂存指令集的缓冲，**永不充当视图**；提交 = 版本链追加 + 内容哈希 commit object 落盘 + ref 原子移动（提交点）。
- **读**一律由 `RefViewGraphStore` 沿链按 ancestry 解析，引擎堆内不存在整图状态对象（`NoMirrorTest` 结构断言常驻）。
- **并发控制** —— 提交时 base head 已被推进抛 `StaleHeadException`；控制面对应返回 `409`，Bolt 侧 `RUN` 写路径重试一次。
- **三方合并** —— `GraphVersionStore.merge(target, source, author, message)` 返回 `GraphMergeResult`（`isMerged` / `getCommit` / `getConflicts` / `hasConflicts`）。commit 级 replay：只对两侧自 merge-base 以来**触碰过的实体**做三方字段判定（不物化任何整图）；单侧变化自动合并，同字段双侧改动进冲突列表且不移动 target head；target 无分叉时 fast-forward。

### 3.3 OpenCypher 子集

`CypherEngine`（2,227 行，手写解析）+ `CypherExecutor`（`RETURN` 字面量旧路径）。已实现：

- **读写** —— `MATCH` / `OPTIONAL MATCH` / `CREATE` / `MERGE`（upsert）/ `DELETE` / `DETACH DELETE` / `SET` / `REMOVE`
- **过滤** —— `WHERE`：`AND` / `OR` / `NOT` / `IN` / `CONTAINS` / `STARTS WITH` / `ENDS WITH` / `IS [NOT] NULL` / `EXISTS` / `NOT EXISTS`
- **返回** —— `RETURN` / `DISTINCT` / `WITH`（投影 + `WITH ... ORDER BY`）/ `ORDER BY` / `SKIP` / `LIMIT`
- **聚合** —— `count` / `sum` / `avg` / `min` / `max`（自动分组）
- **路径** —— 变长 `(a)-[:TYPE*min..max]->(b)`，反向 `<-[]->`、无方向 `-[]-`、类型过滤均支持
- **其他** —— `UNWIND [..] AS x` / 多语句 `;` / 参数绑定（`$name`，走 `CypherEngine.execute(cypher, parameters)`）
- **元数据** —— `SHOW TAGS` / `SHOW EDGES` / `SHOW INDEXES` / `SHOW TAG <n>` / `SHOW EDGE <n>` / `SHOW STATS` / `DESCRIBE TAG|EDGE`
- **内置过程** —— `CALL db.version` / `db.stats` / `db.tags` / `db.edges` / `db.indexes` / `db.branches` / `db.commits` / `db.head`
- **DDL** —— `CREATE TAG` / `CREATE EDGE [TYPE]` / `CREATE TAG INDEX` / `CREATE EDGE INDEX` / `DROP TAG|EDGE|INDEX` / `ALTER TAG|EDGE` / `REBUILD` / `EXPLAIN`

**明确的缺口：** `collect()` 未实现（`parseAggregate` 正则只收 5 个函数）；`shortestPath()` / `allShortestPaths()` 全仓 0 处实现；`WITH` 里带聚合会抛 `CypherException`；`UNWIND` 只接字面量列表。

### 3.4 Tag / EdgeType schema 与索引下推

`TagSchema` / `EdgeTypeSchema` + `CypherEngine` 的 `CREATE TAG` / `CREATE EDGE [TYPE]` / `DROP TAG` / `DROP EDGE` / `ALTER TAG` / `ALTER EDGE` / `REBUILD`，Nebula 风格声明式属性、类型与 NOT NULL 校验，覆盖前按旧 schema 校验存量数据。

属性索引下推是**窄口径**：只有模式恰为 `(v:Label)`、WHERE 恰为 `v.prop = 字面量`、且 `(Label, prop)` 索引存在时才替换全表扫描（`CypherEngine#tryIndexSeed`），其余仍走标签扫描。

### 3.5 Bolt 风格协议服务

`BoltServer`（Netty pipeline）+ `z-graph-protocol` 的 `BoltConstants` / `BoltFrames` / `BoltMessageDecoder` + `BoltMessageHandler`：

| 请求 | 响应 | 备注 |
|---|---|---|
| `HELLO` (0x01) | `SUCCESS` | 回 `connection_id` / `server=z-graph/1.0.0-SNAPSHOT` / `edition=community`；**不校验 scheme/credentials** |
| `RUN` (0x10) | `SUCCESS`(qid, fields, t_first) | 读查询绑定当前 head 快照；写查询自行开事务并 commit 一次（冲突重试一次） |
| `PULL` (0x3F) | `SUCCESS` + `RECORD`*N + `SUCCESS`(has_more) | 用普通 `SUCCESS` 代替 `PULL_SUCCESS`，POC 简化 |
| `BEGIN` / `COMMIT` / `ROLLBACK` | `SUCCESS` / `FAILURE` | 连接级事务落在 `main` 分支的 `GraphWriteTransaction` |

### 3.6 HTTP 控制面

`GraphControlServer`（JDK `HttpServer`）注册 **13 个 context**：

| 路径 | 方法 | 说明 |
|---|---|---|
| `/health` | GET | `status` / `head` / `nodeCount` / `edgeCount`；**认证与限流均豁免** |
| `/query` | GET / POST | `?cypher=&branch=&commit=` 或同名字段的 JSON body；返回**裸数组** |
| `/query/batch` | POST | 多条语句顺序执行，逐条回 `statementIndex` + `elapsedMs` |
| `/query/explain` | POST | 查询计划（读写判定、是否可走索引、是否含变长路径） |
| `/meta/branches` | GET | 分支名列表 |
| `/meta/commits` | GET | commit 列表（id / parents / branch / author / message / node / edge 计数） |
| `/meta/schema` | GET | `?branch=main`，内部用 `SHOW TAGS` / `SHOW EDGES` |
| `/meta/stats` | GET | `?branch=main`，内部 `CALL db.stats()` |
| `/meta/metrics` | GET | JSON：uptime / totalRequests / errorRate / rateLimitedRequests / authFailures / apiTokenEnabled / jvmMemory / availableProcessors 等 |
| `/meta/logs` | GET | 环形缓冲最多 500 条；可按 `limit&offset&method&status&path&requestId` 过滤 |
| `/meta/export` | GET | `?branch=` 导出节点 + 按类型取的边 + schema |
| `/meta/import` | POST | body 的 `nodes` / `edges` 段以 Cypher `CREATE` 落地 |
| `/options` | OPTIONS | 204 + CORS 头 |

统一行为：安全响应头 `X-Content-Type-Options: nosniff` / `X-Frame-Options: DENY` / `X-XSS-Protection: 1; mode=block` / `Referrer-Policy: strict-origin-when-cross-origin`；`X-Request-ID` 缺省生成 16 字符、客户端传入则复用；`Accept-Encoding: gzip` 且响应 >256 字节才压缩；CORS 白名单命中时回显具体 origin 并带 `Vary: Origin`。

状态码按异常类型分类：分支 / commit 不存在 → `404`，参数错 → `400`，写冲突 → `409`，限流 → `429` + `Retry-After: 60`，未授权 → `401`，其余才 `500`。

### 3.7 可视化控制台

`z-graph-console` 是独立 npm 工程（`private: true`），`src/App.jsx` 定义 **8 个 tab**：总览 / 图视图 / 分支 / 提交历史 / Cypher 查询 / Schema / 请求日志 / API 文档。页面在 `src/pages/`（`Dashboard.jsx` / `GraphView.jsx` / `Branches.jsx` / `Commits.jsx` / `QueryPage.jsx` / `Schema.jsx` / `LogViewer.jsx` / `ApiDocs.jsx`）+ `src/components/CypherEditor.jsx`，样式是自写的 `src/styles.css`（无 AntD / 无组件库）。

## 4. 对外集成 / 上下游

**上游依赖：** `z-boot-parent:1.1.0`（`relativePath` 留空）→ 第三方地板（Netty 4.1.138.Final、log4j-api 2.25.4、slf4j-simple 2.0.13、junit 5.10.2）。根 POM 按坐标写**直接**条目顶住地板下压的有意版本分歧：`slf4j-api` 2.0.13、`junit` 5.10.2 / `junit-platform` 1.10.2、`objenesis` 3.2，根 POM 注释里逐条写了原因。

**对外边界：**

| 面 | 形态 | 端口 |
|---|---|---|
| Bolt 协议 | Netty `BoltServer`（`z-graph-bolt-server`） | **7687**（`Z_GRAPH_BOLT_PORT` / `z.graph.boltPort`） |
| HTTP 控制面 | JDK `com.sun.net.httpserver` | **8090**（`Z_GRAPH_HTTP_PORT` / `z.graph.httpPort`） |
| 控制台 dev | Vite dev server | **5173**（代理到 8090） |
| 控制台容器 | nginx | **80**（Service）/ 本机 conf 3333 |
| all-in-one 镜像 | nginx 同时代理 API | **3000**（`EXPOSE 3000 7687 8090`） |

`ZGraphServer` 是两个端口一起起的那个 main；`GraphControlServer` 还在 `z-graph-bolt-server` 里另有独立 main（`GraphControlServerMain`）。

**环境变量：** `Z_GRAPH_BOLT_PORT`（7687）、`Z_GRAPH_HTTP_PORT`（8090）、`Z_GRAPH_DATA_DIR`（未设 = 进程临时目录，容器里默认 `/var/lib/z-graph`）、`Z_GRAPH_API_TOKEN`（未设 = 不鉴权；设置后启用 Bearer 校验）、`Z_GRAPH_RATE_LIMIT`（`0` = 不限，每 IP 每分钟上限）、`Z_GRAPH_CORS_ALLOWED_ORIGINS`（默认 `*`）、`Z_GRAPH_API_UPSTREAM`（前端镜像 nginx upstream，容器里 `z-graph-server:8090`）。

**Spring Boot starter：** `z.graph.enabled` **默认 false** —— 不装任何 Bean、不碰磁盘和网络；开启时装的是 `GraphVersionStore` + 内嵌 HTTP 控制面，**不起 BoltServer**。`ZGraphProperties` 只有 3 个字段（`enabled` / `port` / `data-dir`），1.1.0 起删掉了 `checkpoint-interval` / `max-retained-views` / `retained-whole-graph-views` / `view-layer-limit` 四个视图缓存键 —— v5 引擎里视图缓存与检查点已不存在，留着只会骗人。

**部署资产：** `deploy/docker/`（3 个 Dockerfile + compose）、`deploy/kubernetes/z-graph.yaml`（Namespace + ConfigMap + PVC + 2 Deployment + 2 Service，容器端口 7687 / 8090 / 80）、`deploy/nginx/`（2 份 conf）。CI：`java-tests.yml` 跑 `mvn -B -ntp verify`；`build-images.yml` 构建并推三个镜像到 GHCR。

**对外发布：** Maven Central（根 POM 与 5 个子 POM 逐字面写 `1.1.0`，没用 `${revision}`；发布形状由 `flatten-maven-plugin 1.5.0` 的 `oss` 模式自包含）。

## 5. 技术特点

1. **没有"当前态镜像"是硬结构，不是优化**：`RefViewGraphStore` 沿版本链按 ancestry 闭包即时解析视图，main head 与任意历史 commit 走**同一条**解析路径。`NoMirrorTest` 用结构断言把这条性质常驻在测试里 —— 引擎堆内一旦出现整图状态对象就会红。
2. **提交点是一个原子文件操作**：ref 是纯文本指针文件，`ref 临时文件 + ATOMIC_MOVE` 就是提交点；崩溃序被设计成「ref 指向的 commit 必完整，孤儿对象由 GC 收」。
3. **删除用墓碑条目实现**：四族倒排 posting 是纯追加，删除 = 追加一条墓碑，解析取「ancestry 内 commitSeq 最大且非墓碑者胜」。所以历史视图天然可回放，不需要为删除单独写补偿逻辑。
4. **GC 是重放式压实而不是标记清除**：不可达 commit 删除 + 可达 delta 按 commitSeq 序重放进新引擎目录、原子换名；两步 move 之间的崩溃由 open 守卫收尾。
5. **v4→v5 迁移把旧 id 保留为可查键**：旧 id 记进 legacyId，`checkout(<v4旧id>)` 与 HTTP `?commit=<旧id>` 继续可用；源文件归档不删，中途崩溃从源整体重来（幂等）。
6. **索引下推是窄口径且写明边界**：只有 `(v:Label)` + `v.prop = 字面量` + 存在 `(Label, prop)` 索引这三条件全中才替换全表扫描，其余仍走标签扫描 —— 宁可少优化也不给错结果。
7. **协议实现是 POC 级的，且缺口写在矩阵里**：`PULL` 用普通 `SUCCESS` 代替 `PULL_SUCCESS`、`HELLO` 不校验 scheme/credentials、HTTP `/query` 不做参数绑定 —— README 用一张消息矩阵把每条简化都标出来，读者能知道哪条不能直接当 Neo4j 用。
8. **同一个仓里两套前端工程并存**：`z-graph-console`（独立 npm 工程，8 个 tab，不进 Maven reactor）与 `_frontend/z-graph-component` + `_frontend/z-graph-suit`（组织统一的前端基座那套）—— 前者是本仓自带的可视化，后者是接入 z-opc 管理台的形态。
9. **端口是显式契约**：Bolt 7687 / HTTP 8090 在代码（`BoltServer` 缺省参数、`ZGraphServer` 环境变量）、配置（`z.graph.boltPort` / `z.graph.httpPort`）、部署（k8s Service、all-in-one 镜像 `EXPOSE`、nginx upstream）四处一致。

## 6. 目录结构

源码包树共 7 个包（`com.zifang.z.graph.*`），主源码 **48 个 `.java`**（api 8 + protocol 3 + core 27 + bolt-server 5 + starter 5），测试 25 个文件 / **195 个 `@Test`**：

```
z-graph/
├── z-graph-api/src/main/java/com/zifang/z/graph/api/
│   ├── GraphStore.java            # 存储抽象（addNode / getOutEdges / getInEdges / getEdgesByType / traverse …）
│   ├── Node.java / Edge.java
│   ├── GraphCommit.java / GraphMergeResult.java
│   ├── TagSchema.java / EdgeTypeSchema.java
│   └── Colls.java                 # Java 8 没有 Map.of 的替代品
├── z-graph-protocol/src/main/java/com/zifang/z/graph/protocol/
│   └── BoltConstants.java / BoltFrames.java / BoltMessageDecoder.java
├── z-graph-core/src/main/java/com/zifang/z/graph/core/
│   ├── CypherEngine.java          # 手写解析器，2,227 行
│   ├── CypherExecutor.java / InMemoryGraphStore.java / ReadOnlyGraphStore.java
│   ├── GraphVersionStore.java     # MVCC 主入口（beginWrite / commit / checkout / merge / garbageCollect …）
│   ├── TxBuffer.java / GraphDelta.java / GraphEntityVersion.java
│   ├── GraphCodec.java            # STORAGE_VERSION = 5
│   ├── GraphWriteTransaction.java / GraphCheckout.java
│   ├── GraphQueryService.java / GraphMetaService.java
│   ├── LegacyMigrator.java        # v4 → v5 迁移
│   └── storage/                   # 磁盘引擎 13 个类
│       ├── StorageEngine.java     # 门面
│       ├── VersionStore.java / PostingStore.java / PayloadCodec.java
│       ├── CommitObjectStore.java / RefStore.java / AncestryIndex.java
│       ├── RefViewGraphStore.java # 唯一读路径
│       ├── FixedRecordFile.java / AppendSegment.java / StoreHeader.java
│       └── NameDictionary.java / Visibility.java
├── z-graph-bolt-server/src/main/java/com/zifang/z/graph/bolt/
│   ├── BoltServer.java            # Netty，默认 7687
│   ├── BoltMessageHandler.java
│   ├── GraphControlServer.java    # JDK HttpServer，13 个 context，默认 8090
│   └── ZGraphServer.java / GraphControlServerMain.java  # 两个 main
└── z-graph-spring-boot-starter/src/main/java/com/zifang/z/graph/starter/
    ├── ZGraphAutoConfiguration.java
    ├── ZGraphProperties.java      # z.graph.enabled / port / data-dir
    ├── autoconfigure/GraphEmbeddedServerConfig.java
    └── host/GraphHostAutoConfiguration.java + GraphProxyController.java
```

`_doc/` 侧两个桶：`003_script/`（10 个文件：5 支 Bolt 探针脚本 `test_bolt_poc.py` / `test_bolt_raw.py` / `test_bolt_full.py` / `test_bolt_error.py` / `test_bolt_concurrent.py`、`test_http_control_plane.py`、`run_e2e.sh`、`run_t1_verify.sh`、`all-in-one-entrypoint.sh`、`deploy_maven_center.sh`、`verify_central.sh`）与 `005_testing/`（`01-test-report.md`）。
