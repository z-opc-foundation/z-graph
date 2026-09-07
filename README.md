# z-graph

独立于 `z-opc` 的图数据库工程。当前工程以 NebulaGraph 的三层职责为参考：

- **Query 层**：`z-graph-core` 的 `CypherEngine`、`GraphQueryService`、`GraphCheckout`，负责 OpenCypher 子集解析和版本视图查询。
- **Storage 层**：`InMemoryGraphStore` 负责节点、边、标签/属性索引和遍历；`GraphVersionStore` 通过不可变快照提供 commit/branch/merge 语义，并支持可选的 `repository.bin` 原子持久化。
- **Meta 层**：`GraphMetaService`、`GraphCommit`、分支 head、祖先关系和三方 merge 冲突检测组成轻量元数据服务。
- **协议层**：`z-graph-protocol` 和 `z-graph-bolt-server` 提供 Bolt 4.4 chunk/PackStream 子集及 Netty 服务端；`GraphControlServer` 提供独立的 Meta/Query HTTP 控制面。

控制面端点：`/health`、`/meta/branches`、`/meta/commits`、`/query?commit=...&cypher=...`。

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
tx.addNode("Person", Map.of("name", "Alice"));
GraphCommit first = tx.commit("alice", "initial graph");

repository.createBranch("feature", first.getId());
GraphWriteTransaction feature = repository.beginWrite("feature");
feature.addNode("Person", Map.of("name", "Bob"));
GraphCommit second = feature.commit("bob", "feature graph");

GraphCheckout checkout = repository.checkout(second.getId());
checkout.query("MATCH (n:Person) RETURN n.name AS name");
```

## 开源参考说明

架构分层和协议兼容目标参考 [NebulaGraph](https://github.com/vesoft-inc/nebula)。NebulaGraph 为 Apache License 2.0 项目；本工程仅采用其公开架构思想和协议资料，不复制其未授权代码，并保留上游项目链接及许可证边界。
