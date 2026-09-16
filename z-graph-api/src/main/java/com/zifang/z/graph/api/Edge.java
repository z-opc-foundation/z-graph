package com.zifang.z.graph.api;

import java.util.*;

/**
 * 有向边（Relationship） — 连接两个节点的带类型、带属性的关系。
 * <p>
 * Neo4j 的关系是 **有向** 的：OUTGOING 从 startNode → endNode。
 * 查询时方向可过滤但不是必须的。
 */
public class Edge {

    private final long id;
    private final String type;
    private final long startNodeId;
    private final long endNodeId;
    private final Map<String, Object> properties;

    public Edge(long id, String type, long startNodeId, long endNodeId) {
        this(id, type, startNodeId, endNodeId, new LinkedHashMap<>());
    }

    public Edge(long id, String type, long startNodeId, long endNodeId, Map<String, Object> properties) {
        this.id = id;
        this.type = type;
        this.startNodeId = startNodeId;
        this.endNodeId = endNodeId;
        this.properties = new LinkedHashMap<>(properties);
    }

    public long getId() { return id; }
    public String getType() { return type; }
    public long getStartNodeId() { return startNodeId; }
    public long getEndNodeId() { return endNodeId; }
    public Map<String, Object> getProperties() { return properties; }

    public Object get(String key) { return properties.get(key); }

    public Edge set(String key, Object value) {
        properties.put(key, value);
        return this;
    }

    /** 反转方向，返回新的 Edge（id/type/properties 不变，start/end 互换） */
    public Edge reverse() {
        return new Edge(id, type, endNodeId, startNodeId, new LinkedHashMap<>(properties));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        } else {
            if (!(o instanceof Edge e)) {
                return false;
            } else {
                return id == e.id;
            }
        }
    }

    @Override
    public int hashCode() { return Long.hashCode(id); }

    @Override
    public String toString() {
        return String.format("(%d)-[:%s]->(%d) %s", startNodeId, type, endNodeId, properties);
    }
}
