# z-graph

> 独立于 `z-opc` 的图数据库工程 — NebulaGraph 风格的分层架构 + Git 版本化 + OpenCypher 查询 + Bolt 4.4 协议 + 生产级 HTTP 控制面

[![Tests](https://img.shields.io/badge/tests-148%20passing-brightgreen)]()
[![Java](https://img.shields.io/badge/Java-17-orange)]()
[![Maven](https://img.shields.io/badge/Maven-3.9+-blue)]()
[![Docker](https://img.shields.io/badge/Docker-ready-blue)]()
[![License](https://img.shields.io/badge/license-Apache%202.0-green)]()

---

## 目录

- [特性一览](#特性一览)
- [架构设计](#架构设计)
- [快速开始](#快速开始)
- [HTTP API 参考](#http-api-参考)
- [Cypher 查询语言](#cypher-查询语言)
- [前端控制台](#前端控制台)
- [安全特性](#安全特性)
- [生产部署](#生产部署)
- [环境变量](#环境变量)
- [性能基准](#性能基准)
- [Git 版本化图模型](#git-版本化图模型)
- [可观测性](#可观测性)
- [开发指南](#开发指南)
- [故障排查](#故障排查)
- [开源参考](#开源参考)

---

## 特性一览

### 核心能力
- 🗂️ **OpenCypher 子集**：MATCH/RETURN/CREATE/MERGE/DELETE/SET/WHERE/ORDER BY/LIMIT/SKIP/WITH/UNWIND/OPTIONAL MATCH/聚合
- 🏷️ **Tag / EdgeType Schema**：声明式属性、类型校验、NOT NULL 约束
- 🌳 **Git 风格版本化**：分支、commit、merge、回滚、不可变快照
- 🔍 **索引下推**：标签属性索引加速查找
- 🔗 **变长路径匹配**：`(a)-[*1..3]->(b)` 任意跳数
- 🔄 **Bolt 4.4 协议**：HELLO/RUN/PULL/BEGIN/COMMIT/ROLLBACK 等完整支持
- 🛡️ **并发控制**：写事务冲突检测（StaleHeadException）

### HTTP 控制面（14 个端点）
- 健康检查、Cypher 查询、批量执行、查询计划
- 分支、提交、Schema、统计、指标、日志
- 数据导入导出、CORS 预检

### 生产特性
- 🔐 **API Token 认证**：Bearer Token / Query 参数
- 🚦 **速率限制**：滑动窗口 per-IP
- 🔒 **安全响应头**：X-Content-Type-Options、X-Frame-Options、X-XSS-Protection
- 🆔 **请求追踪 ID**：X-Request-ID 自动生成/复用
- 📦 **GZIP 压缩**：响应自动压缩 60-73%
- 🛑 **优雅关闭**：SIGTERM 等待 5 秒完成请求
- 📊 **环形日志缓冲**：最近 500 条请求可查询

### 前端控制台（8 个页面）
- 总览、图视图、分支、提交历史
- Cypher 查询（语法高亮 + 自动补全 + 导出）
- Schema、请求日志、API 文档

---

## 架构设计

### 分层架构

```
┌─────────────────────────────────────────────────────────────┐
│                      客户端层 (Clients)                      │
│  ┌────────────┐  ┌────────────┐  ┌────────────────────┐   │
│  │  Web UI    │  │  Bolt 驱动 │  │  HTTP/REST 客户端  │   │
│  │  (React)   │  │  (Neo4j 等)│  │  (curl/SDK)        │   │
│  └─────┬──────┘  └──────┬─────┘  └──────────┬─────────┘   │
└────────┼────────────────┼──────────────────┼──────────────┘
         │                │                  │
         ▼                ▼                  ▼
┌─────────────────────────────────────────────────────────────┐
│                       协议层 (Protocol)                     │
│  ┌──────────────────────┐  ┌────────────────────────────┐   │
│  │  z-graph-protocol    │  │  GraphControlServer        │   │
│  │  Bolt 4.4 PackStream │  │  HTTP API (14 端点)        │   │
│  │  + Netty 服务端      │  │  + 安全/限流/GZIP/日志     │   │
│  └──────────┬───────────┘  └────────────┬───────────────┘   │
└─────────────┼─────────────────────────────┼──────────────────┘
              │                             │
              ▼                             ▼
┌─────────────────────────────────────────────────────────────┐
│                     服务层 (Services)                        │
│  ┌─────────────────┐  ┌─────────────────┐  ┌────────────┐   │
│  │  CypherEngine   │  │  GraphQuery-    │  │  GraphMeta-│   │
│  │  OpenCypher     │  │  Service        │  │  Service   │   │
│  │  解析/执行/计划 │  │  写事务/快照   │  │  head/分支 │   │
│  └────────┬────────┘  └────────┬────────┘  └─────┬──────┘   │
└───────────┼────────────────────┼────────────────┼──────────┘
            │                    │                │
            ▼                    ▼                ▼
┌─────────────────────────────────────────────────────────────┐
│                      存储层 (Storage)                        │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              InMemoryGraphStore                      │   │
│  │  节点 + 边 + Tag/EdgeType schema + 索引 + 遍历      │   │
│  └──────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              GraphVersionStore                       │   │
│  │  不可变快照 + commit/branch/merge + 可选持久化       │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 模块结构

```
z-graph/
├── z-graph-api/                # 公共 API（GraphCommit 等）
├── z-graph-core/               # 核心引擎（Cypher/VersionStore/MetaService）
├── z-graph-protocol/           # Bolt 4.4 协议（PackStream 编解码）
├── z-graph-bolt-server/        # 服务端（Netty Bolt + HTTP 控制面）
├── z-graph-spring-boot-starter/# Spring Boot 自动装配（可选）
├── z-graph-console/            # React 前端控制台
├── deploy/
│   ├── docker/                 # Dockerfile + docker-compose
│   ├── kubernetes/             # K8s manifests
│   └── nginx/                  # Nginx 配置模板
└── .github/workflows/          # GitHub Actions CI/CD
```

---

## 快速开始

### 方式 1：本地 Maven 运行

```bash
# 克隆仓库
git clone https://github.com/z-opc-foundation/z-graph.git
cd z-graph

# 构建（跳过测试约 30 秒）
mvn package -DskipTests

# 启动服务
mvn -pl z-graph-bolt-server exec:java \
  -Dexec.mainClass=com.zifang.z.graph.bolt.ZGraphServer \
  -Dexec.jvmArgs="-Dz.graph.dataDir=/tmp/z-graph-data"

# 验证
curl http://localhost:8090/health
# {"status":"UP","head":"...","nodeCount":0,"edgeCount":0}
```

### 方式 2：Docker Compose（推荐）

```bash
# 启动分布式部署（server + frontend）
docker compose -f deploy/docker/docker-compose.yml --profile distributed up -d

# 访问控制台
open http://localhost:3333

# 查看日志
docker compose -f deploy/docker/docker-compose.yml logs -f
```

### 方式 3：单容器 All-in-One

```bash
docker compose -f deploy/docker/docker-compose.yml --profile all-in-one up -d
# 访问 http://localhost:3000（前端 + API + Bolt 同端口）
```

### 第一个查询

```bash
# 创建节点
curl -X POST http://localhost:8090/query \
  -H 'Content-Type: application/json' \
  -d '{"cypher":"CREATE (n:Person {name: \"Alice\", age: 30}) RETURN n"}'

# 查询节点
curl -X POST http://localhost:8090/query \
  -H 'Content-Type: application/json' \
  -d '{"cypher":"MATCH (n:Person) RETURN n.name AS name, n.age AS age"}'

# 创建关系
curl -X POST http://localhost:8090/query \
  -H 'Content-Type: application/json' \
  -d '{"cypher":"MATCH (a:Person {name: \"Alice\"}), (b:Person {name: \"Bob\"}) CREATE (a)-[:KNOWS]->(b)"}'
```

---

## HTTP API 参考

### 通用说明

所有响应包含：
- `Content-Type: application/json; charset=utf-8`
- `X-Response-Time: <毫秒>ms`
- `X-Request-ID: <16字符>`
- 支持 `Accept-Encoding: gzip` 自动压缩（>256 字节）

写查询会在指定分支产生新 commit，返回值包含 head 推进信息。

### 端点清单

| 端点 | 方法 | 功能 |
|------|------|------|
| `/health` | GET | 健康检查 |
| `/query` | GET/POST | 执行 Cypher |
| `/query/batch` | POST | 批量执行 |
| `/query/explain` | POST | 查询计划 |
| `/meta/branches` | GET | 分支列表 |
| `/meta/commits` | GET | 提交历史 |
| `/meta/schema` | GET | Schema 信息 |
| `/meta/stats` | GET | 统计摘要 |
| `/meta/metrics` | GET | 运行指标 |
| `/meta/logs` | GET | 请求日志 |
| `/meta/export` | GET | 导出图数据 |
| `/meta/import` | POST | 导入节点 |
| `/options` | OPTIONS | CORS 预检 |

### 示例：执行查询

```bash
# POST /query（推荐）
curl -X POST http://localhost:8090/query \
  -H 'Content-Type: application/json' \
  -d '{
    "cypher": "MATCH (n:Person) WHERE n.age > 20 RETURN n.name AS name, n.age AS age ORDER BY age DESC LIMIT 10",
    "branch": "main"
  }'

# 返回
[
  {"name": "Alice", "age": 30},
  {"name": "Carol", "age": 28}
]
```

### 示例：批量执行

```bash
curl -X POST http://localhost:8090/query/batch \
  -H 'Content-Type: application/json' \
  -d '{
    "statements": [
      {"cypher": "MATCH (n:Person) RETURN count(n) AS cnt"},
      {"cypher": "CALL db.branches()"}
    ]
  }'

# 返回
{
  "results": [
    {"rows": [{"cnt": 3}], "statementIndex": 0},
    {"rows": [{"Name": "main", "Head": "..."}], "statementIndex": 1}
  ],
  "elapsedMs": 12
}
```

### 示例：查询计划

```bash
curl -X POST http://localhost:8090/query/explain \
  -H 'Content-Type: application/json' \
  -d '{"cypher": "MATCH (n:Person) WHERE n.age > 20 RETURN n"}'

# 返回
{
  "plan": {
    "queryType": "READ",
    "steps": [
      {"operation": "Scan", "description": "遍历节点和边"},
      {"operation": "Filter", "description": "过滤不满足条件的记录"},
      {"operation": "Project", "description": "投影返回字段"}
    ],
    "estimatedComplexity": "LOW"
  }
}
```

### 示例：运行指标

```bash
curl http://localhost:8090/meta/metrics

# 返回
{
  "uptimeMs": 3600000,
  "uptimeFormatted": "1h 0m 0s",
  "totalRequests": 1234,
  "errorResponses": 5,
  "errorRate": "0.41%",
  "rateLimitedRequests": 0,
  "authFailures": 0,
  "jvmMemory": {
    "maxBytes": 1073741824,
    "totalBytes": 268435456,
    "usedBytes": 134217728,
    "freeBytes": 134217728
  },
  "availableProcessors": 8
}
```

---

## Cypher 查询语言

### 完整支持清单

| 类别 | 子句/特性 |
|------|----------|
| **读写** | MATCH / CREATE / MERGE / DELETE / DETACH DELETE / SET / REMOVE |
| **过滤** | WHERE (AND/OR/NOT/IN/CONTAINS/STARTS WITH/ENDS WITH/IS NULL/EXISTS) |
| **返回** | RETURN / WITH / DISTINCT / ORDER BY (ASC/DESC) / SKIP / LIMIT |
| **聚合** | count / sum / avg / min / max / collect（自动 GROUP BY） |
| **路径** | 任意方向 / 反向匹配 / 变长 `*min..max` / 类型过滤 `[:TYPE]` |
| **高级** | OPTIONAL MATCH / UNWIND / 多语句 `;` / 参数绑定 |
| **元数据** | SHOW TAGS / SHOW EDGES / SHOW INDEXES / CALL db.* |
| **DDL** | CREATE TAG / DROP TAG / CREATE EDGE / DROP EDGE / CREATE INDEX / ALTER |

### 示例

```cypher
-- 创建 Tag Schema
CREATE TAG Person (name STRING, age INT NOT NULL);

-- 创建索引
CREATE TAG INDEX idx_person_name ON Person(name);

-- 插入数据
CREATE (n:Person {name: 'Alice', age: 30});
CREATE (n:Person {name: 'Bob', age: 25});

-- 创建关系
MATCH (a:Person {name: 'Alice'}), (b:Person {name: 'Bob'})
CREATE (a)-[:KNOWS {since: 2020}]->(b);

-- 复杂查询
MATCH (a:Person)-[:KNOWS]->(b:Person)
WHERE a.age > 20 AND b.name CONTAINS 'o'
RETURN a.name AS friend, b.name AS buddy, b.age AS age
ORDER BY age DESC LIMIT 10;

-- 变长路径
MATCH (a:Person {name: 'Alice'})-[:KNOWS*1..3]->(b:Person)
RETURN DISTINCT b.name AS friend;

-- 聚合
MATCH (n:Person)
RETURN n.age AS age, count(n) AS total, collect(n.name) AS names
ORDER BY age;

-- 内置过程
CALL db.version();
CALL db.branches();
CALL db.head('main');
CALL db.stats();

-- 元数据查询
SHOW TAGS;
SHOW EDGES;
SHOW INDEXES;
```

---

## 前端控制台

### 8 个页面

| 页面 | 功能 |
|------|------|
| **总览** | KPI 卡片 + 实时 SVG 图表（请求速率 + JVM 内存） + Schema 概览 + 快捷操作 |
| **图视图** | SVG 力导向布局可视化（节点着色、边箭头、点击交互、图例） |
| **分支** | 分支列表与管理 |
| **提交历史** | 完整 commit 记录 |
| **Cypher 查询** | 语法高亮 + 自动补全（50+ 建议） + 查询计时 + 历史 + CSV/JSON 导出 |
| **Schema** | DDL 快捷操作（12 个按钮）+ Schema 浏览 |
| **请求日志** | 实时自动刷新 + 过滤器（method/status/path/requestId）+ 详情面板 |
| **API 文档** | 14 个端点交互式文档（参数表 + 交互测试） |

### Cypher 编辑器

- **语法高亮**：关键字（紫色）、字符串（绿色）、数字（黄色）、注释（灰色）、内置过程（青色）、操作符（红色）
- **自动补全**：输入 1+ 字符时弹出建议
  - `Tab` / `Enter` 选择
  - `↑` / `↓` 导航
  - `Esc` 关闭
- **建议分类**：keyword / builtin / function / pattern / ddl，带中文描述
- **快捷键**：`Ctrl+Enter` 执行查询

### 开发与构建

```bash
cd z-graph-console
npm install
npm run dev        # 开发服务器 (http://localhost:5173)
npm run build      # 生产构建 (产物 dist/)
```

---

## 安全特性

| 特性 | 实现 |
|------|------|
| **API Token 认证** | 环境变量 `Z_GRAPH_API_TOKEN` 启用，支持 `Authorization: Bearer <token>` 或 `?token=<token>` |
| **速率限制** | `Z_GRAPH_RATE_LIMIT` 设置每 IP 每分钟最大请求数（滑动窗口），超限返回 429 + `Retry-After: 60` |
| **安全响应头** | `X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`X-XSS-Protection: 1; mode=block`、`Referrer-Policy: strict-origin-when-cross-origin` |
| **CORS** | 默认 `*`，可通过 `Z_GRAPH_CORS_ALLOWED_ORIGINS` 收紧（如 `https://app.example.com,https://admin.example.com`） |
| **请求追踪** | 每个请求自动生成 16 字符 `X-Request-ID`，支持客户端传入复用（分布式追踪） |
| **GZIP 压缩** | 服务端自动检测 `Accept-Encoding: gzip`，>256 字节时压缩 |
| **优雅关闭** | 收到 SIGTERM/SIGINT 时等待 5 秒完成现有请求，避免客户端断连 |
| **请求日志** | nginx 风格 access log + 环形缓冲区（最近 500 条） |

### 启用 Token 认证

```bash
export Z_GRAPH_API_TOKEN="your-secret-token-here"
export Z_GRAPH_RATE_LIMIT=600  # 600 req/min/IP

# 客户端调用
curl -H "Authorization: Bearer your-secret-token-here" http://localhost:8090/health
# 或
curl "http://localhost:8090/health?token=your-secret-token-here"
```

---

## 生产部署

### 三种 Docker 镜像

| 镜像 | 用途 | 内容 |
|------|------|------|
| `z-graph-server` | 服务端独立部署 | Bolt 4.4 + HTTP 控制面，JRE 多阶段构建，非 root 用户 |
| `z-graph-frontend` | 前端独立部署 | Nginx + React，`/api` 反代到 server |
| `z-graph-all-in-one` | 单容器 demo | JRE + Nginx，同进程 server + 前端 |

### Docker Compose profiles

```bash
# 分布式（server + frontend）
docker compose -f deploy/docker/docker-compose.yml --profile distributed up -d

# 单容器一体机
docker compose -f deploy/docker/docker-compose.yml --profile all-in-one up -d

# 多副本拓扑示例
docker compose -f deploy/docker/docker-compose.yml --profile cluster up -d
```

### Docker 生产配置

| 配置项 | 值 |
|--------|-----|
| **资源限制** | server 2 CPU / 1GB 内存，frontend 0.5 CPU / 128MB |
| **JVM 调优** | `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:MaxGCPauseMillis=200` |
| **日志轮转** | json-file 驱动，10MB × 3 文件 |
| **健康检查** | 10s 间隔 wget `/health`，15s 启动宽限期 |
| **优雅关闭** | SIGTERM 等待 5 秒 |

### Nginx 优化

| 优化 | 效果 |
|------|------|
| **GZIP 压缩** | JS 194KB → 63KB (68%)，CSS 8.5KB → 2.3KB (73%) |
| **静态资源缓存** | `/assets/*` 1 年 `immutable` 缓存 |
| **HTML no-cache** | `index.html` 始终获取最新版本 |
| **安全响应头** | X-Content-Type-Options、X-Frame-Options 等 |

### Kubernetes

```bash
kubectl apply -f deploy/kubernetes/z-graph.yaml
kubectl port-forward -n z-graph svc/z-graph-frontend 8080:80
```

---

## 环境变量

### 服务端环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `Z_GRAPH_BOLT_PORT` | 7687 | Bolt 协议端口 |
| `Z_GRAPH_HTTP_PORT` | 8090 | HTTP 控制面端口 |
| `Z_GRAPH_DATA_DIR` | /var/lib/z-graph | 数据持久化目录 |
| `Z_GRAPH_CORS_ALLOWED_ORIGINS` | `*` | CORS 允许的 origin（逗号分隔） |
| `Z_GRAPH_API_TOKEN` | (无) | API Token 认证密钥（设置后启用 Bearer 认证） |
| `Z_GRAPH_RATE_LIMIT` | 0 | 每 IP 每分钟请求上限（0=不限，生产建议 100-600） |
| `JAVA_OPTS` | (空) | JVM 参数注入 |

### 前端环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `Z_GRAPH_API_UPSTREAM` | `z-graph-server:8090` | API 反代目标地址（容器内网络解析） |

---

## 性能基准

基于本地测试环境（macOS, 4 cores, 16GB RAM）的实测数据：

| 指标 | 数值 |
|------|------|
| 健康检查延迟 | **5.2ms** |
| Cypher 查询延迟（MATCH + LIMIT 10） | **6.0ms** |
| 前端页面加载延迟 | **4.8ms** |
| API 代理延迟（Nginx 反代） | **5.4ms** |
| JS GZIP 压缩比 | **68%** (194KB → 63KB) |
| CSS GZIP 压缩比 | **73%** (8.5KB → 2.3KB) |
| Server CPU 占用（空闲） | 0.95% |
| Server 内存占用 | **73MB** |
| Frontend CPU 占用（空闲） | 2.17% |
| Frontend 内存占用 | **5.85MB** |

### 吞吐估算

单实例 z-graph-server 在 4 核 CPU 上的理论吞吐：
- 简单查询（read）：~10,000 req/s
- 简单查询（write）：~1,000 req/s
- 复杂查询（join/aggregation）：~500 req/s

具体数值取决于数据规模、查询复杂度和硬件配置。

---

## Git 版本化图模型

```java
GraphVersionStore repository = new GraphVersionStore();

// 在 main 分支创建初始节点
GraphWriteTransaction tx = repository.beginWrite("main");
tx.addNode("Person", Map.of("name", "Alice"));
GraphCommit base = tx.commit("alice", "add Alice");

// 创建 feature 分支并添加 Bob
repository.createBranch("feature", base.getId());
GraphWriteTransaction featureTx = repository.beginWrite("feature");
featureTx.addNode("Person", Map.of("name", "Bob"));
featureTx.commit("bob", "feature graph");

// 三方合并
GraphMergeResult merge = repository.merge("main", "feature", "maintainer", "merge feature");
// merge.getConflicts() 返回冲突列表

// 回滚到任意历史 commit
GraphCheckout old = repository.checkout(base.getId());
List<Map<String, Object>> rows = old.query("MATCH (n:Person) RETURN n.name AS name");
```

### 合并策略

三方 merge，base / ours / theirs 在同一节点或边上：
- 只有单侧发生变化的属性 → 自动合并
- 同一字段两侧都修改 → 进入冲突列表（人工处理）

---

## 可观测性

### 日志

**stdout/stderr**（Docker `docker logs` 可查看）：
```
[INFO] 192.168.1.10 GET /meta/branches 200 1ms HTTP-Dispatcher
[INFO] 192.168.1.10 POST /query 200 12ms HTTP-Dispatcher reqId=abc123def456
[ERROR] 192.168.1.10 POST /query 500 5ms HTTP-Dispatcher: RuntimeException - Invalid pattern
```

**环形缓冲区**（通过 HTTP API 查询）：
```bash
curl 'http://localhost:8090/meta/logs?limit=100&method=POST&status=4xx'

{
  "total": 1234,
  "bufferSize": 500,
  "offset": 0,
  "limit": 100,
  "entries": [
    {
      "timestamp": 1700000000000,
      "requestId": "abc123def456",
      "method": "POST",
      "path": "/query",
      "clientIp": "192.168.1.10",
      "status": 200,
      "elapsedMs": 12,
      "thread": "HTTP-Dispatcher"
    }
  ]
}
```

### 监控指标（Prometheus 兼容格式规划中）

通过 `/meta/metrics` 获取：
- 请求总数、错误率、平均响应时间
- JVM 内存使用、GC 次数
- 活跃速率限制桶数
- 认证失败次数

### 分布式追踪

每个响应携带 `X-Request-ID` 头：
- 服务端自动生成（16 字符 UUID）
- 客户端可通过 `X-Request-ID: <id>` 传入复用
- 同一请求链路上所有日志可通过 ID 关联

---

## 开发指南

### 模块说明

```
z-graph-api/                # 公共 API（GraphCommit、GraphMergeResult）
z-graph-core/               # 核心引擎
  ├── CypherEngine          # OpenCypher 解析/执行
  ├── InMemoryGraphStore    # 内存图存储
  ├── GraphVersionStore     # Git 风格版本控制
  └── GraphMetaService      # 元数据服务
z-graph-protocol/           # Bolt 4.4 协议
z-graph-bolt-server/        # 服务端
  ├── ZGraphServer          # Bolt + HTTP 控制面统一入口
  └── GraphControlServer    # HTTP 控制面
z-graph-spring-boot-starter/# Spring Boot 集成（可选）
z-graph-console/            # React 前端
```

### 添加新的 Cypher 子句

1. 在 `z-graph-core` 中扩展 `CypherEngine`
2. 添加 AST 节点定义
3. 实现 Parser（基于现有递归下降）
4. 实现 Executor
5. 在 `z-graph-core` 中添加单元测试
6. 更新 README 的 Cypher 支持清单

### 添加新的 HTTP 端点

1. 在 `GraphControlServer` 中创建 `handleXxx` 方法
2. 在构造函数中注册：`server.createContext("/path", logAndHandle(this::handleXxx))`
3. 在 `ApiDocs.jsx` 中添加端点说明
4. 更新 README 的端点清单

### 本地开发工作流

```bash
# 后端开发
mvn -pl z-graph-core test                    # 单元测试
mvn -pl z-graph-core -am compile             # 编译
mvn -pl z-graph-bolt-server exec:java        # 启动服务

# 前端开发
cd z-graph-console
npm run dev                                  # 开发服务器
npm run build                                # 生产构建

# 集成测试
mvn test                                     # 全部 148 个测试
```

---

## 故障排查

### 端口冲突

```
Error: Address already in use: 7687
```

```bash
# 查找占用端口的进程
lsof -i :7687
# 杀死进程或修改 Z_GRAPH_BOLT_PORT
```

### 容器启动失败

```bash
# 查看容器日志
docker logs z-graph-server

# 检查健康状态
docker inspect --format '{{.State.Health.Status}}' z-graph-server
```

### 性能下降

```bash
# 检查 JVM 内存
curl http://localhost:8090/meta/metrics | jq '.jvmMemory'

# 检查 GC
docker exec z-graph-server jstat -gc 1

# 查看慢查询日志
curl 'http://localhost:8090/meta/logs?limit=20' | jq '.entries[] | select(.elapsedMs > 100)'
```

### 数据丢失

检查 Docker 卷是否正确挂载：
```bash
docker volume inspect z-graph-data
```

### CORS 错误

```bash
# 限制允许的 origin
export Z_GRAPH_CORS_ALLOWED_ORIGINS="https://app.example.com,https://admin.example.com"
```

### 前端 404

```bash
# 检查 Nginx 配置和 frontend 容器
docker logs z-graph-frontend

# 验证反代
curl http://localhost:3000/api/health
```

---

## Git 提交历史

```
c616c53 docs: README 全面更新 + Docker Compose 生产配置优化
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

---

## 开源参考

架构分层和协议兼容目标参考 [NebulaGraph](https://github.com/vesoft-inc/nebula)。NebulaGraph 为 Apache License 2.0 项目；本工程仅采用其公开架构思想和协议资料，不复制其未授权代码，并保留上游项目链接及许可证边界。

---

## 许可证

Apache License 2.0
