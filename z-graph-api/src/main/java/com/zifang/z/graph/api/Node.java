package com.zifang.z.graph.api;

import java.util.*;

/**
 * 图节点 — 有标签集合和属性 Map 的最小图元素。
 * <p>
 * 属性值类型限定为 Bolt 4.4 数据类型子集:
 * Integer / Long / Float / Double / Boolean / String / null / List
 */
public class Node {

    private final long id;
    private final Set<String> labels;
    private final Map<String, Object> properties;

    public Node(long id) {
        this(id, new LinkedHashSet<>(), new LinkedHashMap<>());
    }

    public Node(long id, String label) {
        this(id);
        this.labels.add(label);
    }

    public Node(long id, Collection<String> labels, Map<String, Object> properties) {
        this.id = id;
        this.labels = new LinkedHashSet<>(labels);
        this.properties = new LinkedHashMap<>(properties);
    }

    /** 节点 ID（唯一标识） */
    public long getId() { return id; }

    /** 标签集合（可变引用，供 Builder 操作） */
    public Set<String> getLabels() { return labels; }

    /** 属性 Map（可变引用） */
    public Map<String, Object> getProperties() { return properties; }

    /** 取单个属性值，不存在时返回 null */
    public Object get(String key) { return properties.get(key); }

    /** 设置属性 */
    public Node set(String key, Object value) {
        properties.put(key, value);
        return this;
    }

    /** 批量设置属性 */
    public Node setAll(Map<String, Object> props) {
        properties.putAll(props);
        return this;
    }

    public boolean hasLabel(String label) { return labels.contains(label); }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        } else {
            if (!(o instanceof Node n)) {
                return false;
            } else {
                return id == n.id;
            }
        }
    }

    @Override
    public int hashCode() { return Long.hashCode(id); }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("(");
        sb.append(id);
        if (!labels.isEmpty()) {
            sb.append(":").append(labels);
        }
        if (!properties.isEmpty()) {
            sb.append(" ").append(properties);
        }

        sb.append(")");
        return sb.toString();
    }
}
