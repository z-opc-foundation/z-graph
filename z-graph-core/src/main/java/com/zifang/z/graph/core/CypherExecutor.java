package com.zifang.z.graph.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简 Cypher 执行器 — POC 阶段只支持:
 * <pre>
 *   RETURN &lt;literal&gt; AS &lt;identifier&gt;[, &lt;identifier2&gt; ...]
 *   RETURN &lt;literal1&gt;, &lt;literal2&gt; [, ...]
 * </pre>
 *
 * literal 支持:整数(1, -2, 100)、字符串("hello")、布尔(true/false)、null。
 *
 * 示例:
 *   RETURN 1 AS n              → [{n=1}]
 *   RETURN "hello" AS msg      → [{msg="hello"}]
 *   RETURN 1 AS x, "y" AS y    → [{x=1, y="y"}]
 *   RETURN 1                   → [{1=1}]
 */
public class CypherExecutor {

    // MATCH "RETURN <lit> AS <id>(, <lit> AS <id>)*" 或 "RETURN <lit>(, <lit>)*"
    private static final Pattern RETURN_PATTERN = Pattern.compile(
            "^\\s*RETURN\\s+(?<rest>.*?)\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern COLUMN_PATTERN = Pattern.compile(
            "\\s*(?<expr>(?:\\d+|-?\\d+|\"(?:\\\\.|[^\"\\\\])*\"|true|false|null))"
                    + "(?:\\s+AS\\s+(?<alias>[A-Za-z_][A-Za-z0-9_]*))?",
            Pattern.CASE_INSENSITIVE);

    /**
     * 执行 Cypher,返回结果集(每行是一个 Map:列名 → 值)。
     * 当前 POC 阶段只支持 RETURN 字面量。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> execute(String cypher) {
        Matcher m = RETURN_PATTERN.matcher(cypher);
        if (!m.matches()) {
            throw new IllegalArgumentException("POC only supports RETURN literal statements, got: " + cypher);
        }
        String rest = m.group("rest");
        Matcher cm = COLUMN_PATTERN.matcher(rest);
        List<String> names = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        int idx = 0;
        while (cm.find()) {
            String expr = cm.group("expr").trim();
            String alias = cm.group("alias");
            Object value = parseLiteral(expr);
            String name;
            if (alias != null && !alias.isEmpty()) {
                name = alias;
            } else {
                name = String.valueOf(idx); // 默认列名 0, 1, 2, ...
            }
            names.add(name);
            values.add(value);
            idx++;
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException("RETURN must have at least one column, got: " + cypher);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            row.put(names.get(i), values.get(i));
        }
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(row);
        return result;
    }

    private Object parseLiteral(String literal) {
        if (literal.startsWith("\"") && literal.endsWith("\"")) {
            return literal.substring(1, literal.length() - 1)
                    .replace("\\\\", "\\\\").replace("\\\"", "\"");
        }
        if ("true".equalsIgnoreCase(literal)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(literal)) {
            return Boolean.FALSE;
        }
        if ("null".equalsIgnoreCase(literal)) {
            return null;
        }
        try {
            return Long.parseLong(literal);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Unsupported literal: " + literal);
        }
    }
}