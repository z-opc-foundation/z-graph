package com.zifang.z.graph.bolt;

import com.sun.net.httpserver.HttpExchange;
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
        server.createContext("/health", this::handleHealth);
        server.createContext("/meta/branches", this::handleBranches);
        server.createContext("/meta/commits", this::handleCommits);
        server.createContext("/meta/schema", this::handleSchema);
        server.createContext("/meta/stats", this::handleStats);
        server.createContext("/query", this::handleQuery);
        // OPTIONS 预检 + CORS 头:允许浏览器前端直接访问此控制面
        server.createContext("/options", exchange -> writeNoContent(exchange));
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

    private void handleQuery(HttpExchange exchange) throws IOException {
        applyCorsHeaders(exchange);
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
                writeJson(exchange, 200, rows);
            } else if (commit != null) {
                var graphCheckout = repository.checkout(commit);
                List<Map<String, Object>> rows = new CypherEngine(graphCheckout.getStore(), repository)
                        .execute(cypher, Map.of());
                writeJson(exchange, 200, rows);
            } else {
                var graphCheckout = repository.checkoutBranch(branch);
                List<Map<String, Object>> rows = new CypherEngine(graphCheckout.getStore(), repository)
                        .execute(cypher, Map.of());
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
}
