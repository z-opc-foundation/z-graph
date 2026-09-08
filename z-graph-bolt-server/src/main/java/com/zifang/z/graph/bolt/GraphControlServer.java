package com.zifang.z.graph.bolt;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.zifang.z.graph.api.GraphCommit;
import com.zifang.z.graph.core.CypherEngine;
import com.zifang.z.graph.core.GraphMetaService;
import com.zifang.z.graph.core.GraphQueryService;
import com.zifang.z.graph.core.GraphVersionStore;
import com.zifang.z.graph.core.GraphWriteTransaction;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 本地 Graphd 控制面服务骨架。
 *
 * <p>它把 Nebula 风格的 Meta、Query 入口暴露为独立 HTTP 控制面，底层仍复用同一个
 * GraphVersionStore。生产部署时可把这些 handler 替换成 RPC 实现，而不改变版本和查询语义。</p>
 *
 * <p>端点：{@code /health}、{@code /meta/branches}、{@code /meta/commits}、
 * {@code /query?branch=main&cypher=...}。</p>
 */
public final class GraphControlServer {

    private final HttpServer server;
    private final GraphVersionStore repository;
    private final GraphMetaService metaService;
    private final GraphQueryService queryService;

    public GraphControlServer(int port, GraphVersionStore repository) throws IOException {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.metaService = new GraphMetaService(repository);
        this.queryService = new GraphQueryService(repository);
        this.server = HttpServer.create(new InetSocketAddress(port), 128);
        // 注册所有端点,通过日志过滤器包装
        server.createContext("/health", logAndHandle(this::handleHealth));
        server.createContext("/meta/branches", logAndHandle(this::handleBranches));
        server.createContext("/meta/commits", logAndHandle(this::handleCommits));
        server.createContext("/meta/schema", logAndHandle(this::handleSchema));
        server.createContext("/meta/stats", logAndHandle(this::handleStats));
        server.createContext("/meta/export", logAndHandle(this::handleExport));
        server.createContext("/meta/import", logAndHandle(this::handleImport));
        server.createContext("/query/batch", logAndHandle(this::handleBatch));
        server.createContext("/query", logAndHandle(this::handleQuery));
        // OPTIONS 预检 + CORS 头
        server.createContext("/options", logAndHandle(exchange -> writeNoContent(exchange)));
    }

    /** 日志过滤器:记录每个请求的方法、路径、状态码和耗时。 */
    private HttpHandler logAndHandle(HttpHandler handler) {
        return exchange -> {
            long start = System.currentTimeMillis();
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            String clientIp = exchange.getRemoteAddress() != null
                    ? exchange.getRemoteAddress().getAddress().getHostAddress() : "-";
            try {
                handler.handle(exchange);
            } catch (Exception e) {
                // 未捕获异常:记录并返回 500
                logError(method, path, clientIp, 500, System.currentTimeMillis() - start, e);
                try {
                    writeJson(exchange, 500, Map.of("error", "Internal server error"));
                } catch (Exception ignored) { }
                return;
            }
            int status = exchange.getResponseCode();
            long elapsed = System.currentTimeMillis() - start;
            // /health 不记录(太频繁),错误和慢查询必须记录
            if (!"/health".equals(path) || status >= 400 || elapsed > 100) {
                logAccess(method, path, query, clientIp, status, elapsed);
            }
        };
    }

    private static void logAccess(String method, String path, String query, String clientIp, int status, long elapsed) {
        String full = query != null ? path + "?" + query : path;
        System.out.println(String.format("[INFO] %s %s %s %d %dms %s",
                clientIp, method, full, status, elapsed, Thread.currentThread().getName()));
    }

    private static void logError(String method, String path, String clientIp, int status, long elapsed, Exception e) {
        System.err.println(String.format("[ERROR] %s %s %s %d %dms %s: %s",
                clientIp, method, path, status, elapsed, e.getClass().getSimpleName(), e.getMessage()));
    }

    /**
     * 解析 CORS 配置。系统属性 {@code z.graph.cors.allowedOrigins} 可以指定
     * 逗号分隔的 origin 列表,默认允许所有 origin(方便本地与容器调试)。
     */
    private static String[] allowedOrigins() {
        String raw = System.getProperty("z.graph.cors.allowedOrigins",
                System.getenv().getOrDefault("Z_GRAPH_CORS_ALLOWED_ORIGINS", "*"));
        if (raw == null || raw.isBlank()) return new String[]{"*"};
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    private static void applyCorsHeaders(HttpExchange exchange) {
        String[] origins = allowedOrigins();
        String requestedOrigin = exchange.getRequestHeaders().getFirst("Origin");
        if ("*".equals(origins[0])) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        } else if (requestedOrigin != null && java.util.Arrays.asList(origins).contains(requestedOrigin)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", requestedOrigin);
            exchange.getResponseHeaders().set("Vary", "Origin");
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "3600");
    }

    private static void writeNoContent(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            applyCorsHeaders(exchange);
            exchange.sendResponseHeaders(204, -1);
            exchange.getResponseBody().close();
        } else {
            writeJson(exchange, 405, Map.of("error", "Method not allowed"));
        }
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        GraphCommit head = metaService.head("main");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("head", head.getId());
        body.put("nodeCount", head.getNodeCount());
        body.put("edgeCount", head.getEdgeCount());
        applyCorsHeaders(exchange);
        writeJson(exchange, 200, body);
    }

    private void handleBranches(HttpExchange exchange) throws IOException {
        applyCorsHeaders(exchange);
        writeJson(exchange, 200, metaService.branches());
    }

    private void handleCommits(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> commits = new ArrayList<>();
        for (GraphCommit commit : metaService.commits()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", commit.getId());
            item.put("parents", commit.getParents());
            item.put("branch", commit.getBranch());
            item.put("author", commit.getAuthor());
            item.put("message", commit.getMessage());
            item.put("timestamp", commit.getTimestampEpochMillis());
            item.put("nodeCount", commit.getNodeCount());
            item.put("edgeCount", commit.getEdgeCount());
            commits.add(item);
        }
        applyCorsHeaders(exchange);
        writeJson(exchange, 200, commits);
    }

    /**
     * GET /meta/schema — 返回当前分支的 schema 信息（TAG / EDGE 定义）。
     */
    private void handleSchema(HttpExchange exchange) throws IOException {
        applyCorsHeaders(exchange);
        Map<String, String> params = queryParameters(exchange.getRequestURI());
        String branch = params.getOrDefault("branch", "main");
        try {
            var graphCheckout = repository.checkoutBranch(branch);
            var store = graphCheckout.getStore();
            // 用 SHOW TAGS / SHOW EDGES 查询 schema
            List<Map<String, Object>> tags = new CypherEngine(store, repository)
                    .execute("SHOW TAGS", Map.of());
            List<Map<String, Object>> edges = new CypherEngine(store, repository)
                    .execute("SHOW EDGES", Map.of());
            List<Map<String, Object>> indexes = new CypherEngine(store, repository)
                    .execute("SHOW INDEXES", Map.of());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("branch", branch);
            result.put("tags", tags);
            result.put("edges", edges);
            result.put("indexes", indexes);
            writeJson(exchange, 200, result);
        } catch (Exception e) {
            writeJson(exchange, 500, Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /meta/stats — 返回当前分支的统计摘要（节点数、边数、标签分布等）。
     */
    private void handleStats(HttpExchange exchange) throws IOException {
        applyCorsHeaders(exchange);
        Map<String, String> params = queryParameters(exchange.getRequestURI());
        String branch = params.getOrDefault("branch", "main");
        try {
            var graphCheckout = repository.checkoutBranch(branch);
            var store = graphCheckout.getStore();
            List<Map<String, Object>> stats = new CypherEngine(store, repository)
                    .execute("CALL db.stats()", Map.of());
            GraphCommit head = metaService.head(branch);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("branch", branch);
            result.put("head", head.getId());
            result.put("nodeCount", head.getNodeCount());
            result.put("edgeCount", head.getEdgeCount());
            result.put("stats", stats);
            writeJson(exchange, 200, result);
        } catch (Exception e) {
            writeJson(exchange, 500, Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /query/batch — 批量执行多条 Cypher 语句。
     * 请求体: {"statements":[{"cypher":"...","branch":"main"},...]}
     * 返回: {"results":[{...},{...},...],"elapsedMs":123}
     */
    private void handleBatch(HttpExchange exchange) throws IOException {
        applyCorsHeaders(exchange);
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("error", "POST required"));
            return;
        }
        long start = System.currentTimeMillis();
        try {
            String body = readBody(exchange);
            // 简易解析 statements 数组 — 提取每个 {cypher:..., branch:...} 对
            List<Map<String, String>> statements = parseStatementsArray(body);
            List<Object> results = new ArrayList<>();
            String defaultBranch = "main";

            for (Map<String, String> stmt : statements) {
                String cypher = stmt.get("cypher");
                String branch = stmt.getOrDefault("branch", defaultBranch);
                if (cypher == null || cypher.isBlank()) {
                    results.add(Map.of("error", "Missing 'cypher'"));
                    continue;
                }
                try {
                    String upper = cypher.trim().toUpperCase();
                    boolean mutating = upper.startsWith("CREATE") || upper.startsWith("MERGE")
                            || (upper.startsWith("MATCH") && (upper.contains(" SET ")
                            || upper.contains(" DELETE ") || upper.contains("DETACH DELETE")));
                    if (mutating) {
                        GraphWriteTransaction tx = queryService.beginWrite(branch);
                        List<Map<String, Object>> rows = new CypherEngine(tx, repository)
                                .execute(cypher, Map.of());
                        tx.commit("batch", "Batch: " + cypher.substring(0, Math.min(cypher.length(), 60)));
                        results.add(Map.of("rows", rows, "statementIndex", statements.indexOf(stmt)));
                    } else {
                        var graphCheckout = repository.checkoutBranch(branch);
                        List<Map<String, Object>> rows = new CypherEngine(graphCheckout.getStore(), repository)
                                .execute(cypher, Map.of());
                        results.add(Map.of("rows", rows, "statementIndex", statements.indexOf(stmt)));
                    }
                } catch (Exception e) {
                    results.add(Map.of("error", e.getMessage(), "statementIndex", statements.indexOf(stmt)));
                }
            }
            long elapsed = System.currentTimeMillis() - start;
            exchange.getResponseHeaders().set("X-Response-Time", elapsed + "ms");
            writeJson(exchange, 200, Map.of("results", results, "elapsedMs", elapsed));
        } catch (Exception e) {
            writeJson(exchange, 500, Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /meta/export — 导出当前分支的全部图数据（节点 + 边 + schema）。
     */
    private void handleExport(HttpExchange exchange) throws IOException {
        applyCorsHeaders(exchange);
        Map<String, String> params = queryParameters(exchange.getRequestURI());
        String branch = params.getOrDefault("branch", "main");
        try {
            var graphCheckout = repository.checkoutBranch(branch);
            var store = graphCheckout.getStore();
            List<Map<String, Object>> nodes = new CypherEngine(store, repository)
                    .execute("MATCH (n) RETURN n", Map.of());
            // 获取所有边类型,逐个查询
            List<Map<String, Object>> edges = new ArrayList<>();
            List<Map<String, Object>> edgeTypes = new CypherEngine(store, repository)
                    .execute("SHOW EDGES", Map.of());
            for (Map<String, Object> et : edgeTypes) {
                String typeName = String.valueOf(et.getOrDefault("Name", et.getOrDefault("name", "")));
                if (!typeName.isEmpty()) {
                    try {
                        List<Map<String, Object>> typeEdges = new CypherEngine(store, repository)
                                .execute("MATCH (a)-[r:" + typeName + "]->(b) RETURN r", Map.of());
                        edges.addAll(typeEdges);
                    } catch (Exception ignored) { }
                }
            }
            List<Map<String, Object>> tags = new CypherEngine(store, repository)
                    .execute("SHOW TAGS", Map.of());
            GraphCommit head = metaService.head(branch);

            Map<String, Object> export = new LinkedHashMap<>();
            export.put("version", "z-graph-1.0");
            export.put("branch", branch);
            export.put("head", head.getId());
            export.put("nodeCount", head.getNodeCount());
            export.put("edgeCount", head.getEdgeCount());
            export.put("schema", Map.of("tags", tags, "edgeTypes", edgeTypes));
            export.put("nodes", nodes);
            export.put("edges", edges);
            export.put("exportedAt", System.currentTimeMillis());

            // 设置下载头
            exchange.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename=\"z-graph-export-" + branch + ".json\"");
            writeJson(exchange, 200, export);
        } catch (Exception e) {
            writeJson(exchange, 500, Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /meta/import — 导入图数据。
     * 请求体: {"branch":"main","nodes":[{"label":"Person","name":"Alice",...}],...}
     */
    private void handleImport(HttpExchange exchange) throws IOException {
        applyCorsHeaders(exchange);
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("error", "POST required"));
            return;
        }
        long start = System.currentTimeMillis();
        try {
            String body = readBody(exchange);
            Map<String, String> json = parseJsonStringMap(body);
            String branch = json.getOrDefault("branch", "main");

            // 通过 Cypher CREATE 语句导入
            String nodesJson = json.get("nodes");
            String edgesJson = json.get("edges");

            int imported = 0;
            if (nodesJson != null && !nodesJson.isBlank()) {
                // 解析节点数组并生成 CREATE 语句
                List<String> nodeStatements = parseNodeImportStatements(nodesJson);
                for (String stmt : nodeStatements) {
                    GraphWriteTransaction tx = queryService.beginWrite(branch);
                    new CypherEngine(tx, repository).execute(stmt, Map.of());
                    tx.commit("import", "Import node");
                    imported++;
                }
            }

            long elapsed = System.currentTimeMillis() - start;
            exchange.getResponseHeaders().set("X-Response-Time", elapsed + "ms");
            writeJson(exchange, 200, Map.of(
                    "imported", imported,
                    "branch", branch,
                    "elapsedMs", elapsed));
        } catch (Exception e) {
            writeJson(exchange, 500, Map.of("error", e.getMessage()));
        }
    }

    private void handleQuery(HttpExchange exchange) throws IOException {
        applyCorsHeaders(exchange);
        long start = System.currentTimeMillis();
        // 允许 GET (?cypher=...) 和 POST (JSON body: {"cypher":"...","branch":"...","commit":"..."})
        String cypher;
        String branch;
        String commit;
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            String body = readBody(exchange);
            Map<String, String> json = parseJsonStringMap(body);
            cypher = json.get("cypher");
            branch = json.getOrDefault("branch", "main");
            commit = json.get("commit");
        } else {
            Map<String, String> parameters = queryParameters(exchange.getRequestURI());
            cypher = parameters.get("cypher");
            branch = parameters.getOrDefault("branch", "main");
            commit = parameters.get("commit");
        }

        if (cypher == null || cypher.isBlank()) {
            writeJson(exchange, 400, Map.of("error", "Missing 'cypher' in request"));
            return;
        }

        String upper = cypher.trim().toUpperCase();
        boolean mutating = upper.startsWith("CREATE")
                || upper.startsWith("MERGE")
                || (upper.startsWith("MATCH") && (upper.contains(" SET ")
                || upper.contains(" DELETE ") || upper.contains("DETACH DELETE")));

        try {
            if (mutating && commit == null) {
                // 写查询:通过 BEGIN+RUN+COMMIT 在独立事务提交,避免阻塞读请求
                GraphWriteTransaction tx = queryService.beginWrite(branch);
                List<Map<String, Object>> rows = new CypherEngine(tx, repository)
                        .execute(cypher, Map.of());
                tx.commit("http", "HTTP query: " + cypher.substring(0, Math.min(cypher.length(), 80)));
                long elapsed = System.currentTimeMillis() - start;
                exchange.getResponseHeaders().set("X-Response-Time", elapsed + "ms");
                writeJson(exchange, 200, rows);
            } else if (commit != null) {
                var graphCheckout = repository.checkout(commit);
                List<Map<String, Object>> rows = new CypherEngine(graphCheckout.getStore(), repository)
                        .execute(cypher, Map.of());
                long elapsed = System.currentTimeMillis() - start;
                exchange.getResponseHeaders().set("X-Response-Time", elapsed + "ms");
                writeJson(exchange, 200, rows);
            } else {
                var graphCheckout = repository.checkoutBranch(branch);
                List<Map<String, Object>> rows = new CypherEngine(graphCheckout.getStore(), repository)
                        .execute(cypher, Map.of());
                long elapsed = System.currentTimeMillis() - start;
                exchange.getResponseHeaders().set("X-Response-Time", elapsed + "ms");
                writeJson(exchange, 200, rows);
            }
        } catch (IllegalStateException conflict) {
            writeJson(exchange, 409, Map.of("error", "Stale head: " + conflict.getMessage()));
        } catch (Exception e) {
            writeJson(exchange, 500, Map.of("error", e.getMessage()));
        }
    }

    private static Map<String, String> queryParameters(URI uri) {
        Map<String, String> result = new LinkedHashMap<>();
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty()) return result;
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            if (separator <= 0) continue;
            String key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    private static void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String toJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String || value instanceof Character) {
            return "\"" + escape(String.valueOf(value)) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(entry -> toJson(String.valueOf(entry.getKey())) + ":" + toJson(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(GraphControlServer::toJson)
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        }
        return toJson(String.valueOf(value));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    /** 读取 HTTP 请求体全部内容。 */
    private static String readBody(HttpExchange exchange) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    /** 从简单 JSON 字符串中提取 key-value（支持嵌套引号内的逗号）。 */
    private static Map<String, String> parseJsonStringMap(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return result;
        // 状态机解析:跳过引号内的逗号
        String trimmed = json.strip();
        if (trimmed.startsWith("{")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("}")) trimmed = trimmed.substring(0, trimmed.length() - 1);

        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (inQuotes) {
                if (c == '\\') {
                    current.append(c);
                    if (i + 1 < trimmed.length()) {
                        current.append(trimmed.charAt(++i));
                    }
                } else if (c == quoteChar) {
                    inQuotes = false;
                    current.append(c);
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"' || c == '\'') {
                    inQuotes = true;
                    quoteChar = c;
                    current.append(c);
                } else if (c == ',') {
                    tokens.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
        }
        if (current.length() > 0) tokens.add(current.toString());

        for (String token : tokens) {
            String[] kv = token.split(":", 2);
            if (kv.length == 2) {
                String key = kv[0].trim().replaceAll("^\"|\"$", "");
                String value = kv[1].trim().replaceAll("^\"|\"$", "");
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * 从 JSON 数组字符串中解析语句列表。
     * 格式: [{"cypher":"...","branch":"main"},...]（简易解析,支持嵌套引号）。
     */
    private static List<Map<String, String>> parseStatementsArray(String json) {
        List<Map<String, String>> result = new ArrayList<>();
        if (json == null || json.isBlank()) return result;
        String trimmed = json.strip();
        // 找到 "statements" 键后面的数组
        int idx = trimmed.indexOf("\"statements\"");
        if (idx < 0) {
            // 可能整个 body 就是一个数组
            idx = trimmed.indexOf("[");
        } else {
            idx = trimmed.indexOf("[", idx);
        }
        if (idx < 0) return result;
        String arrayStr = trimmed.substring(idx);

        // 逐个提取 {...} 块
        int depth = 0;
        int start = -1;
        for (int i = 0; i < arrayStr.length(); i++) {
            char c = arrayStr.charAt(i);
            if (c == '[' && depth == 0) { depth = 1; start = -1; continue; }
            if (c == '{' && depth >= 1) { depth++; if (start < 0) start = i; }
            if (c == '}' && depth > 1) { depth--; if (depth == 1 && start >= 0) {
                String obj = arrayStr.substring(start, i + 1);
                result.add(parseJsonStringMap(obj));
                start = -1;
            }}
            if (c == ']' && depth == 1) break;
        }
        return result;
    }

    /**
     * 从 JSON 节点数组字符串中解析出 CREATE 语句。
     * 格式: [{"label":"Person","name":"Alice","age":30},...]
     */
    private static List<String> parseNodeImportStatements(String nodesJson) {
        List<String> statements = new ArrayList<>();
        if (nodesJson == null || nodesJson.isBlank()) return statements;
        String trimmed = nodesJson.strip();
        if (trimmed.startsWith("[")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("]")) trimmed = trimmed.substring(0, trimmed.length() - 1);

        // 逐个提取 {...} 块
        int depth = 0;
        int start = -1;
        List<String> nodeObjs = new ArrayList<>();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '{') { depth++; if (start < 0) start = i; }
            if (c == '}') { depth--; if (depth == 0 && start >= 0) {
                nodeObjs.add(trimmed.substring(start, i + 1));
                start = -1;
            }}
        }

        for (String nodeObj : nodeObjs) {
            Map<String, String> props = parseJsonStringMap(nodeObj);
            String label = props.remove("label");
            if (label == null || label.isBlank()) label = "Node";
            StringBuilder sb = new StringBuilder("CREATE (n:");
            sb.append(label).append(" {");
            boolean first = true;
            for (var entry : props.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(entry.getKey()).append(": ");
                String val = entry.getValue();
                // 尝试解析为数字
                try {
                    long l = Long.parseLong(val);
                    sb.append(l);
                } catch (NumberFormatException nf) {
                    try {
                        double d = Double.parseDouble(val);
                        sb.append(d);
                    } catch (NumberFormatException nf2) {
                        sb.append("'").append(val.replace("'", "\\'")).append("'");
                    }
                }
                first = false;
            }
            sb.append("})");
            statements.add(sb.toString());
        }
        return statements;
    }
}
