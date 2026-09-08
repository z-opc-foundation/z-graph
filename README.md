# z-graph

独立于 `z-opc` 的图数据库工程。当前工程以 NebulaGraph 的三层职责为参考：

- **Query 层**：`z-graph-core` 的 `CypherEngine`、`GraphQueryService`、`GraphCheckout`，负责 OpenCypher 子集解析、版本视图查询、聚合函数与管理 SHOW。
- **Storage 层**：`InMemoryGraphStore` 负责节点、边、Tag/EdgeType schema、标签/属性索引和遍历；`GraphVersionStore` 通过不可变快照提供 commit/branch/merge 语义，并支持可选的 `repository.bin` 原子持久化。
- **Meta 层**：`GraphMetaService`、`GraphCommit`、分支 head、祖先关系、三方 merge 冲突检测、`StaleHeadException` 并发控制共同组成轻量元数据服务。
- **协议层**：`z-graph-protocol` 和 `z-graph-bolt-server` 提供 Bolt 4.4 chunk/PackStream 子集及 Netty 服务端；`GraphControlServer` 提供独立的 Meta/Query HTTP 控制面。

## HTTP API 端点（14 个）

| 端点 | 方法 | 功能 |
|------|------|------|
| `/health` | GET | 健康检查（head、节点数、边数） |
| `/query` | GET/POST | 执行单条 Cypher 查询 |
| `/query/batch` | POST | 批量执行多条 Cypher 语句 |
| `/query/explain` | POST | 返回查询执行计划（操作步骤 + 复杂度估算） |
| `/meta/branches` | GET | 分支列表 |
| `/meta/commits` | GET | 提交历史 |
| `/meta/schema` | GET | Schema 信息（TAG/EDGE/INDEX） |
| `/meta/stats` | GET | 统计摘要（节点数、边数、标签分布） |
| `/meta/metrics` | GET | 运行指标（请求数、错误率、JVM 内存、uptime） |
| `/meta/logs` | GET | 请求日志（环形缓冲 500 条，支持 method/status/path/requestId 过滤） |
| `/meta/export` | GET | 导出图数据 JSON |
| `/meta/import` | POST | 导入节点数据 |
| `/options` | OPTIONS | CORS 预检 |

所有查询响应包含 `X-Response-Time` 和 `X-Request-ID` 头。支持 GZIP 响应压缩（>256 字节自动压缩）。

## 安全特性

- **API Token 认证**：环境变量 `Z_GRAPH_API_TOKEN` 启用可选的 Bearer Token 认证（`Authorization: Bearer <token>` 或 `?token=<token>`）
- **速率限制**：`Z_GRAPH_RATE_LIMIT` 设置每 IP 每分钟最大请求数（滑动窗口），超限返回 429 + `Retry-After`
- **安全响应头**：`X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`X-XSS-Protection`、`Referrer-Policy`
- **CORS**：默认 `*`，可通过 `Z_GRAPH_CORS_ALLOWED_ORIGINS` 收紧
- **请求追踪**：每个请求自动生成 `X-Request-ID`，支持客户端传入复用
- **GZIP 压缩**：服务端自动检测 `Accept-Encoding: gzip`，>256 字节时压缩响应
- **优雅关闭**：收到 SIGTERM/SIGINT 时等待 5 秒完成现有请求
- **请求日志**：nginx 风格 access log + 环形缓冲区（最近 500 条）

## 已对齐 NebulaGraph 的能力

- **Tag / EdgeType schema**：声明属性名、数据类型与 `NOT NULL` 约束，存写前自动校验。
- **DDL via Cypher**：`CREATE TAG` / `DROP TAG` / `CREATE EDGE` / `DROP EDGE` / `CREATE TAG INDEX` / `CREATE EDGE INDEX` / `ALTER TAG / EDGE ADD|DROP`。
- **EXPLAIN / DESCRIBE / SHOW STATS**：查询计划预览、Schema 描述、统计信息。
- **CALL 内置过程**：`db.version()` / `db.stats()` / `db.tags()` / `db.edges()` / `db.indexes()` / `db.branches()` / `db.commits()` / `db.head()`。
- **索引下推**：单节点 `MATCH (n:L) WHERE n.prop = value` 在已建索引时走索引查找。
- **反向边匹配**：`(a)<-[:TYPE]-(b)` 与 `(a:Label)<-[:TYPE]-(b:Label)`。
- **ORDER BY / SKIP / LIMIT / WITH**：完整的查询子句支持。
- **多语句**：`;` 分隔的多条 Cypher 顺序执行。
- **Snapshot 导入 / 导出**：`GraphVersionStore.exportSnapshot()` / `importSnapshot()`。
- **变长路径匹配**：`(a)-[*min..max]->(b)` / `(a)-[:TYPE*min..max]->(b)`。
- **OPTIONAL MATCH / EXISTS / NOT EXISTS**：模式匹配和子查询。
- **聚合函数**：`count` / `sum` / `avg` / `min` / `max`，自动 GROUP BY。
- **MERGE 节点 upsert**：标签 + 属性精确匹配。
- **Bolt 4.4 协议**：HELLO / RUN / PULL / BEGIN / COMMIT / ROLLBACK / RESET / DISCARD / GOODBYE，参数绑定，事务视图。
- **不可变 commit 视图**：`checkout(commitId)` 只可见该 commit。
- **并发写事务**：分支 head 推进时抛 `StaleHeadException`。

## Git 风格的版本化图

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
GraphCheckout old = repository.checkout(base.getId());
List<Map<String, Object>> rows = old.query("MATCH (n:Person) RETURN n.name AS name");
```

合并策略：三方 merge，base / ours / theirs 在同一节点或边上只有单侧发生变化的属性被自动合并；同一字段两侧都修改进入冲突列表。

## 构建与测试

```bash
mvn test                              # 运行全部 148 个单元测试
mvn -pl z-graph-core -am test         # 仅运行核心模块测试

# 启动统一服务（Bolt + HTTP 控制面）
mvn -pl z-graph-bolt-server exec:java \
  -Dexec.mainClass=com.zifang.z.graph.bolt.ZGraphServer \
  -Dexec.jvmArgs="-Dz.graph.dataDir=/tmp/z-graph-data"
```

## 前端控制台（8 个页面）

`z-graph-console/` 是 React + Vite 单页应用：

| 页面 | 功能 |
|------|------|
| 总览 | KPI 卡片 + 实时指标 SVG 图表（请求速率 + JVM 内存） + Schema 概览 + 快捷操作 |
| 图视图 | SVG 力导向布局可视化（节点着色、边箭头、点击交互、图例） |
| 分支 | 分支列表与管理 |
| 提交历史 | 完整 commit 记录 |
| Cypher 查询 | 语法高亮 + 自动补全（50+ 建议） + 查询计时 + 历史 + CSV/JSON 导出 |
| Schema | DDL 快捷操作（12 个按钮）+ Schema 浏览 |
| 请求日志 | 实时自动刷新 + 过滤器（method/status/path/requestId）+ 详情面板 |
| API 文档 | 14 个端点交互式文档（参数表 + 交互测试） |

开发：`cd z-graph-console && npm install && npm run dev`

打包：`npm run build`（产物 `dist/`，由 `frontend.Dockerfile` 拷贝进 Nginx）

### Cypher 编辑器特性

- **语法高亮**：关键字（紫色）、字符串（绿色）、数字（黄色）、注释（灰色）、内置过程（青色）、操作符（红色）
- **自动补全**：输入 1+ 字符时弹出建议（Tab/Enter 选择，↑↓ 导航，Esc 关闭）
- **建议分类**：keyword / builtin / function / pattern / ddl，带中文描述
- **快捷键**：Ctrl+Enter 执行查询

## 部署 — 三种镜像

| 镜像 | 用途 | 内容 |
|------|------|------|
| `z-graph-server` | 服务端独立部署 | Bolt 4.4 + HTTP 控制面, JRE 多阶段构建, 非 root |
| `z-graph-frontend` | 前端独立部署 | Nginx + React, `/api` 反代到 server |
| `z-graph-all-in-one` | 单容器 demo | JRE + Nginx, 同进程 server + 前端 |

### Docker Compose

```bash
# 分布式（server + frontend）
docker compose -f deploy/docker/docker-compose.yml --profile distributed up -d

# 单容器一体机
docker compose -f deploy/docker/docker-compose.yml --profile all-in-one up -d

# 多副本拓扑
docker compose -f deploy/docker/docker-compose.yml --profile cluster up -d
```

### Docker 生产配置

- **资源限制**：server 2 CPU / 1GB 内存，frontend 0.5 CPU / 128MB
- **JVM 调优**：`JAVA_OPTS` 环境变量支持（`-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC`）
- **日志轮转**：json-file 驱动，10MB × 3 文件
- **健康检查**：10s 间隔 wget `/health`
- **优雅关闭**：SIGTERM 等待 5 秒完成现有请求

### Nginx 优化

- **GZIP 压缩**：JS/CSS/JSON/SVG 压缩比 60-73%
- **静态资源缓存**：`/assets/*` 1 年 `immutable` 缓存
- **安全头**：X-Content-Type-Options, X-Frame-Options, X-XSS-Protection, Referrer-Policy

### Kubernetes

```bash
kubectl apply -f deploy/kubernetes/z-graph.yaml
kubectl port-forward -n z-graph svc/z-graph-frontend 8080:80
```

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `Z_GRAPH_BOLT_PORT` | 7687 | Bolt 协议端口 |
| `Z_GRAPH_HTTP_PORT` | 8090 | HTTP 控制面端口 |
| `Z_GRAPH_DATA_DIR` | /var/lib/z-graph | 数据持久化目录 |
| `Z_GRAPH_CORS_ALLOWED_ORIGINS` | * | CORS 允许的 origin |
| `Z_GRAPH_API_TOKEN` | (无) | API Token 认证密钥 |
| `Z_GRAPH_RATE_LIMIT` | 0 | 每 IP 每分钟请求上限（0=不限） |
| `JAVA_OPTS` | (空) | JVM 参数注入 |

## Git 提交历史

```
bceed58 feat: Nginx GZIP + 静态缓存 + Dashboard 实时图表 + Cypher 自动补全
95d86b7 feat: X-Request-ID 追踪 + GZIP 响应压缩 + 优雅关闭
e604058 feat: 请求日志环形缓冲 + 日志查看器 + 查询结果导出
1abf198 feat: 侧边栏实时服务器指标（运行时间、请求数、错误率、JVM 内存）
569b924 docs: README 全面更新 — 覆盖 12 个 API + 安全 + 前端 + 部署
28680cb feat: 交互式 API 文档页面
8aa3aa9 feat: 查询执行计划端点 /query/explain
bf9a85a feat: Cypher 语法高亮编辑器
bb1b7d5 ops: 运行指标监控端点 /meta/metrics
8c0a302 security: API Token 认证 + 速率限制 + 安全响应头
7eb2b2a feat: 图数据 SVG 可视化（力导向布局）
90a1d75 ops: Docker 部署生产化 — 资源限制 + JVM 调优 + 日志轮转
6c24102 feat: HTTP 请求日志 + 异常捕获 + 生产级可观测性
c30f4f9 feat: 批量查询 + 导出导入 + 响应计时 + 查询历史
d150363 feat: HTTP API 增强 + React 控制台升级 + all-in-one 镜像修复
fadccf4 fix: Docker 部署调优 — 修复 Maven 依赖解析与 HTTP 控制面写支持
d25ae23 feat: React 控制台 + 三种 Docker 镜像 + GHCR 自动化 + 分布式 K8s
```

## 开源参考说明

架构分层和协议兼容目标参考 [NebulaGraph](https://github.com/vesoft-inc/nebula)。NebulaGraph 为 Apache License 2.0 项目；本工程仅采用其公开架构思想和协议资料，不复制其未授权代码，并保留上游项目链接及许可证边界。
