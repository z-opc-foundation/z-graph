# z-graph

独立于 `z-opc` 的图数据库工程。当前工程以 NebulaGraph 的三层职责为参考：

- **Query 层**：`z-graph-core` 的 `CypherEngine`、`GraphQueryService`、`GraphCheckout`，负责 OpenCypher 子集解析、版本视图查询、聚合函数与管理 SHOW。
- **Storage 层**：`InMemoryGraphStore` 负责节点、边、Tag/EdgeType schema、标签/属性索引和遍历；`GraphVersionStore` 通过不可变快照提供 commit/branch/merge 语义，并支持可选的 `repository.bin` 原子持久化。
- **Meta 层**：`GraphMetaService`、`GraphCommit`、分支 head、祖先关系、三方 merge 冲突检测、`StaleHeadException` 并发控制共同组成轻量元数据服务。
- **协议层**：`z-graph-protocol` 和 `z-graph-bolt-server` 提供 Bolt 4.4 chunk/PackStream 子集及 Netty 服务端；`GraphControlServer` 提供独立的 Meta/Query HTTP 控制面。

控制面端点：`/health`、`/meta/branches`、`/meta/commits`、`/query?commit=...&cypher=...`。

## 已对齐 NebulaGraph 的能力

- **Tag / EdgeType schema**：声明属性名、数据类型与 `NOT NULL` 约束，存写前自动校验；schema 也会随 commit 进入快照并随仓库重新加载恢复。
- **DDL via Cypher**：`CREATE TAG` / `DROP TAG` / `CREATE EDGE` / `DROP EDGE` / `CREATE TAG INDEX ON <tag>.<prop>` / `CREATE EDGE INDEX ON <edge>.<prop>` / `DROP TAG INDEX` / `DROP EDGE INDEX` / `ALTER TAG / EDGE ADD|DROP (<field> ...)`。
- **EXPLAIN**：只读查询计划预览，标注 `IndexSeek` / `LabelScan` / `VarLenExpand` / `Aggregate` 等算子，便于调优索引。
- **DESCRIBE TAG / DESCRIBE EDGE / DESCRIBE GRAPH / SHOW STATS**：NebulaGraph 风格的管理命令。
- **CALL 内置过程**：`CALL db.version()` / `db.stats()` / `db.tags()` / `db.edges()` / `db.indexes()` / `db.branches()` / `db.commits()` / `db.head('<branch>')`，Bolt 客户端也可直接调用（绑定到仓库的 CypherEngine 才支持 git 元数据过程）。
- **索引下推**：单节点 `MATCH (n:L) WHERE n.prop = value` 在已建 `(L, prop)` 索引时直接走索引查找，省掉全标签扫描。
- **EXISTS / NOT EXISTS 子查询**：`MATCH (n) WHERE EXISTS { (n)-[]->() }` 用在外层 binding 上检查出/入边存在性。
- **反向边匹配**：`(a)<-[:TYPE]-(b)` 与 `(a:Label)<-[:TYPE]-(b:Label)` 都被 CypherEngine 识别，箭头方向决定 leftVar / rightVar 的角色。
- **ORDER BY / SKIP / LIMIT**：`MATCH ... RETURN ... ORDER BY <col> [ASC|DESC] SKIP <n> LIMIT <m>`，列名优先匹配 alias，再回落到 `var.prop` 和原始 binding。
- **WITH 子句**：`MATCH ... WITH <expr> AS <alias>, ... RETURN ...` 把当前 bindings 重投影为下游可用变量。
- **多语句**：`;` 分隔的多条 Cypher 顺序执行，结果拼接返回；字符串字面量内的 `;` 不会被切分。
- **Snapshot 导入 / 导出**：`GraphVersionStore.exportSnapshot(commitId, file)` 与 `importSnapshot(file, branch, author, message)`，用于备份 / 跨仓库迁移 commit 内容（含 schema / index）。
- **REBUILD INDEX**：`REBUILD TAG INDEX <tag>.<prop>` 对当前内存索引做手动 rebuild 钩子（当前实现同步空操作）。
- **SHOW TAGS / SHOW EDGES / SHOW INDEXES / SHOW TAG \<name\> / SHOW EDGE \<name\>**：NebulaGraph 风格的管理 Cypher。
- **变长路径匹配**：`(a)-[*min..max]->(b)` / `(a)-[:TYPE*min..max]->(b)`，节点 `{prop: value}` 过滤同时作用于端点，避免无关起点被 BFS 收录。
- **OPTIONAL MATCH**：模式未命中仍返回一行（左部变量为 null）。
- **聚合函数**：`count(*)` / `count(expr)` / `sum(expr)` / `avg(expr)` / `min(expr)` / `max(expr)`，按 RETURN 中的非聚合列自动 GROUP BY。
- **MERGE 节点 upsert**：标签 + 全部属性精确匹配，不存在则创建，存在则返回现有节点。
- **Bolt 4.4 协议实现**：HELLO / RUN / PULL / BEGIN / COMMIT / ROLLBACK / RESET / DISCARD / GOODBYE；handler 端到端测试覆盖 11 个用例，包含参数绑定、参数化失败、跨连接一致性。
- **Bolt RUN 参数绑定**：通过 `$param` 展开为 Cypher 字面量。
- **Bolt 4.4 BEGIN / RUN / PULL / COMMIT 事务**：会话内多次 RUN 复用同一事务视图；非事务写每次自动提交一个 commit。
- **不可变 commit 视图**：`checkout(commitId)` 切出的查询视图只可见该 commit，不被后续写入污染。
- **并发写事务**：分支 head 推进时抛 `StaleHeadException`，避免静默覆盖。

## Git 风格的版本化图

`GraphVersionStore` 把每一次成功 commit 都当作不可变快照，head 指针只指向某个 commit。常用动作：

```java
GraphVersionStore repository = new GraphVersionStore();
GraphWriteTransaction tx = repository.beginWrite("main");
tx.addNode("Person", Map.of("name", "Alice"));
GraphCommit base = tx.commit("alice", "add Alice");

repository.createBranch("feature", base.getId());
GraphWriteTransaction featureTx = repository.beginWrite("feature");
featureTx.addNode("Person", Map.of("name", "Bob"));
featureTx.commit("bob", "feature graph");

GraphMergeResult merge = repository.merge("main", "feature", "maintainer", "merge feature");
GraphCheckout old = repository.checkout(base.getId()); // 只可见 base 提交的内容
List<Map<String, Object>> rows = old.query("MATCH (n:Person) RETURN n.name AS name");
```

合并策略：三方 merge，base / ours / theirs 在同一节点或边上只有单侧发生变化的属性会被自动合并；同一字段两侧都修改会进入冲突列表且不推进 target head。

`CALL db.branches()` / `db.commits()` / `db.head('<branch>')` 让 Bolt / HTTP 客户端也能直接查询版本元数据，仓库绑定在 `CypherEngine(store, repository)` 上时启用。

## 与 z-opc 的边界

`z-graph` 已从 `z-opc` Maven reactor 中移除，`z-graph/pom.xml` 是独立 parent，可在本目录单独开发、构建和测试。当前没有跨模块共享实现；后续确实需要共享的通用能力，统一下沉到平级 `z-util`，不反向把图数据库重新接回 `z-opc`。

## 构建与测试

```bash
cd /Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-graph
mvn test
mvn -pl z-graph-core -am test

# Bolt 服务端开启可选文件持久化
mvn -pl z-graph-bolt-server exec:java \
  -Dexec.mainClass=com.zifang.z.graph.bolt.BoltServer \
  -Dexec.args=7687 \
  -Dexec.jvmArgs=-Dz.graph.dataDir=/tmp/z-graph-data
```

版本化图的最小用法：

```java
GraphVersionStore repository = new GraphVersionStore();
GraphWriteTransaction tx = repository.beginWrite("main");
tx.createTag(new TagSchema("Person", List.of(
        new TagSchema.Field("name", TagSchema.DataType.STRING, false))));
tx.addNode("Person", Map.of("name", "Alice"));
GraphCommit first = tx.commit("alice", "initial graph");

repository.createBranch("feature", first.getId());
GraphWriteTransaction feature = repository.beginWrite("feature");
feature.addNode("Person", Map.of("name", "Bob"));
GraphCommit second = feature.commit("bob", "feature graph");

GraphCheckout checkout = repository.checkout(second.getId());
checkout.query("CREATE TAG Person (name STRING NOT NULL, age INT)");
checkout.query("CREATE TAG INDEX ON Person.name");
checkout.query("ALTER TAG Person ADD (city STRING)");
checkout.query("MATCH (n:Person) WHERE n.name = 'Alice' RETURN n.city AS city");
checkout.query("MATCH (n:Person) RETURN n.name AS name");
checkout.query("SHOW TAGS");
checkout.query("MATCH (a:Person)-[:KNOWS*1..3]->(b:Person) RETURN b.name AS friend");
checkout.query("MATCH (n:Person) RETURN n.city AS city, count(n) AS cnt");
```

## 前端控制台 (z-graph-console)

`z-graph-console/` 是一个 React + Vite 单页应用，对应 `GraphControlServer` 暴露的 HTTP API。它提供：

- **总览**：节点/边/分支/提交 KPI + 最近 5 次 commit 列表 + 示例查询。
- **分支**：每个分支的 head commit + 该分支的提交数与节点/边统计。
- **提交历史**：按分支筛选、按 message / author / commit id 搜索，支持点击 commit id 复制。
- **Cypher 查询**：在线编辑器 + 预设示例（节点与边、变长路径、反向关系、聚合、CALL db.*、SHOW 等）；支持"分支 head / 指定 commit"两种绑定模式；执行结果表格化展示。
- **Schema**：运行 `CALL db.tags()` / `db.edges()` / `db.indexes()`，展示当前 head 上的 schema 与索引。

开发：

```bash
cd z-graph-console
npm install
npm run dev   # http://localhost:5173, Vite 把 /api/* 代理到 8090
```

打包：

```bash
npm run build   # 产物 dist/,由 frontend.Dockerfile 拷贝进 Nginx 镜像
```

通过环境变量切换 API 地址：`VITE_API_BASE=http://z-graph-server:8090` 或在运行时于界面侧栏填入。

## 部署 — 三种镜像与分布式拓扑

GitHub Actions 在 `.github/workflows/build-images.yml` 自动构建并把以下三个镜像推到 GHCR：

| 镜像 | 用途 | 主要内容 |
| --- | --- | --- |
| `ghcr.io/z-opc-foundation/z-graph-server` | 服务端独立部署 | Bolt 4.4 + HTTP 控制面,JRE 多阶段构建,非 root 用户 |
| `ghcr.io/z-opc-foundation/z-graph-frontend` | 前端独立部署 | Nginx 1.27 + React 静态产物,`/api` 反代到 server,`${Z_GRAPH_API_UPSTREAM}` 可注入 |
| `ghcr.io/z-opc-foundation/z-graph-all-in-one` | 单容器 demo / 内网 | 一个 JRE + Nginx,同进程拉起 server 与前端,适合单机 |

触发策略：

- push 到 `main` → 跑 Java 测试 + 构建 + 推送三个镜像到 GHCR(latest + commit sha tag)。
- push tag `v*` → 推送版本化镜像。
- PR / `workflow_dispatch` → 只跑构建不推送。

### docker-compose 分布式部署

```bash
# 服务 + 前端分离(默认 profile=distributed)
docker compose -f deploy/docker/docker-compose.yml up -d z-graph-server z-graph-frontend
# 访问 http://localhost:8080 看前端,8080 反代到 8090 控制面

# 单容器一体机
docker compose -f deploy/docker/docker-compose.yml --profile all-in-one up -d z-graph-all-in-one
# 访问 http://localhost:8081

# 多副本展示(分布式部署拓扑)
docker compose -f deploy/docker/docker-compose.yml --profile cluster up -d
```

`docker-compose.yml` 内置四个 profile:`distributed`(默认 server + frontend)、`all-in-one`、`cluster`(多 server 节点 + 前端)、`frontend`(仅前端)。

### Kubernetes 部署

`deploy/kubernetes/z-graph.yaml` 包含完整的 Namespace + ConfigMap + PVC + Deployment + Service 资源：

```bash
kubectl apply -f deploy/kubernetes/z-graph.yaml
kubectl port-forward -n z-graph svc/z-graph-frontend 8080:80
```

文件里附带了注释掉的 Ingress 示例，可按域名（如 `z-graph.example.com`）暴露到集群外。当前 `z-graph-server` 是单实例内存存储，多副本属于 topology 演示；生产多副本方案需要外部共享存储（NFS / CSI）替换 PVC。

### 持久化与 CORS

- 数据持久化：服务端镜像把 `/var/lib/z-graph` 暴露为 volume，仓库通过 `repository.bin` 原子写入；容器重启后自动恢复 commit / branch / schema。
- CORS：`GraphControlServer` 默认 `Access-Control-Allow-Origin: *`；生产部署通过环境变量 `Z_GRAPH_CORS_ALLOWED_ORIGINS=https://your.domain` 收紧。
- 健康检查：`/health` 暴露 head commit id 与节点 / 边计数，K8s readiness / liveness 与 docker-compose healthcheck 都使用它。

## 开源参考说明

架构分层和协议兼容目标参考 [NebulaGraph](https://github.com/vesoft-inc/nebula)。NebulaGraph 为 Apache License 2.0 项目；本工程仅采用其公开架构思想和协议资料，不复制其未授权代码，并保留上游项目链接及许可证边界。

