package com.zifang.z.graph.bolt;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zifang.z.graph.api.GraphCommit;
import com.zifang.z.graph.core.GraphMetaService;
import com.zifang.z.graph.core.GraphQueryService;
import com.zifang.z.graph.core.GraphVersionStore;

import java.io.IOException;
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
        server.createContext("/query", this::handleQuery);
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
        writeJson(exchange, 200, body);
    }

    private void handleBranches(HttpExchange exchange) throws IOException {
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
        writeJson(exchange, 200, commits);
    }

    private void handleQuery(HttpExchange exchange) throws IOException {
        Map<String, String> parameters = queryParameters(exchange.getRequestURI());
        String cypher = parameters.get("cypher");
        if (cypher == null || cypher.isBlank()) {
            writeJson(exchange, 400, Map.of("error", "Missing query parameter: cypher"));
            return;
        }
        String branch = parameters.getOrDefault("branch", "main");
        String commit = parameters.get("commit");
        List<Map<String, Object>> rows = commit == null
                ? queryService.queryBranch(branch, cypher)
                : queryService.queryCommit(commit, cypher);
        writeJson(exchange, 200, rows);
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
}
