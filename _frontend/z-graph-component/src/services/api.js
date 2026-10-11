import request from '@/common'

/**
 * z-graph 管理面数据源。
 *
 * 上游不是 Spring MVC：`com.zifang.z.graph.bolt.GraphControlServer` 是 JDK
 * `com.sun.net.httpserver.HttpServer`（构造即 bind，默认 8090），所以它永远不出现在
 * `/actuator/mappings` 里，也不在 vite 的 `/api` 代理范围内。本模块全部走 z-opc 侧的
 * `GraphProxyController`（`/api/graph/**` → **只连本 JVM 自己 bind 成功的那个端口**），
 * 响应体是 GraphControlServer 自己拼的那份 JSON，逐字节透传，没有第二套字段命名。
 *
 * 字段来源（逐个对着 z-graph-bolt-server 的 GraphControlServer 源码 + 2026-09-25 的
 * 真实 GET 响应核过；行内注释就是实测报文的形状）：
 *   GET  /health          → {status:"UP", head:"66091fc9…", nodeCount:0, edgeCount:0}
 *   GET  /meta/branches   → ["main"]                                   ← 裸数组
 *   GET  /meta/commits    → [{id, parents:[], branch, author, message,
 *                             timestamp, nodeCount, edgeCount}]        ← 裸数组
 *   GET  /meta/schema     → {branch, tags:[{Name}], edges:[{Name}],
 *                           indexes:[{Name,Kind,On,Property}]}
 *   GET  /meta/stats      → {branch, head, nodeCount, edgeCount,
 *                           stats:[{nodeCount, edgeCount, labelCount, edgeTypeCount,
 *                                   propertyIndexCount, tagSchemaCount, edgeTypeSchemaCount}]}
 *   GET  /meta/metrics    → {uptimeMs, uptimeSeconds, uptimeFormatted, totalRequests,
 *                           errorResponses, errorRate, rateLimitedRequests, authFailures,
 *                           apiTokenEnabled, rateLimitPerMinute, activeRateBuckets,
 *                           jvmMemory:{maxBytes,totalBytes,freeBytes,usedBytes}, availableProcessors}
 *   GET  /meta/logs       → {total, bufferSize, offset, limit,
 *                           entries:[{timestamp, requestId, method, path, clientIp,
 *                                     status, elapsedMs, thread}]}
 *   GET  /query           → [{...}]  列名由 Cypher 的 RETURN 决定，所以表头是动态的
 *   POST /query/explain   → {plan:{queryType, steps:[{operation, description}],
 *                                estimatedComplexity}, cypher, branch, parsedAt}
 * 代理层自己失败时返回 {status:"error", source:"z-opc-graph-proxy", message, path[, hint]}，
 * HTTP 状态码同语义（503=内嵌没 bind，502=上游调不通，400=路径/查询不合法）。
 *
 * 两个刻意不接的口子：
 *   - `POST /meta/import`：它把 JSON 手工拼成 `CREATE` 语句再执行（parseNodeImportStatements），
 *     对共享图实例是不可逆写入，孵化页不提供。
 *   - `GET /meta/export`：响应带 `Content-Disposition: attachment`，是个下载件而不是表格数据。
 */
export const graphApi = {
    instance: () => request.get('/__instance').then((r) => r.data),
    health: () => request.get('/health').then((r) => r.data),
    branches: () => request.get('/meta/branches').then((r) => r.data),
    commits: () => request.get('/meta/commits').then((r) => r.data),
    schema: (branch) => request.get('/meta/schema', {params: branch ? {branch} : {}}).then((r) => r.data),
    stats: (branch) => request.get('/meta/stats', {params: branch ? {branch} : {}}).then((r) => r.data),
    metrics: () => request.get('/meta/metrics').then((r) => r.data),
    logs: (params) => request.get('/meta/logs', {params}).then((r) => r.data),
    /**
     * 查询走 GET 而不是 POST：`handleQuery` 的 POST 分支用的是手搓的
     * `parseJsonStringMap`（按引号外逗号切 token、再按第一个冒号切 kv），
     * 而 GET 分支用的是 `URLDecoder.decode` —— 对含 `:`、`,`、`{}` 的 Cypher
     * 只有前者会被解析器啃掉。所以这里统一走 GET。
     */
    query: (cypher, branch, commit) =>
        request.get('/query', {params: commit ? {cypher, branch, commit} : {cypher, branch}}).then((r) => r.data),
    explain: (cypher, branch) => request.post('/query/explain', {cypher, branch}).then((r) => r.data),
}

/**
 * 让宿主（suit / 主壳 domainRoutes）注入 API 前缀：所有 graphApi 相对路径
 * 拼到 `{prefix}{path}`，与 vite proxy / nginx 反代同源。
 * 默认 '/api'：dev proxy 默认形态；走绝对地址（如直连 8090）时传完整 origin。
 *
 * 同时把 console/api.js 的 fetch 客户端也切到同一前缀 —— 那条路在
 * withGraphServer HOC + console pages 里仍被使用，两边必须保持一致，
 * 否则 health 报 200 而 instance 报 404 这种诡异分支。
 */
import {api as consoleApi} from '../console/api.js'

export function configureGraph(apiBase = '/api') {
    request.defaults.baseURL = apiBase
    consoleApi.setBaseUrl(apiBase)
}

/**
 * GraphControlServer 的写判定是纯字符串前缀/包含判断（handleQuery 里的 mutating），
 * 页面用它提前给出「这条会改图」的提示，但**不**用它做任何数据兜底。
 */
const MUTATING = /^\s*(CREATE|MERGE|DELETE|DROP|ALTER|SET|REMOVE|REPLACE)\b/i
export function isMutatingCypher(cypher) {
    if (!cypher) return false
    const upper = cypher.toUpperCase()
    if (MUTATING.test(cypher)) return true
    if (upper.startsWith('MATCH') && (upper.includes(' SET ') || upper.includes(' DELETE ') || upper.includes('DETACH DELETE'))) {
        return true
    }
    return false
}

/**
 * 后端错误体有两套形状：代理层的 {status:"error", message, hint} 和上游的 {error:"..."}
 * （GraphControlServer 所有 catch 分支都只写 `error` 字段），只认前者的话 Cypher 语法错误
 * 会被渲染成一句没头没尾的 "Request failed with status code 500"。
 */
export function graphErrorText(e) {
    const data = e?.response?.data
    if (data && typeof data === 'object') {
        const message = data.message || data.error
        const source = data.source ? ` (${data.source})` : ''
        if (message) return `${message}${source}${data.hint ? ` — ${data.hint}` : ''}`
    }
    if (typeof data === 'string' && data.trim()) return data.slice(0, 300)
    return e?.message || String(e)
}

/** 上游的 503 体里带 hint；页面用它把「内嵌没起」和「图里确实没数据」分开展示 */
export function isNotBoundError(e) {
    return e?.response?.status === 503 || /not bound in this JVM/.test(String(e?.response?.data?.message || ''))
}

/** /meta/schema 的 branch 默认值来自 GraphControlServer.handleSchema 的 getOrDefault("branch","main") */
export const DEFAULT_BRANCH = 'main'
