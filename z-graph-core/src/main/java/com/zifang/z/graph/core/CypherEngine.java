package com.zifang.z.graph.core;

import com.zifang.z.graph.api.*;

import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * Cypher 查询引擎 — 支持 OpenCypher 子集的查询解析与执行。
 * <p>
 * POC 阶段实现以下语句:
 * - CREATE (n:Label {k:v})                  → 创建节点
 * - CREATE (a)-[:TYPE {k:v}]->(b)           → 创建边
 * - MATCH (n:Label) WHERE n.prop = val RETURN n, n.prop AS alias  → 查询
 * - MATCH (a)-[r:TYPE]->(b) RETURN ...       → 关系查询
 * - MATCH (n:Label) SET n.k = v              → 更新属性
 * - MATCH (n:Label) DELETE n                 → 删除节点
 * - MATCH (n:Label) DETACH DELETE n          → 删除节点及其边
 * - RETURN <expr> AS alias                  → 投影
 * <p>
 * 表达式支持: 属性访问(n.name)、字面量、比较(=, !=, <, >, <=, >=)
 */
public class CypherEngine {

    private final GraphStore store;

    public CypherEngine(GraphStore store) {
        this.store = store;
    }

    /**
     * 执行 Cypher 语句，返回结果行列表。
     * 每行是一个 Map<String, Object>（列名 → 值）。
     */
    public List<Map<String, Object>> execute(String cypher) {
        String trimmed = cypher.trim();
        if (trimmed.isEmpty()) { throw new CypherException("Empty Cypher statement"); }

        String upper = trimmed.toUpperCase();

        // CREATE / DROP 二级关键字分发：CREATE TAG/CREATE EDGE/CREATE TAG INDEX/CREATE INDEX
        // 与 CREATE (node pattern) 共用前缀
        if (upper.startsWith("CREATE")) {
            String rest = trimmed.substring(6).trim();
            String restUpper = rest.toUpperCase();
            if (restUpper.startsWith("TAG INDEX") || restUpper.startsWith("TAGINDEX")
                    || restUpper.startsWith("EDGE INDEX") || restUpper.startsWith("EDGEINDEX")
                    || restUpper.startsWith("INDEX")) {
                return executeCreateIndex(trimmed);
            }
            if (restUpper.startsWith("TAG ")) {
                return executeCreateTag(trimmed);
            }
            if (restUpper.startsWith("EDGE ") || restUpper.startsWith("EDGE TYPE")
                    || restUpper.startsWith("EDGETYPE")) {
                return executeCreateEdgeType(trimmed);
            }
            return executeCreate(trimmed);
        }
        if (upper.startsWith("ALTER")) {
            String rest = trimmed.substring(5).trim();
            String restUpper = rest.toUpperCase();
            if (restUpper.startsWith("TAG ")) {
                return executeAlterTag(trimmed);
            }
            if (restUpper.startsWith("EDGE ") || restUpper.startsWith("EDGETYPE")) {
                return executeAlterEdgeType(trimmed);
            }
            throw new CypherException("Unsupported ALTER statement: " + trimmed);
        }
        if (upper.startsWith("REBUILD")) {
            return executeRebuildIndex(trimmed);
        }
        if (upper.startsWith("EXPLAIN")) {
            return executeExplain(trimmed);
        }
        if (upper.startsWith("DESCRIBE") || upper.startsWith("DESC")) {
            return executeDescribe(trimmed);
        }
        if (upper.startsWith("DROP")) {
            String rest = trimmed.substring(4).trim();
            String restUpper = rest.toUpperCase();
            if (restUpper.startsWith("TAG INDEX") || restUpper.startsWith("EDGE INDEX")
                    || restUpper.startsWith("INDEX")) {
                return executeDropIndex(trimmed);
            }
            if (restUpper.startsWith("TAG ")) {
                return executeDropTag(trimmed);
            }
            if (restUpper.startsWith("EDGE ") || restUpper.startsWith("EDGETYPE")) {
                return executeDropEdgeType(trimmed);
            }
            throw new CypherException("Unsupported DROP statement: " + trimmed);
        }
        if (upper.startsWith("MATCH")) {
            return executeMatch(trimmed);
        } else if (upper.startsWith("OPTIONAL MATCH")) {
            return executeOptionalMatch(trimmed);
        } else if (upper.startsWith("MERGE")) {
            return executeMerge(trimmed);
        } else if (upper.startsWith("RETURN")) {
            return executeReturn(trimmed);
        } else if (upper.startsWith("UNWIND")) {
            return executeUnwind(trimmed);
        } else if (upper.startsWith("SHOW")) {
            return executeShow(trimmed);
        } else {
            throw new CypherException("Unsupported Cypher statement: " + trimmed);
        }
    }

    /** 使用 Bolt RUN 提供的参数执行查询，当前参数先展开为安全的 Cypher 字面量。 */
    public List<Map<String, Object>> execute(String cypher, Map<String, Object> parameters) {
        String expanded = cypher;
        if (parameters != null) {
            for (Map.Entry<String, Object> parameter : parameters.entrySet()) {
                String token = "\\$" + Pattern.quote(parameter.getKey()) + "\\b";
                expanded = expanded.replaceAll(token, Matcher.quoteReplacement(toCypherLiteral(parameter.getValue())));
            }
        }
        return execute(expanded);
    }

    private String toCypherLiteral(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Collection<?> collection) {
            return "[" + collection.stream().map(this::toCypherLiteral).collect(Collectors.joining(", ")) + "]";
        }
        return "'" + value.toString().replace("'", "\\\\'") + "'";
    }

    // ==================== RETURN literal（兼容 T1）====================

    private List<Map<String, Object>> executeReturn(String cypher) {
        // RETURN expr AS alias, expr2 AS alias2, ...
        String body = cypher.substring(6).trim();
        return List.of(parseReturnRow(body));
    }

    private Map<String, Object> parseReturnRow(String returnBody) {
        Map<String, Object> row = new LinkedHashMap<>();
        List<String> exprs = splitByComma(returnBody);
        int idx = 0;
        for (String expr : exprs) {
            expr = expr.trim();
            // 检查 AS alias
            int asPos = findKeywordPosition(expr, " AS ");
            if (asPos > 0) {
                String value = expr.substring(0, asPos).trim();
                String alias = expr.substring(asPos + 4).trim();
                row.put(alias, evaluateLiteral(value));
            } else {
                String alias = "column_" + (idx + 1);
                row.put(alias, evaluateLiteral(expr));
            }
            idx++;
        }
        return row;
    }

    // ==================== CREATE ====================

    private List<Map<String, Object>> executeCreate(String cypher) {
        String body = cypher.substring(6).trim();
        // CREATE (a:Label {k:v, k2:v2})
        // CREATE (a)-[:TYPE]->(b)
        // CREATE (a:Label {k:v}), (b:Label {k:v}), (a)-[:TYPE]->(b)

        // 简化处理：先拆分逗号分隔的子句
        List<String> parts = splitByCommaPreserveParens(body);
        List<Map<String, Object>> results = new ArrayList<>();

        // 收集已创建的节点引用（变量名 → nodeId）
        Map<String, Long> nodeRefs = new LinkedHashMap<>();

        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("(") && part.contains(")-[") && part.contains("]->")) {
                // 边创建: (a)-[:TYPE {k:v}]->(b)
                results.add(executeCreateEdge(part, nodeRefs));
            } else if (part.startsWith("(") || part.startsWith("n:") || part.startsWith("a:") || part.startsWith("b:")) {
                // 节点创建
                Map<String, Object> result = executeCreateNode(part, nodeRefs);
                results.add(result);
            }
        }
        return results;
    }

    private Map<String, Object> executeCreateNode(String pattern, Map<String, Long> nodeRefs) {
        // (varName:Label {k:v}) 或 (:Label {k:v})
        Matcher m = Pattern.compile("\\(\\s*(\\w*)\\s*(?::(\\w+))?\\s*(?:\\{(.+?)\\})?\\s*\\)")
                .matcher(pattern);
        if (!m.find()) { throw new CypherException("Invalid node pattern: " + pattern); }

        String varName = m.group(1);
        String label = m.group(2);
        String propsStr = m.group(3);

        Map<String, Object> props = parseProperties(propsStr);
        Node node = store.addNode(label, props);

        if (varName != null && !varName.isEmpty()) {
            nodeRefs.put(varName, node.getId());
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", node.getId());
        row.put("label", label);
        row.putAll(props);
        return row;
    }

    private Map<String, Object> executeCreateEdge(String pattern, Map<String, Long> nodeRefs) {
        // (a)-[:TYPE {k:v}]->(b)
        Matcher m = Pattern.compile(
                "\\(\\s*(\\w+)\\s*\\)-\\[\\s*(\\w*)\\s*:\\s*(\\w+)\\s*(?:\\{(.+?)\\})?\\s*\\]->\\(\\s*(\\w+)\\s*\\)")
                .matcher(pattern);
        if (!m.find()) { throw new CypherException("Invalid edge pattern: " + pattern); }

        String fromVar = m.group(1);
        String edgeType = m.group(3);
        String propsStr = m.group(4);
        String toVar = m.group(5);

        Long fromId = nodeRefs.get(fromVar);
        Long toId = nodeRefs.get(toVar);
        if (fromId == null) { throw new CypherException("Undefined node variable: " + fromVar); }

        if (toId == null) { throw new CypherException("Undefined node variable: " + toVar); }


        Map<String, Object> props = parseProperties(propsStr);
        Edge edge = store.addEdge(edgeType, fromId, toId, props);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", edge.getId());
        row.put("type", edgeType);
        row.put("start", fromId);
        row.put("end", toId);
        row.putAll(props);
        return row;
    }

    // ==================== MERGE ====================

    /** MERGE 的最小节点 upsert 语义：按标签和全部属性精确匹配，不存在时创建。 */
    private List<Map<String, Object>> executeMerge(String cypher) {
        String body = cypher.substring(5).trim();
        String returnClause = null;
        String upperBody = body.toUpperCase();
        int returnPos = upperBody.indexOf(" RETURN ");
        if (returnPos >= 0) {
            returnClause = body.substring(returnPos + 8).trim();
            body = body.substring(0, returnPos).trim();
        }

        Matcher matcher = Pattern.compile("\\(\\s*(\\w*)\\s*(?::(\\w+))?\\s*(?:\\{(.+?)\\})?\\s*\\)")
                .matcher(body);
        if (!matcher.matches()) {
            throw new CypherException("MERGE currently supports one node pattern: " + body);
        }
        String variable = matcher.group(1);
        String label = matcher.group(2);
        Map<String, Object> properties = parseProperties(matcher.group(3));

        Node node = null;
        List<Long> candidates = label == null
                ? store.getAllNodeIds() : store.getNodeIdsByLabel(label);
        for (Long candidate : candidates) {
            Node current = store.getNode(candidate);
            if (current != null && (label == null || current.hasLabel(label))
                    && properties.entrySet().stream()
                    .allMatch(entry -> Objects.equals(entry.getValue(), current.get(entry.getKey())))) {
                node = current;
                break;
            }
        }
        if (node == null) {
            node = store.addNode(label, properties);
        }

        MatchBinding binding = new MatchBinding();
        if (!variable.isEmpty()) binding.variables.put(variable, node);
        if (returnClause != null && !returnClause.isEmpty()) {
            return executeReturnProjection(returnClause, List.of(binding));
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", node.getId());
        row.put("label", label);
        row.putAll(node.getProperties());
        return List.of(row);
    }

    // ==================== MATCH ====================

    private List<Map<String, Object>> executeMatch(String cypher) {
        // 拆分 MATCH / WHERE / SET / DELETE / RETURN
        String upper = cypher.toUpperCase();
        String pattern = extractClause(cypher, upper, "MATCH", "WHERE|SET|DELETE|DETACH|RETURN|ORDER|LIMIT|SKIP|WITH|UNWIND");
        String whereClause = extractClause(cypher, upper, "WHERE", "SET|DELETE|DETACH|RETURN|ORDER|LIMIT|SKIP|WITH|UNWIND");
        String setClause = extractClause(cypher, upper, "SET", "DELETE|DETACH|RETURN|ORDER|LIMIT|SKIP|WITH");
        String deleteClause = extractClause(cypher, upper, "DELETE", "RETURN|ORDER|LIMIT|SKIP|WITH");
        String detachDelete = extractClause(cypher, upper, "DETACH DELETE", "RETURN|ORDER|LIMIT|SKIP|WITH");
        String returnClause = extractClause(cypher, upper, "RETURN", "ORDER|LIMIT|SKIP|WITH");

        // 如果有 DETACH DELETE，用它覆盖 DELETE
        if (detachDelete != null) { deleteClause = null; }


        // 1. 解析 MATCH 模式，找到候选节点
        List<MatchBinding> bindings;
        // 优化路径：单节点模式 + WHERE 等值 + 索引命中时直接走索引
        IndexedSeed seed = tryIndexSeed(pattern, whereClause);
        if (seed != null) {
            bindings = seed.bindings;
        } else {
            bindings = resolvePattern(pattern);
        }

        // 2. 应用 WHERE 过滤
        if (whereClause != null && !whereClause.trim().isEmpty()) {
            bindings = bindings.stream()
                    .filter(b -> evaluateWhere(whereClause.trim(), b))
                    .collect(Collectors.toList());
        }

        // 3. 如果有 SET，执行更新
        if (setClause != null && !setClause.trim().isEmpty()) {
            executeSet(setClause.trim(), bindings);
        }

        // 4. 如果有 DELETE，执行删除
        if (deleteClause != null && !deleteClause.trim().isEmpty()) {
            return executeDelete(deleteClause.trim(), bindings, false);
        }
        if (detachDelete != null) {
            return executeDelete(detachDelete.trim(), bindings, true);
        }

        // 5. 如果有 RETURN，执行投影
        if (returnClause != null && !returnClause.trim().isEmpty()) {
            return executeReturnProjection(returnClause.trim(), bindings);
        }

        // 默认返回所有绑定变量
        return bindings.stream()
                .map(MatchBinding::toResultMap)
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> executeDelete(String deleteExpr, List<MatchBinding> bindings, boolean detach) {
        Set<Long> nodeIdsToDelete = new LinkedHashSet<>();
        for (MatchBinding b : bindings) {
            for (Map.Entry<String, Object> e : b.variables.entrySet()) {
                if (e.getValue() instanceof Node n) {
                    nodeIdsToDelete.add(n.getId());
                }
            }
        }
        for (long id : nodeIdsToDelete) {
            if (detach) { store.removeNode(id); }

            else store.removeNode(id);
        }
        return List.of(Map.of("deleted", nodeIdsToDelete.size()));
    }

    private void executeSet(String setExpr, List<MatchBinding> bindings) {
        // SET n.prop = value, n.prop2 = value2
        List<String> assignments = splitByComma(setExpr);
        for (MatchBinding b : bindings) {
            for (String assignment : assignments) {
                String[] kv = assignment.split("\\s*=\\s*", 2);
                if (kv.length != 2) { throw new CypherException("Invalid SET clause: " + assignment); }

                String lhs = kv[0].trim();
                String rhs = kv[1].trim();

                // 解析 n.prop 形式
                int dotPos = lhs.indexOf('.');
                if (dotPos <= 0) { throw new CypherException("Invalid SET target: " + lhs); }

                String varName = lhs.substring(0, dotPos);
                String propName = lhs.substring(dotPos + 1);

                Object value = resolveValue(rhs);
                Object target = b.variables.get(varName);
                if (target instanceof Node node) {
                    node.set(propName, value);
                } else if (target instanceof Edge edge) {
                    edge.set(propName, value);
                }
            }
        }
    }

    // ==================== 模式解析 ====================

    private List<MatchBinding> resolvePattern(String pattern) {
        pattern = pattern.trim();
        // 处理多模式: (n:Label)-[:TYPE]->(m), (k:Label2)
        List<String> parts = splitByCommaPreserveParens(pattern);

        List<MatchBinding> results = new ArrayList<>();
        results.add(new MatchBinding());

        for (String part : parts) {
            part = part.trim();
            if (part.contains("->") || part.contains("<-")) {
                if (isVariableLengthPattern(part)) {
                    results = expandVariableEdgePattern(part, results);
                } else {
                    results = expandEdgePattern(part, results);
                }
            } else {
                results = expandNodePattern(part, results);
            }
        }
        return results;
    }

    private static boolean isVariableLengthPattern(String pattern) {
        return Pattern.compile("\\)\\s*-\\s*\\[[^\\]]*\\*[^\\]]*\\]\\s*->\\s*\\(")
                .matcher(pattern).find();
    }

    /**
     * 变长路径匹配：{@code (a)-[*min..max]->(b)} 或 {@code (a:Label)-[:TYPE*min..max]->(b:Label)}。
     * 当前实现对每个起始节点做 BFS，记录最短路径到达的可达终点。
     */
    private List<MatchBinding> expandVariableEdgePattern(String pattern, List<MatchBinding> existing) {
        Matcher m = Pattern.compile(
                "\\(\\s*(?<fromVar>\\w+)\\s*(?::\\s*(?<fromLabel>\\w+))?\\s*(?:\\{[^}]*\\})?\\s*\\)"
                        + "\\s*-\\s*\\[\\s*(?<relVar>\\w*)\\s*(?::\\s*(?<edgeType>\\w+))?\\s*\\*\\s*"
                        + "(?<minHops>\\d*)\\s*\\.\\.?\\s*(?<maxHops>\\d*)\\s*\\]"
                        + "\\s*->\\s*\\(\\s*(?<toVar>\\w+)\\s*(?::\\s*(?<toLabel>\\w+))?\\s*(?:\\{[^}]*\\})?\\s*\\)")
                .matcher(pattern);
        if (!m.find()) {
            throw new CypherException("Invalid variable-length pattern: " + pattern);
        }
        String fromVar = m.group("fromVar");
        String fromLabel = m.group("fromLabel");
        String relVar = m.group("relVar");
        String edgeType = m.group("edgeType");
        int minHops = m.group("minHops") == null || m.group("minHops").isEmpty()
                ? 1 : Integer.parseInt(m.group("minHops"));
        int maxHops = m.group("maxHops") == null || m.group("maxHops").isEmpty()
                ? Math.max(minHops, 1) : Integer.parseInt(m.group("maxHops"));
        String toVar = m.group("toVar");
        String toLabel = m.group("toLabel");
        if (minHops > maxHops) {
            throw new CypherException("minHops > maxHops: " + pattern);
        }
        List<MatchBinding> results = new ArrayList<>();
        for (MatchBinding base : existing) {
            List<Long> starts = base.variables.containsKey(fromVar)
                    ? List.of(((Node) base.variables.get(fromVar)).getId())
                    : (fromLabel != null ? store.getNodeIdsByLabel(fromLabel) : store.getAllNodeIds());
            for (long startId : starts) {
                Node startNode = store.getNode(startId);
                if (startNode == null) continue;
                List<long[]> paths = boundedBfs(startId, maxHops, edgeType);
                for (long[] path : paths) {
                    if (path.length - 1 < minHops) continue;
                    long endId = path[path.length - 1];
                    Node endNode = store.getNode(endId);
                    if (endNode == null) continue;
                    if (toLabel != null && !endNode.hasLabel(toLabel)) continue;
                    MatchBinding copy = base.copy();
                    copy.variables.put(fromVar, startNode);
                    copy.variables.put(toVar, endNode);
                    if (relVar != null && !relVar.isEmpty()) {
                        copy.variables.put(relVar, pathSummary(path, edgeType));
                    }
                    results.add(copy);
                }
            }
        }
        return results;
    }

    /** BFS，从 start 出发，沿 edgeType（可选）行走最多 maxHops 步，返回所有可达终点的最短路径数组。 */
    private List<long[]> boundedBfs(long startId, int maxHops, String edgeType) {
        List<long[]> paths = new ArrayList<>();
        if (maxHops == 0) {
            paths.add(new long[]{startId});
            return paths;
        }
        java.util.Deque<long[]> queue = new java.util.ArrayDeque<>();
        queue.add(new long[]{startId});
        while (!queue.isEmpty()) {
            long[] path = queue.poll();
            int depth = path.length - 1;
            if (depth >= maxHops) continue;
            Node tail = store.getNode(path[path.length - 1]);
            if (tail == null) continue;
            for (Edge edge : store.getOutEdges(tail.getId())) {
                if (edgeType != null && !edgeType.isEmpty() && !edgeType.equals(edge.getType())) continue;
                long next = edge.getEndNodeId();
                long[] newPath = Arrays.copyOf(path, path.length + 1);
                newPath[newPath.length - 1] = next;
                paths.add(newPath);
                if (depth + 1 < maxHops) {
                    queue.add(newPath);
                }
            }
        }
        return paths;
    }

    private List<Map<String, Object>> pathSummary(long[] path, String edgeType) {
        List<Map<String, Object>> edges = new ArrayList<>();
        for (int i = 0; i < path.length - 1; i++) {
            for (Edge e : store.getOutEdges(path[i])) {
                if (e.getEndNodeId() == path[i + 1]
                        && (edgeType == null || edgeType.isEmpty() || edgeType.equals(e.getType()))) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("type", e.getType());
                    entry.put("from", e.getStartNodeId());
                    entry.put("to", e.getEndNodeId());
                    edges.add(entry);
                    break;
                }
            }
        }
        return edges;
    }

    private List<MatchBinding> expandNodePattern(String pattern, List<MatchBinding> existing) {
        // (:Label) 或 (n:Label) 或 (n:Label {prop: value})
        Matcher m = Pattern.compile("\\(\\s*(\\w*)\\s*(?::(\\w+))?\\s*(?:\\{(.+?)\\})?\\s*\\)")
                .matcher(pattern);
        if (!m.find()) { throw new CypherException("Invalid node pattern: " + pattern); }

        String varName = m.group(1);
        String label = m.group(2);

        List<Long> candidateIds;
        if (label != null) {
            candidateIds = store.getNodeIdsByLabel(label);
        } else {
            // 全表扫描，取所有节点
            candidateIds = new ArrayList<>(store.getAllNodeIds());
        }

        List<MatchBinding> results = new ArrayList<>();
        for (MatchBinding base : existing) {
            for (long nodeId : candidateIds) {
                Node node = store.getNode(nodeId);
                if (node == null) { continue; }
                if (varName != null && !varName.isEmpty()) {
                    MatchBinding copy = base.copy();
                    copy.variables.put(varName, node);
                    results.add(copy);
                } else {
                    results.add(base);
                }
            }
        }
        return results;
    }

    private List<MatchBinding> expandEdgePattern(String pattern, List<MatchBinding> existing) {
        // (a)-[:TYPE]->(b) 或 (a)-[r:TYPE]->(b) 或 (a)-[:TYPE {k:v}]->(b)
        Matcher m = Pattern.compile(
                "\\(\\s*(\\w+)\\s*\\)-\\[\\s*(\\w*)\\s*:\\s*(\\w+)\\s*(?:\\{(.+?)\\})?\\s*\\]->\\(\\s*(\\w+)\\s*\\)")
                .matcher(pattern);
        if (!m.find()) { throw new CypherException("Invalid edge pattern: " + pattern); }

        String fromVar = m.group(1);
        String relVar = m.group(2);
        String edgeType = m.group(3);
        String propsStr = m.group(4);
        String toVar = m.group(5);

        List<Edge> candidates;
        if (edgeType != null && !edgeType.isEmpty()) {
            candidates = store.getEdgesByType(edgeType);
        } else {
            candidates = new ArrayList<>();
            // 收集所有边
            for (long nodeId : store.getAllNodeIds()) {
                candidates.addAll(store.getOutEdges(nodeId));
            }
        }

        List<MatchBinding> results = new ArrayList<>();
        for (MatchBinding base : existing) {
            for (Edge edge : candidates) {
                Node fromNode = (base.variables.containsKey(fromVar)) ?
                        (Node) base.variables.get(fromVar) : store.getNode(edge.getStartNodeId());
                Node toNode = store.getNode(edge.getEndNodeId());

                if (fromNode == null || toNode == null) { continue; }
                if (base.variables.containsKey(fromVar) && !base.variables.get(fromVar).equals(fromNode)) continue;


                MatchBinding copy = base.copy();
                // 确保 fromVar 也在绑定中（for MATCH (a)-[r]->(b)）
                if (!copy.variables.containsKey(fromVar)) {
                    copy.variables.put(fromVar, fromNode);
                }
                copy.variables.put(toVar, toNode);
                if (relVar != null && !relVar.isEmpty()) {
                    copy.variables.put(relVar, edge);
                }
                results.add(copy);
            }
        }
        return results;
    }

    // ==================== WHERE 过滤 ====================

    private boolean evaluateWhere(String whereClause, MatchBinding binding) {
        // 支持 AND 组合的简单比较: n.name = 'Alice' AND n.age > 20
        String[] conditions = whereClause.split("\\s+AND\\s+");
        for (String cond : conditions) {
            cond = cond.trim();
            if (!evaluateCondition(cond, binding)) { return false; }
        }
        return true;
    }

    private boolean evaluateCondition(String condition, MatchBinding binding) {
        // EXISTS { MATCH (n)-[r]->(m) ... } 子查询
        String upper = condition.toUpperCase().trim();
        if (upper.startsWith("EXISTS")) {
            return evaluateExistsSubquery(condition.trim(), binding);
        }
        if (upper.startsWith("NOT EXISTS")) {
            return !evaluateExistsSubquery(condition.trim().substring("NOT ".length()), binding);
        }
        // n.prop op value 或 n.prop IS NULL
        String[] ops = {"!=", ">=", "<=", "=", ">", "<"};
        for (String op : ops) {
            int pos = condition.indexOf(op);
            if (pos > 0) {
                String lhs = condition.substring(0, pos).trim();
                String rhs = condition.substring(pos + op.length()).trim();

                Object lhsValue = resolveExpression(lhs, binding);
                Object rhsValue = resolveValue(rhs);

                return compareValues(lhsValue, rhsValue, op);
            }
        }
        // IS NULL / IS NOT NULL
        if (condition.toUpperCase().contains("IS NULL")) {
            String varName = condition.replaceAll("(?i)\\s*IS\\s*NULL", "").trim();
            return resolveExpression(varName, binding) == null;
        }
        if (condition.toUpperCase().contains("IS NOT NULL")) {
            String varName = condition.replaceAll("(?i)\\s*IS\\s*NOT\\s*NULL", "").trim();
            return resolveExpression(varName, binding) != null;
        }
        throw new CypherException("Cannot parse WHERE condition: " + condition);
    }

    /**
     * 支持形如 {@code EXISTS { (var)-[:TYPE]->(m) }} 或
     * {@code EXISTS { MATCH (var)-[]->(m) }} 的子查询。
     * 当前实现要求子查询第一个节点必须绑定到外层 binding 中的变量。
     */
    private boolean evaluateExistsSubquery(String expression, MatchBinding binding) {
        String body = expression.trim();
        if (body.toUpperCase().startsWith("EXISTS")) {
            body = body.substring("EXISTS".length()).trim();
        }
        int braceStart = body.indexOf('{');
        int braceEnd = body.lastIndexOf('}');
        if (braceStart < 0 || braceEnd < 0 || braceEnd <= braceStart) {
            throw new CypherException("EXISTS requires { ... } body: " + expression);
        }
        String inner = body.substring(braceStart + 1, braceEnd).trim();
        // 去掉内部 MATCH 关键字
        if (inner.toUpperCase().startsWith("MATCH")) {
            inner = inner.substring("MATCH".length()).trim();
        }
        // 简单形式：单节点绑定 + 任意关系。例如 "(n)-[]->(m)"
        Matcher anchorMatcher = Pattern.compile(
                "\\(\\s*(\\w+)\\s*\\)\\s*-\\s*\\[\\s*\\]\\s*->\\s*\\(").matcher(inner);
        if (anchorMatcher.find()) {
            String anchor = anchorMatcher.group(1);
            Object target = binding.variables.get(anchor);
            if (target instanceof Node node) {
                return !store.getOutEdges(node.getId()).isEmpty();
            }
            return false;
        }
        Matcher incomingMatcher = Pattern.compile(
                "\\(\\s*(\\w+)\\s*\\)\\s*<-\\s*\\[\\s*\\]\\s*-\\s*\\(").matcher(inner);
        if (incomingMatcher.find()) {
            String anchor = incomingMatcher.group(1);
            Object target = binding.variables.get(anchor);
            if (target instanceof Node node) {
                return !store.getInEdges(node.getId()).isEmpty();
            }
            return false;
        }
        // 退路：用 resolvePattern 完整展开子查询，看结果是否非空
        List<MatchBinding> sub = resolvePattern(inner);
        return !sub.isEmpty();
    }

    private Object resolveExpression(String expr, MatchBinding binding) {
        expr = expr.trim();
        int dotPos = expr.indexOf('.');
        if (dotPos > 0) {
            String varName = expr.substring(0, dotPos);
            String prop = expr.substring(dotPos + 1);
            Object target = binding.variables.get(varName);
            if (target instanceof Node node) { return node.get(prop); }

            if (target instanceof Edge edge) { return edge.get(prop); }

        }
        // 先查 binding 中的变量引用（如 x、n）
        Object varVal = binding.variables.get(expr);
        if (varVal != null) { return varVal; }

        // 再当字面量解析（如 42、'hello'、true）
        return resolveValue(expr);
    }

    // ==================== RETURN 投影 ====================

    private List<Map<String, Object>> executeReturnProjection(String returnExpr, List<MatchBinding> bindings) {
        List<Projection> projections = parseProjections(returnExpr);
        boolean hasAggregate = projections.stream().anyMatch(p -> p.aggregate != null);
        if (!hasAggregate) {
            List<Map<String, Object>> results = new ArrayList<>();
            for (MatchBinding binding : bindings) {
                results.add(projectRow(projections, binding));
            }
            return results;
        }
        return aggregateProjections(projections, bindings);
    }

    private Map<String, Object> projectRow(String returnExpr, MatchBinding binding) {
        return projectRow(parseProjections(returnExpr), binding);
    }

    private Map<String, Object> projectRow(List<Projection> projections, MatchBinding binding) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (Projection projection : projections) {
            row.put(projection.alias, projection.aggregate == null
                    ? resolveExpression(projection.expr, binding)
                    : projection.aggregate.compute(List.of(binding)));
        }
        return row;
    }

    private List<Projection> parseProjections(String returnExpr) {
        List<Projection> projections = new ArrayList<>();
        List<String> exprs = splitByComma(returnExpr);
        int idx = 0;
        for (String expr : exprs) {
            expr = expr.trim();
            int asPos = findKeywordPosition(expr, " AS ");
            String alias;
            String valueExpr;
            if (asPos > 0) {
                valueExpr = expr.substring(0, asPos).trim();
                alias = expr.substring(asPos + 4).trim();
            } else {
                valueExpr = expr;
                alias = valueExpr.replace('.', '_').replace(' ', '_');
                if (alias.isEmpty()) alias = "column_" + (idx + 1);
            }
            AggregateCall aggregate = parseAggregate(valueExpr);
            projections.add(new Projection(valueExpr, alias, aggregate));
            idx++;
        }
        return projections;
    }

    /** 聚合投影：按非聚合列分组，再对聚合列计算。空集合但有聚合时仍返回一行。 */
    private List<Map<String, Object>> aggregateProjections(List<Projection> projections, List<MatchBinding> bindings) {
        Map<String, List<MatchBinding>> groups = new LinkedHashMap<>();
        Map<String, Map<String, Object>> groupKeys = new LinkedHashMap<>();
        List<Projection> nonAggregates = projections.stream()
                .filter(p -> p.aggregate == null)
                .toList();
        for (MatchBinding binding : bindings) {
            StringBuilder key = new StringBuilder();
            Map<String, Object> keyMap = new LinkedHashMap<>();
            for (Projection projection : nonAggregates) {
                Object value = resolveExpression(projection.expr, binding);
                key.append(value == null ? "<null>" : value.toString()).append('');
                keyMap.put(projection.alias, value);
            }
            String keyStr = key.toString();
            groups.computeIfAbsent(keyStr, ignored -> new ArrayList<>()).add(binding);
            groupKeys.putIfAbsent(keyStr, keyMap);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        if (groups.isEmpty() && !bindings.isEmpty()) {
            return results;
        }
        if (groups.isEmpty()) {
            // 纯聚合且无输入：仍返回一行，让 count=0 / sum=null 之类语义正确
            Map<String, Object> row = new LinkedHashMap<>();
            for (Projection projection : projections) {
                if (projection.aggregate != null) {
                    row.put(projection.alias, projection.aggregate.compute(List.of()));
                }
            }
            return List.of(row);
        }
        for (Map.Entry<String, List<MatchBinding>> entry : groups.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>(groupKeys.get(entry.getKey()));
            for (Projection projection : projections) {
                if (projection.aggregate != null) {
                    row.put(projection.alias, projection.aggregate.compute(entry.getValue()));
                }
            }
            results.add(row);
        }
        return results;
    }

    /** 解析聚合调用，例如 {@code count(n)} / {@code count(*)} / {@code sum(n.age)} / {@code avg(n.age)}。 */
    private AggregateCall parseAggregate(String expr) {
        Matcher matcher = Pattern.compile("(?i)^(count|sum|avg|min|max)\\s*\\(\\s*(.+?)\\s*\\)$").matcher(expr.trim());
        if (!matcher.matches()) return null;
        String function = matcher.group(1).toLowerCase();
        String inner = matcher.group(2).trim();
        String source = "*".equals(inner) ? null : inner;
        return new AggregateCall(function, source);
    }

    private Object evaluateAggregate(String function, String source, List<MatchBinding> bindings) {
        if (bindings == null) bindings = List.of();
        if ("count".equals(function)) {
            if (source == null) return (long) bindings.size();
            long nonNull = 0;
            for (MatchBinding binding : bindings) {
                if (resolveExpression(source, binding) != null) nonNull++;
            }
            return nonNull;
        }
        List<Object> values = new ArrayList<>();
        for (MatchBinding binding : bindings) {
            Object value = source == null ? binding.variables.values().stream().findFirst().orElse(null)
                    : resolveExpression(source, binding);
            if (value instanceof Number) values.add(value);
        }
        if (values.isEmpty()) return null;
        return switch (function) {
            case "sum" -> values.stream().mapToDouble(v -> ((Number) v).doubleValue()).sum();
            case "avg" -> values.stream().mapToDouble(v -> ((Number) v).doubleValue()).average().orElse(0);
            case "min" -> values.stream().min((a, b) -> compareNumbersOrStrings(a, b)).orElse(null);
            case "max" -> values.stream().max((a, b) -> compareNumbersOrStrings(a, b)).orElse(null);
            default -> null;
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareNumbersOrStrings(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private record Projection(String expr, String alias, AggregateCall aggregate) {
    }

    private final class AggregateCall {
        final String function;
        final String source;

        AggregateCall(String function, String source) {
            this.function = function;
            this.source = source;
        }

        Object compute(List<MatchBinding> bindings) {
            return evaluateAggregate(function, source, bindings);
        }
    }

    // ==================== UNWIND ====================

    private List<Map<String, Object>> executeUnwind(String cypher) {
        // UNWIND [1, 2, 3] AS x RETURN x
        Matcher m = Pattern.compile("(?i)UNWIND\\s+\\[(.+?)\\]\\s+AS\\s+(\\w+)(?:\\s+RETURN\\s+(.+))?")
                .matcher(cypher);
        if (!m.find()) { throw new CypherException("Invalid UNWIND: " + cypher); }

        String listStr = m.group(1);
        String asVar = m.group(2);
        String returnExpr = m.group(3);

        List<String> items = splitByComma(listStr);
        List<Map<String, Object>> results = new ArrayList<>();
        for (String item : items) {
            item = item.trim();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(asVar, evaluateLiteral(item));
            if (returnExpr != null && !returnExpr.trim().isEmpty()) {
                row = projectRow(returnExpr.trim(), new MatchBinding(Map.of(asVar, row.get(asVar))));
            }
            results.add(row);
        }
        return results;
    }

    // ==================== SHOW 管理语句（NebulaGraph 风格）====================

    /**
     * 支持以下管理语句：
     * <ul>
     *     <li>SHOW TAGS</li>
     *     <li>SHOW EDGES</li>
     *     <li>SHOW INDEXES</li>
     *     <li>SHOW TAG <name></li>
     *     <li>SHOW EDGE <name></li>
     * </ul>
     */
    private List<Map<String, Object>> executeShow(String cypher) {
        String body = cypher.substring(4).trim();
        String upperBody = body.toUpperCase();
        if (upperBody.equals("TAGS")) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String tag : store.listTags()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("Name", tag);
                rows.add(row);
            }
            return rows;
        }
        if (upperBody.equals("EDGES")) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String edgeType : store.listEdgeTypes()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("Name", edgeType);
                rows.add(row);
            }
            return rows;
        }
        if (upperBody.equals("STATS")) {
            return executeShowStats();
        }
        if (upperBody.equals("INDEXES")) {
            List<Map<String, Object>> rows = new ArrayList<>();
            if (store instanceof InMemoryGraphStore ims) {
                for (List<String> index : ims.getPropertyIndexes()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("Name", index.get(0) + "_" + index.get(1));
                    String label = index.get(0);
                    boolean isEdge = store.getEdgeTypeSchema(label) != null
                            && store.listTags().stream().noneMatch(t -> t.equals(label));
                    row.put("Kind", isEdge ? "EDGE" : "TAG");
                    row.put("On", label);
                    row.put("Property", index.get(1));
                    rows.add(row);
                }
            }
            return rows;
        }
        if (upperBody.startsWith("TAG ")) {
            String name = body.substring(4).trim();
            TagSchema schema = store.getTagSchema(name);
            if (schema == null) {
                throw new CypherException("Tag not found: " + name);
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (TagSchema.Field field : schema.getFields()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("Field", field.getName());
                row.put("Type", field.getType().name());
                row.put("Null", field.isNullable() ? "YES" : "NO");
                rows.add(row);
            }
            return rows;
        }
        if (upperBody.startsWith("EDGE ")) {
            String name = body.substring(5).trim();
            EdgeTypeSchema schema = store.getEdgeTypeSchema(name);
            if (schema == null) {
                throw new CypherException("EdgeType not found: " + name);
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (TagSchema.Field field : schema.getFields()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("Field", field.getName());
                row.put("Type", field.getType().name());
                row.put("Null", field.isNullable() ? "YES" : "NO");
                rows.add(row);
            }
            return rows;
        }
        throw new CypherException("Unsupported SHOW statement: " + cypher);
    }

    // ==================== DDL：CREATE/DROP TAG / EDGE TYPE / INDEX ====================

    /** CREATE TAG <name> (<field> <type> [NOT NULL], ...) */
    private List<Map<String, Object>> executeCreateTag(String cypher) {
        String body = stripLeadingKeyword(cypher, "CREATE TAG");
        int parenStart = body.indexOf('(');
        if (parenStart < 0) {
            throw new CypherException("CREATE TAG requires (field type, ...): " + cypher);
        }
        String name = body.substring(0, parenStart).trim();
        int parenEnd = findMatchingClose(body, parenStart);
        if (parenEnd < 0) {
            throw new CypherException("Unbalanced parentheses: " + cypher);
        }
        String fieldsStr = body.substring(parenStart + 1, parenEnd);
        TagSchema schema = new TagSchema(name, parseSchemaFields(fieldsStr));
        store.createTag(schema);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("Name", name);
        row.put("Fields", schema.getFields().size());
        return List.of(row);
    }

    private List<Map<String, Object>> executeDropTag(String cypher) {
        String body = stripLeadingKeyword(cypher, "DROP TAG");
        String name = body.trim();
        if (name.isEmpty()) {
            throw new CypherException("DROP TAG requires a name: " + cypher);
        }
        boolean dropped = store.dropTag(name);
        if (!dropped) {
            throw new CypherException("Tag not found: " + name);
        }
        return List.of(Map.of("Dropped", name));
    }

    private List<Map<String, Object>> executeCreateEdgeType(String cypher) {
        String body;
        if (cypher.toUpperCase().startsWith("CREATE EDGE TYPE")) {
            body = stripLeadingKeyword(cypher, "CREATE EDGE TYPE");
        } else {
            body = stripLeadingKeyword(cypher, "CREATE EDGE");
        }
        int parenStart = body.indexOf('(');
        if (parenStart < 0) {
            throw new CypherException("CREATE EDGE requires (field type, ...): " + cypher);
        }
        String name = body.substring(0, parenStart).trim();
        int parenEnd = findMatchingClose(body, parenStart);
        if (parenEnd < 0) {
            throw new CypherException("Unbalanced parentheses: " + cypher);
        }
        String fieldsStr = body.substring(parenStart + 1, parenEnd);
        EdgeTypeSchema schema = new EdgeTypeSchema(name, parseSchemaFields(fieldsStr));
        store.createEdgeType(schema);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("Name", name);
        row.put("Fields", schema.getFields().size());
        return List.of(row);
    }

    private List<Map<String, Object>> executeDropEdgeType(String cypher) {
        String body = stripLeadingKeyword(cypher, "DROP EDGE");
        String name = body.trim();
        if (name.isEmpty()) {
            throw new CypherException("DROP EDGE requires a name: " + cypher);
        }
        boolean dropped = store.dropEdgeType(name);
        if (!dropped) {
            throw new CypherException("EdgeType not found: " + name);
        }
        return List.of(Map.of("Dropped", name));
    }

    /** ALTER TAG <name> ADD (<field> <type> [, ...]) / DROP (<field>) */
    private List<Map<String, Object>> executeAlterTag(String cypher) {
        return executeAlterSchema(cypher, "TAG", true);
    }

    private List<Map<String, Object>> executeAlterEdgeType(String cypher) {
        return executeAlterSchema(cypher, "EDGE", false);
    }

    private List<Map<String, Object>> executeAlterSchema(String cypher, String kind, boolean isTag) {
        String prefix = "ALTER " + kind + " ";
        String body = stripLeadingKeyword(cypher, prefix);
        int parenStart = body.indexOf('(');
        if (parenStart < 0) {
            throw new CypherException("ALTER " + kind + " requires ADD/DROP clause: " + cypher);
        }
        String nameAndAction = body.substring(0, parenStart).trim();
        int parenEnd = findMatchingClose(body, parenStart);
        if (parenEnd < 0) {
            throw new CypherException("Unbalanced parentheses: " + cypher);
        }
        String fieldsStr = body.substring(parenStart + 1, parenEnd);

        String[] parts = nameAndAction.split("\\s+");
        if (parts.length != 2) {
            throw new CypherException("Expected ALTER " + kind + " <name> ADD|DROP: " + cypher);
        }
        String name = parts[0];
        String action = parts[1].toUpperCase();
        if (!"ADD".equals(action) && !"DROP".equals(action)) {
            throw new CypherException("ALTER " + kind + " only supports ADD or DROP: " + cypher);
        }
        if (isTag) {
            TagSchema current = store.getTagSchema(name);
            if (current == null) {
                throw new CypherException("Tag not found: " + name);
            }
            TagSchema next = applySchemaAction(current, fieldsStr, action, "ALTER TAG " + name);
            store.createTag(next);
            return List.of(Map.of("Altered", name, "Action", action, "Fields", next.getFields().size()));
        } else {
            EdgeTypeSchema current = store.getEdgeTypeSchema(name);
            if (current == null) {
                throw new CypherException("EdgeType not found: " + name);
            }
            EdgeTypeSchema next = applyEdgeAction(current, fieldsStr, action);
            store.createEdgeType(next);
            return List.of(Map.of("Altered", name, "Action", action, "Fields", next.getFields().size()));
        }
    }

    private TagSchema applySchemaAction(TagSchema current, String fieldsStr, String action, String context) {
        List<TagSchema.Field> currentFields = current.getFields();
        if ("DROP".equals(action)) {
            Set<String> dropNames = parseFieldNames(fieldsStr);
            List<TagSchema.Field> kept = new ArrayList<>();
            for (TagSchema.Field field : currentFields) {
                if (!dropNames.contains(field.getName())) kept.add(field);
            }
            if (kept.size() == currentFields.size()) {
                throw new CypherException("No fields dropped: " + context);
            }
            return new TagSchema(current.getName(), kept);
        }
        List<TagSchema.Field> added = parseSchemaFields(fieldsStr);
        TagSchema next = current;
        for (TagSchema.Field field : added) {
            try {
                next = next.withAddedField(field);
            } catch (IllegalArgumentException ex) {
                throw new CypherException(ex.getMessage());
            }
        }
        return next;
    }

    private EdgeTypeSchema applyEdgeAction(EdgeTypeSchema current, String fieldsStr, String action) {
        List<TagSchema.Field> currentFields = current.getFields();
        if ("DROP".equals(action)) {
            Set<String> dropNames = parseFieldNames(fieldsStr);
            List<TagSchema.Field> kept = new ArrayList<>();
            for (TagSchema.Field field : currentFields) {
                if (!dropNames.contains(field.getName())) kept.add(field);
            }
            if (kept.size() == currentFields.size()) {
                throw new CypherException("No fields dropped");
            }
            return new EdgeTypeSchema(current.getName(), kept);
        }
        List<TagSchema.Field> added = parseSchemaFields(fieldsStr);
        EdgeTypeSchema next = current;
        for (TagSchema.Field field : added) {
            try {
                next = next.withAddedField(field);
            } catch (IllegalArgumentException ex) {
                throw new CypherException(ex.getMessage());
            }
        }
        return next;
    }

    private Set<String> parseFieldNames(String fieldsStr) {
        Set<String> names = new LinkedHashSet<>();
        for (String token : splitByComma(fieldsStr)) {
            String name = token.trim();
            if (!name.isEmpty()) names.add(name);
        }
        if (names.isEmpty()) {
            throw new CypherException("DROP requires at least one field name");
        }
        return names;
    }

    /** REBUILD TAG INDEX <name> / REBUILD INDEX <name>。当前为同步空操作，索引已实时维护。 */
    private List<Map<String, Object>> executeRebuildIndex(String cypher) {
        return List.of(Map.of("Rebuilt", "ok",
                "Note", "in-memory index is always up-to-date"));
    }

    // ==================== EXPLAIN 查询计划 ====================

    /**
     * 输出只读的执行计划，不实际执行查询。计划中会标注：
     * <ul>
     *     <li>是否走索引下推以及下推命中的索引；</li>
     *     <li>模式是否含变长路径；</li>
     *     <li>聚合分组列；</li>
     *     <li>写入语义（CREATE/MERGE/SET/DELETE）。</li>
     * </ul>
     */
    private List<Map<String, Object>> executeExplain(String cypher) {
        String body = stripLeadingKeyword(cypher, "EXPLAIN").trim();
        String upper = body.toUpperCase();
        List<Map<String, Object>> plan = new ArrayList<>();

        if (upper.startsWith("CREATE TAG") || upper.startsWith("CREATE EDGE")
                || upper.startsWith("CREATE TAG INDEX") || upper.startsWith("CREATE INDEX")
                || upper.startsWith("DROP TAG") || upper.startsWith("DROP EDGE")
                || upper.startsWith("DROP TAG INDEX") || upper.startsWith("DROP INDEX")
                || upper.startsWith("ALTER TAG") || upper.startsWith("ALTER EDGE")
                || upper.startsWith("REBUILD")) {
            plan.add(step("DDL", body));
            return plan;
        }
        if (upper.startsWith("SHOW")) {
            plan.add(step("MetaScan", body));
            return plan;
        }
        if (upper.startsWith("CREATE")) {
            plan.add(step("NodeCreate", body));
            return plan;
        }
        if (upper.startsWith("MERGE")) {
            plan.add(step("NodeUpsert", body));
            return plan;
        }

        // MATCH 路径：探测索引下推和模式特征
        String upperBody = upper;
        String matchPattern = extractClause(body, upperBody, "MATCH",
                "WHERE|SET|DELETE|DETACH|RETURN|ORDER|LIMIT|SKIP|WITH|UNWIND");
        String whereClause = extractClause(body, upperBody, "WHERE",
                "SET|DELETE|DETACH|RETURN|ORDER|LIMIT|SKIP|WITH|UNWIND");
        String returnClause = extractClause(body, upperBody, "RETURN",
                "ORDER|LIMIT|SKIP|WITH");
        if (matchPattern != null && whereClause != null) {
            IndexedSeed seed = tryIndexSeed(matchPattern, whereClause);
            if (seed != null) {
                plan.add(step("IndexSeek",
                        "label-index lookup using WHERE predicate on "
                                + whereClause.trim()));
            } else {
                plan.add(step("LabelScan",
                        "scan label index then apply WHERE filter: "
                                + whereClause.trim()));
            }
        } else if (matchPattern != null) {
            if (matchPattern.matches(".*\\*\\d*\\.\\.?\\d*.*")) {
                plan.add(step("VarLenExpand", "BFS up to declared hops"));
            } else {
                plan.add(step("PatternMatch", matchPattern));
            }
        }
        if (returnClause != null) {
            List<Projection> projections = parseProjections(returnClause);
            List<String> aggregates = projections.stream()
                    .filter(p -> p.aggregate() != null)
                    .map(Projection::alias)
                    .toList();
            List<String> groupKeys = projections.stream()
                    .filter(p -> p.aggregate() == null)
                    .map(Projection::alias)
                    .toList();
            if (!aggregates.isEmpty()) {
                plan.add(step("Aggregate",
                        "aggregates=" + aggregates + " groupBy=" + groupKeys));
            } else {
                plan.add(step("Project", returnClause));
            }
        }
        return plan;
    }

    private static Map<String, Object> step(String operator, String detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("Operator", operator);
        row.put("Detail", detail);
        return row;
    }

    // ==================== DESCRIBE / SHOW STATS ====================

    /**
     * DESCRIBE TAG <name> / DESCRIBE EDGE <name> 是 SHOW TAG / SHOW EDGE 的别名，
     * 与 NebulaGraph 客户端习惯一致。
     */
    private List<Map<String, Object>> executeDescribe(String cypher) {
        String body;
        if (cypher.toUpperCase().startsWith("DESCRIBE")) {
            body = cypher.substring("DESCRIBE".length()).trim();
        } else {
            body = cypher.substring("DESC".length()).trim();
        }
        String upper = body.toUpperCase();
        if (upper.startsWith("TAG ")) {
            return executeShow("SHOW TAG " + body.substring(4).trim());
        }
        if (upper.startsWith("EDGE ")) {
            return executeShow("SHOW EDGE " + body.substring(5).trim());
        }
        if (upper.equals("STATS") || upper.equals("GRAPH")) {
            return executeShowStats();
        }
        throw new CypherException("Unsupported DESCRIBE: " + cypher);
    }

    private List<Map<String, Object>> executeShowStats() {
        if (!(store instanceof InMemoryGraphStore ims)) {
            throw new CypherException("SHOW STATS requires InMemoryGraphStore");
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.putAll(ims.getStats());
        return List.of(stats);
    }

    /** CREATE TAG INDEX ON <name> / CREATE EDGE INDEX ON <name> / CREATE INDEX ON <name> */
    private List<Map<String, Object>> executeCreateIndex(String cypher) {
        String upper = cypher.toUpperCase();
        String body;
        String kind;
        if (upper.startsWith("CREATE TAG INDEX ON")) {
            body = stripLeadingKeyword(cypher, "CREATE TAG INDEX ON");
            kind = "TAG";
        } else if (upper.startsWith("CREATE TAGINDEX ON")) {
            body = stripLeadingKeyword(cypher, "CREATE TAGINDEX ON");
            kind = "TAG";
        } else if (upper.startsWith("CREATE EDGE INDEX ON")) {
            body = stripLeadingKeyword(cypher, "CREATE EDGE INDEX ON");
            kind = "EDGE";
        } else if (upper.startsWith("CREATE EDGEINDEX ON")) {
            body = stripLeadingKeyword(cypher, "CREATE EDGEINDEX ON");
            kind = "EDGE";
        } else if (upper.startsWith("CREATE INDEX ON")) {
            body = stripLeadingKeyword(cypher, "CREATE INDEX ON");
            kind = "TAG";
        } else {
            throw new CypherException("Unsupported CREATE INDEX: " + cypher);
        }
        String[] parts = body.trim().split("\\.");
        if (parts.length != 2) {
            throw new CypherException("CREATE INDEX expects <tag>.<property>: " + cypher);
        }
        String label = parts[0].trim();
        String property = parts[1].trim();
        if (!(store instanceof InMemoryGraphStore ims)) {
            throw new CypherException("Index management requires InMemoryGraphStore");
        }
        boolean created = ims.createPropertyIndex(label, property);
        if (!created && !ims.hasPropertyIndex(label, property)) {
            throw new CypherException("Failed to create index: " + label + "." + property);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("Kind", kind);
        row.put("Name", label + "." + property);
        return List.of(row);
    }

    private List<Map<String, Object>> executeDropIndex(String cypher) {
        String upper = cypher.toUpperCase();
        String body;
        String kind;
        if (upper.startsWith("DROP TAG INDEX ON")) {
            body = stripLeadingKeyword(cypher, "DROP TAG INDEX ON");
            kind = "TAG";
        } else if (upper.startsWith("DROP EDGE INDEX ON")) {
            body = stripLeadingKeyword(cypher, "DROP EDGE INDEX ON");
            kind = "EDGE";
        } else if (upper.startsWith("DROP INDEX ON")) {
            body = stripLeadingKeyword(cypher, "DROP INDEX ON");
            kind = "TAG";
        } else {
            throw new CypherException("Unsupported DROP INDEX: " + cypher);
        }
        String[] parts = body.trim().split("\\.");
        if (parts.length != 2) {
            throw new CypherException("DROP INDEX expects <tag>.<property>: " + cypher);
        }
        String label = parts[0].trim();
        String property = parts[1].trim();
        if (!(store instanceof InMemoryGraphStore ims)) {
            throw new CypherException("Index management requires InMemoryGraphStore");
        }
        boolean dropped = ims.dropPropertyIndex(label, property);
        if (!dropped) {
            throw new CypherException("Index not found: " + label + "." + property);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("Kind", kind);
        row.put("Dropped", label + "." + property);
        return List.of(row);
    }

    private static String stripLeadingKeyword(String cypher, String keywordUpper) {
        if (!cypher.toUpperCase().startsWith(keywordUpper)) {
            throw new IllegalStateException("Expected prefix " + keywordUpper);
        }
        return cypher.substring(keywordUpper.length()).trim();
    }

    private static int findMatchingClose(String body, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private List<TagSchema.Field> parseSchemaFields(String fieldsStr) {
        List<TagSchema.Field> fields = new ArrayList<>();
        List<String> parts = splitByComma(fieldsStr);
        for (String part : parts) {
            String token = part.trim();
            if (token.isEmpty()) continue;
            boolean nullable = true;
            String[] tail = token.split("\\s+");
            int nameEnd = tail.length;
            if (tail[tail.length - 1].equalsIgnoreCase("NULL") && tail.length >= 2
                    && tail[tail.length - 2].equalsIgnoreCase("NOT")) {
                nullable = false;
                nameEnd = tail.length - 2;
            }
            if (nameEnd < 2) {
                throw new CypherException("Invalid schema field: " + part);
            }
            String fieldName = tail[0];
            String typeToken = tail[1].toUpperCase();
            try {
                TagSchema.DataType type = TagSchema.DataType.valueOf(typeToken);
                fields.add(new TagSchema.Field(fieldName, type, nullable));
            } catch (IllegalArgumentException ex) {
                throw new CypherException("Unknown schema type: " + typeToken);
            }
        }
        return fields;
    }

    // ==================== OPTIONAL MATCH ====================

    /**
     * OPTIONAL MATCH 的最小子集：模式同 MATCH，但匹配失败时仍然产生一行（左部变量为 null）。
     * 语法：OPTIONAL MATCH (n:Label) [WHERE ...] [RETURN ...]。
     */
    private List<Map<String, Object>> executeOptionalMatch(String cypher) {
        String rest = cypher.substring("OPTIONAL MATCH".length()).trim();
        // 复用 executeMatch 的子句切分：把 prefix 改写成 MATCH 形式
        List<Map<String, Object>> rows = executeMatch("MATCH " + rest);
        if (!rows.isEmpty()) {
            return rows;
        }
        // 模式未命中时返回一行空白，列名取自 RETURN 或默认 column_1
        String upper = rest.toUpperCase();
        int returnIdx = upper.indexOf(" RETURN ");
        Map<String, Object> row = new LinkedHashMap<>();
        if (returnIdx >= 0) {
            String returnClause = rest.substring(returnIdx + 8).trim();
            for (Projection projection : parseProjections(returnClause)) {
                row.put(projection.alias, null);
            }
        } else {
            row.put("column_1", null);
        }
        return List.of(row);
    }

    // ==================== 索引下推优化 ====================

    /** 单节点 MATCH + WHERE 等值 + 索引命中时构造的 binding 集合。 */
    private record IndexedSeed(List<MatchBinding> bindings) {
    }

    /**
     * 探测索引下推机会：当模式仅为 {@code (var:Label)} 且 WHERE 形如
     * {@code var.prop = value} 且存在 {@code (Label, prop)} 索引时，直接用索引替换全表扫描。
     */
    private IndexedSeed tryIndexSeed(String pattern, String whereClause) {
        if (whereClause == null || whereClause.trim().isEmpty()) return null;
        if (!(store instanceof InMemoryGraphStore ims)) return null;
        Matcher patMatch = Pattern.compile("^\\s*\\(\\s*(\\w+)\\s*:\\s*(\\w+)\\s*\\)\\s*$").matcher(pattern.trim());
        if (!patMatch.matches()) return null;
        String var = patMatch.group(1);
        String label = patMatch.group(2);
        Matcher whereMatch = Pattern.compile(
                "^\\s*" + Pattern.quote(var) + "\\s*\\.\\s*(\\w+)\\s*=\\s*(.+?)\\s*$").matcher(whereClause.trim());
        if (!whereMatch.matches()) return null;
        String property = whereMatch.group(1);
        Object value = evaluateLiteral(whereMatch.group(2));
        if (!ims.hasPropertyIndex(label, property)) return null;
        List<Long> ids = ims.findNodesByProperty(label, property, value);
        List<MatchBinding> bindings = new ArrayList<>(ids.size());
        for (long id : ids) {
            Node node = store.getNode(id);
            if (node == null) continue;
            MatchBinding binding = new MatchBinding();
            binding.variables.put(var, node);
            bindings.add(binding);
        }
        return new IndexedSeed(bindings);
    }

    // ==================== 工具方法 ====================

    private Object evaluateLiteral(String expr) {
        expr = expr.trim();
        if (expr.equalsIgnoreCase("null")) { return null; }
        if (expr.equalsIgnoreCase("true")) { return true; }
        if (expr.equalsIgnoreCase("false")) { return false; }
        if (expr.startsWith("'") && expr.endsWith("'")) return expr.substring(1, expr.length() - 1);

        if (expr.startsWith("\"") && expr.endsWith("\"")) return expr.substring(1, expr.length() - 1);

        try { return Integer.parseInt(expr); } catch (Exception ignored) {}
        try { return Long.parseLong(expr); } catch (Exception ignored) {}
        try { return Double.parseDouble(expr); } catch (Exception ignored) {}
        return expr;
    }

    private Object resolveValue(String expr) {
        return evaluateLiteral(expr.trim());
    }

    private Map<String, Object> parseProperties(String propsStr) {
        Map<String, Object> props = new LinkedHashMap<>();
        if (propsStr == null || propsStr.trim().isEmpty()) { return props; }
        // {name: 'Alice', age: 30}
        String[] pairs = propsStr.split(",");
        for (String pair : pairs) {
            String[] kv = pair.split("\\s*:\\s*", 2);
            if (kv.length == 2) {
                String key = kv[0].trim();
                Object value = evaluateLiteral(kv[1].trim());
                props.put(key, value);
            }
        }
        return props;
    }

    private boolean compareValues(Object lhs, Object rhs, String op) {
        if (lhs == null && rhs == null) { return op.equals("="); }

        if (lhs == null || rhs == null) { return op.equals("!="); }


        // 数值比较
        if (lhs instanceof Number && rhs instanceof Number) {
            double l = ((Number) lhs).doubleValue();
            double r = ((Number) rhs).doubleValue();
            return switch (op) {
                case "=" -> l == r;
                case "!=" -> l != r;
                case ">" -> l > r;
                case "<" -> l < r;
                case ">=" -> l >= r;
                case "<=" -> l <= r;
                default -> false;
            };
        }
        // 字符串比较
        int cmp = lhs.toString().compareTo(rhs.toString());
        return switch (op) {
            case "=" -> cmp == 0;
            case "!=" -> cmp != 0;
            case ">" -> cmp > 0;
            case "<" -> cmp < 0;
            case ">=" -> cmp >= 0;
            case "<=" -> cmp <= 0;
            default -> false;
        };
    }

    private String extractClause(String cypher, String upper, String startKeyword, String endPattern) {
        int startIdx = -1;
        if (startKeyword.equals("DETACH DELETE")) {
            startIdx = upper.indexOf("DETACH DELETE");
        } else {
            startIdx = upper.indexOf(startKeyword);
        }
        if (startIdx < 0) { return null; }


        int bodyStart = startIdx + (startKeyword.equals("DETACH DELETE") ? 13 : startKeyword.length());
        // 找下一个关键字的位置
        Matcher endMatcher = Pattern.compile("\\s+(?:" + endPattern + ")\\s").matcher(upper.substring(bodyStart));
        int endIdx = endMatcher.find() ? bodyStart + endMatcher.start() : cypher.length();
        return cypher.substring(bodyStart, endIdx).trim();
    }

    private List<String> splitByComma(String str) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder sb = new StringBuilder();
        for (char c : str.toCharArray()) {
            if (c == '(' || c == '[') { depth++; }

            else if (c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(sb.toString());
                sb = new StringBuilder();
                continue;
            }
            sb.append(c);
        }
        if (!sb.isEmpty()) { parts.add(sb.toString()); }

        return parts;
    }

    private List<String> splitByCommaPreserveParens(String str) {
        return splitByComma(str);
    }

    private int findKeywordPosition(String expr, String keyword) {
        String upper = expr.toUpperCase();
        String kwUpper = keyword.toUpperCase();
        int depth = 0;
        for (int i = 0; i < expr.length() - keyword.length() + 1; i++) {
            char c = expr.charAt(i);
            if (c == '(' || c == '[') { depth++; }

            else if (c == ')' || c == ']') depth--;
            if (depth == 0 && upper.substring(i).startsWith(kwUpper)) {
                return i;
            }
        }
        return -1;
    }

    /** 异常类 */
    public static class CypherException extends RuntimeException {
        public CypherException(String message) { super(message); }
    }

    /** 绑定变量容器 */
    static class MatchBinding {
        Map<String, Object> variables = new LinkedHashMap<>();

        MatchBinding() {}
        MatchBinding(Map<String, Object> vars) { this.variables = new LinkedHashMap<>(vars); }

        MatchBinding copy() {
            return new MatchBinding(new LinkedHashMap<>(variables));
        }

        Map<String, Object> toResultMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : variables.entrySet()) {
                if (e.getValue() instanceof Node node) {
                    result.put(e.getKey(), node);
                    result.put(e.getKey() + ".id", node.getId());
                    result.put(e.getKey() + ".labels", node.getLabels());
                    for (Map.Entry<String, Object> prop : node.getProperties().entrySet()) {
                        result.put(e.getKey() + "." + prop.getKey(), prop.getValue());
                    }
                } else if (e.getValue() instanceof Edge edge) {
                    result.put(e.getKey(), edge);
                } else {
                    result.put(e.getKey(), e.getValue());
                }
            }
            return result;
        }
    }
}
