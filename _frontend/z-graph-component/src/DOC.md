# z-graph-component

z-graph（图数据库控制台）跨仓唯一前端面：把「总览 / 图视图 / 分支 / 提交历史 / Cypher 查询 /
Schema / 请求日志 / API 文档 / 实例自省」这一组图数据库运维视图收敛成一个可复用的 React
组件包，由 `z-graph-suit` 独立壳、z-team 的 OpsWorkbench（`/graph/*` 通配挂载）或 z-opc 主壳挂载。

- **npm 包名**：`@yuku123/z-graph-component`
- **宿主接入**：`@yuku123/z-graph-component/pages`（manifest + 页面组件），主壳可只取 `ComponentDocPage`
- **后端**：z-graph-bolt-server 的 `GraphControlServer`（JDK `com.sun.net.httpserver.HttpServer`，
  构造即 bind，默认 8090），在 z-opc 里由 `GraphProxyController` 转一道（`/api/graph/**`）
- **技术栈**：React 19 + antd 6 + react-router-dom 7 + vite（library mode，双入口 `.` / `./pages`）

---

## 一、能力概览

包里有**两套**页面树、**两套**请求层，路由前缀也不同，别混：

### 1.1 manifest 面（`/z-graph/*`，`src/pages-manifest.jsx`）

| 页面 | 路由（相对 `/z-graph/*`） | 源文件 | 能力 |
|------|--------------------------|--------|------|
| 首页 | `home` | `pages/HomePage.jsx` | 读 `userInfo` 显示登录人，按 `menuItems` 渲染功能入口卡片 |
| 总览 | `dashboard` | `console/pages/Dashboard.jsx` | 图数据库总览（点数 / 边数 / head） |
| 图视图 | `graph` | `console/pages/GraphView.jsx` | 图数据可视化 |
| 分支 | `branches` | `console/pages/Branches.jsx` | 分支清单 |
| 提交历史 | `commits` | `console/pages/Commits.jsx` | commit 列表 |
| Cypher 查询 | `query` | `console/pages/QueryPage.jsx` | Cypher 执行 + 执行计划 |
| Schema | `schema` | `console/pages/Schema.jsx` | 点类型 / 边类型 / 属性索引 |
| 请求日志 | `logs` | `console/pages/LogViewer.jsx` | 控制面请求日志 |
| API 文档 | `api-docs` | `console/pages/ApiDocs.jsx` | 控制面 API 说明 |
| 实例与端口 | `instance` | `pages/InstanceStatus.jsx` | 实例自省 + 控制面指标 |
| 登录 | （不在菜单内） | `pages/LoginPage.jsx` | mock 登录，写 `token` / `userInfo` 后跳 `/z-graph/home` |
| 组件文档 | `docs` | `pages/ComponentDoc.jsx` | 本文件渲染页（左侧 240px TOC + 右侧正文） |

`console/` 下这 8 个旧版页面都套了 `withGraphConsole(...)`（`console/serverHook.jsx`）——
它在 `withGraphServer` 之外再套一层 `.zgc` 作用域容器。
**为什么必须有这层**：旧版页面吃的 `console/styles.css` 原本是「独立 SPA 全局样式」
（裸元素选择器 `button` / `input` / `a` / `table`），而本组件的 CSS 会被主壳 z-opc 当
**全局样式**注入 ⇒ `button:hover{background:#334155}` 会泄漏到整个主壳，
压过 z-team 工作台标签栏的 `.tab{background:none}`（特异度 0,1,0 vs 0,1,1）。
新版页面（`src/pages/*`，antd 实现）不吃这份样式，所以**不加**作用域。

### 1.2 GraphApp 面（`/graph/*`，`src/pages/GraphApp.jsx`）

`GraphApp` 是给 OpsWorkbench 通配挂载用的**子路由集合**，入口重定向到 `schema`：

| 子路由 | 源文件 | 能力 |
|--------|--------|------|
| `schema` | `pages/SchemaPage.jsx` | 图 Schema（点类型 / 边类型 / 属性索引） |
| `query` | `pages/QueryConsole.jsx` | Cypher 执行 + 执行计划 |
| `branches` | `pages/BranchCommitPage.jsx` | 分支与提交 |
| `instance` | `pages/InstanceStatus.jsx` | 实例自省 |

### 1.3 两套请求层

| 层 | 文件 | 出口 | 用在哪些页面 |
|----|------|------|--------------|
| axios 版 | `src/services/api.js` | `graphApi` | `src/pages/*` 的新版页面（`SchemaPage` / `QueryConsole` / `BranchCommitPage` / `InstanceStatus`） |
| fetch 版（console） | `src/console/api.js` | `api` | `console/pages/*` 的 8 个旧版页面（经 `withGraphServer` 供给） |

两层的路径**完全一致**，只是实现不同（axios 30s 超时 vs 原生 fetch 无超时）。
`configureGraph(apiBase)` 会把**两层的 baseURL 一起切**（`console/api.js` 有 `setBaseUrl`），
避免出现「health 报 200 而 instance 报 404」这种诡异分支。

## 二、导出面

```js
// 主入口（. 桶）：两套请求层 + 域挂载点
import {graphApi, graphErrorText, isNotBoundError, isMutatingCypher, DEFAULT_BRANCH,
        configureGraph, api, GraphApp} from '@yuku123/z-graph-component'

// 宿主组装面（./pages 桶）：manifest + 页面组件
import {menuItems, routes, appMeta, HomePage, LoginPage,
        withGraphServer, withGraphConsole, useGraphServer,
        api, configureGraph, ComponentDocPage} from '@yuku123/z-graph-component/pages'

// 组件文档页（主壳接线用，名字全仓统一）
import {ComponentDocPage} from '@yuku123/z-graph-component'
```

## 三、API 端点

上游**不是 Spring MVC**：`com.zifang.z.graph.bolt.GraphControlServer` 是 JDK
`com.sun.net.httpserver.HttpServer`，构造即 bind（默认 8090），所以它永远不出现在
`/actuator/mappings` 里，也不在 vite 的 `/api` 代理范围内。

下表路径是 `src/services/api.js`（`graphApi`）与 `src/console/api.js`（`api`）里出现的
**实际路径**（不含前缀，前缀见下）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/__instance` | 实例自省（**只由 z-opc 的 `GraphProxyController` 应答**，不经过上游） |
| GET | `/health` | 健康检查 `{status, head, nodeCount, edgeCount}` |
| GET | `/meta/branches` | 分支列表（裸数组） |
| GET | `/meta/commits` | 提交历史（裸数组） |
| GET | `/meta/schema` | Schema（query：`branch`） |
| GET | `/meta/stats` | 引擎统计（query：`branch`） |
| GET | `/meta/metrics` | 控制面自身指标（uptime / 请求数 / 限流 / JVM 内存…） |
| GET | `/meta/logs` | 请求日志（query：`offset` / `limit` / `method` / `status` / `path`） |
| GET | `/meta/export` | 导出（**下载件**，只由 console 层暴露） |
| GET | `/query` | Cypher 查询（query：`cypher` / `branch` / `commit`） |
| POST | `/query/explain` | 执行计划（body：`{cypher, branch}`） |
| POST | `/query/batch` | 批量查询（body：`{statements}`，只由 console 层暴露） |

### 3.1 请求前缀

| 部署形态 | 前缀 | 说明 |
|---|---|---|
| 独立壳 `z-graph-suit` | `/api` | dev proxy 把 `/api/*` 剥掉前缀转发到 `localhost:8090` |
| z-opc 主壳 | `/api/graph` | 后端 `GraphProxyController` 的 `@RequestMapping` 前缀就是 `/api/graph` |

`configureGraph(apiBase = '/api')` 是宿主注入前缀的唯一入口（同时切 axios 层与 console fetch 层）。

### 3.2 两个刻意不接的口子

- `POST /meta/import`：它把 JSON 手工拼成 `CREATE` 语句再执行，对共享图实例是不可逆写入，
  孵化页不提供。
- `GET /meta/export`：响应带 `Content-Disposition: attachment`，是个下载件而不是表格数据。

## 四、页面说明

### 4.1 首页

登录后的着陆页。读 `localStorage.userInfo` 显示欢迎语与角色标签，再把 `menuItems`（去掉首页自身）
渲染成入口卡片 —— 菜单加一项，首页卡片自动多一张，不用改页面代码。

### 4.2 总览 / 图视图 / 分支 / 提交历史 / Cypher 查询 / Schema / 请求日志 / API 文档

`console/pages/` 下的 8 个旧版页面（吃 `console/styles.css`，故套 `.zgc` 作用域）。
它们统一由 `withGraphServer` 供给 `{server, metrics}` —— `useGraphServer` 每 5 秒轮询
`/health` + `/meta/metrics`，把控制面健康态与指标灌给页面。

### 4.3 实例与端口（`pages/InstanceStatus.jsx`）

`/api/graph/__instance` 的可视化（外加控制面自己的 `/meta/metrics` 与 `/meta/logs`）。
它把「内嵌没 bind 成功（503）」与「图里确实没数据」分开展示 ——
代理只在本 JVM `boundPort > 0` 时才转发。

### 4.4 Schema / 查询 / 分支与提交（新版，antd 实现）

`src/pages/` 下的 `SchemaPage` / `QueryConsole` / `BranchCommitPage`，用 axios 层
（`graphApi`），不吃 console 样式表。`QueryConsole` 对写语句（`CREATE` / `MERGE` / `DELETE` /
`DROP` / `SET`…）有二次确认，判据是 `isMutatingCypher`。

### 4.5 登录

当前是 **mock 模式**：比对一对写死在 `src/pages/LoginPage.jsx` 里的管理员凭据
（明文口令不在本文复述，见源码），成功后写 `localStorage.token` 与 `localStorage.userInfo`，
再 `window.location.href = '/z-graph/home'`。CTC/SSO 尚未接入。

### 4.6 组件文档

本文件。内容以 `?raw` 在编译期内联，零额外请求；左侧 240px TOC 随滚动联动。

## 五、配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `configureGraph(apiBase)` | `'/api'` | 同时设置 axios 层与 console fetch 层的 baseURL |
| `console/api.js` 的 baseUrl | `window.__Z_GRAPH_API__` → `VITE_API_BASE` → `'/api'` | 三级兜底；`setBaseUrl` 会写回 `window.__Z_GRAPH_API__` |
| axios 超时 | `30000` ms | axios 层；console fetch 层**没有超时** |
| 默认分支 | `main` | `DEFAULT_BRANCH`，与上游 `getOrDefault("branch","main")` 一致 |
| 健康轮询间隔 | `5000` ms | `useGraphServer` 的轮询周期 |

## 六、已知限制

- ⚠ **平台主壳里的前缀没接上**：`configureGraph` 默认 `'/api'`，但 z-opc 后端的
  `GraphProxyController` 前缀是 `/api/graph`。z-opc 主壳与 z-team 都**没有**调用
  `configureGraph('/api/graph')`（只有 `z-graph-suit/src/main.jsx` 调了 `configureGraph('/api')`），
  ⇒ 在平台里这批请求会打到 `/api/health` 而不是 `/api/graph/health`。
  修这个要在宿主侧接线（`configureGraph('/api/graph')`），**不在本包的文件所有权范围内**。
- **查询走 GET 而不是 POST**：`handleQuery` 的 POST 分支用的是手搓的 `parseJsonStringMap`
  （按引号外逗号切 token），GET 分支用的是 `URLDecoder.decode` ——
  对含 `:`、`,`、`{}` 的 Cypher 只有前者会被解析器啃掉。所以 axios 层统一走 GET；
  而 console 层的 `api.query` 走的是 POST，两层口径不同。
- **commit 字段不能传空串**：「null」字符串无法区分真正的 null，所以 commit 为空时
  console 层直接不发送该字段，避免触发 `Unknown commit: null` 错误。
- **两套请求层的结果形状不同**：console 层返回 `{data, elapsed}`（带耗时），
  axios 层返回裸 data。两层页面不能混用同一个返回值。
- **上游 8090 是独立进程**：z-graph-bolt-server 实测是 PID 1794 的常驻进程；
  代理只连本 JVM 内嵌 bind 成功的端口，bind 没成功就直接 503，
  不会退化成「连 8090 试试看」—— 那样会把外部进程的应答读成自己的。
- 后端错误体有两套形状：代理层的 `{status:"error", message, hint}` 与上游的 `{error:"…"}`
  （`GraphControlServer` 所有 catch 分支都只写 `error`），`graphErrorText` 两个都认。
- 静态 `message` / `notification`（antd 6 下会告警，规范要求改 `App.useApp()`）。
- z-graph 已被 z-opc 主壳的 `domainRoutes.ts` 与 z-team 的 OpsWorkbench 引用
  ⇒ `/z-graph/docs` 在平台主壳里**可达**。

## 七、变更记录

| 版本 | 变更 |
|---|---|
| 0.1.1 | 新增组件文档页 `ComponentDocPage`（lead 008 §7）：`src/DOC.md` + 基座 `ComponentDoc` 薄包装 |
| 0.1.1 | 首版：console 旧版页面集 + 新版 antd 页面 + 双请求层 + manifest + GraphApp 挂载点 |
