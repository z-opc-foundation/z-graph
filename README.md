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

## 开源参考说明

架构分层和协议兼容目标参考 [NebulaGraph](https://github.com/vesoft-inc/nebula)。NebulaGraph 为 Apache License 2.0 项目；本工程仅采用其公开架构思想和协议资料，不复制其未授权代码，并保留上游项目链接及许可证边界。

