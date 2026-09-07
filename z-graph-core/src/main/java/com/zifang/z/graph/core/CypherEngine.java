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

        if (upper.startsWith("MATCH")) {
            return executeMatch(trimmed);
        } else if (upper.startsWith("CREATE")) {
            return executeCreate(trimmed);
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
        List<MatchBinding> bindings = resolvePattern(pattern);

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
                results = expandEdgePattern(part, results);
            } else {
                results = expandNodePattern(part, results);
            }
        }
        return results;
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
        if (upperBody.equals("INDEXES")) {
            List<Map<String, Object>> rows = new ArrayList<>();
            if (store instanceof InMemoryGraphStore ims) {
                for (List<String> index : ims.getPropertyIndexes()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("Name", index.get(0) + "_" + index.get(1));
                    row.put("Tag", index.get(0));
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
