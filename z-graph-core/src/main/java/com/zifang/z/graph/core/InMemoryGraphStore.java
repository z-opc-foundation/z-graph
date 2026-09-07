package com.zifang.z.graph.core;

import com.zifang.z.graph.api.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 内存图引擎 — 基于邻接表的完整 GraphStore 实现。
 * <p>
 * 存储结构:
 * - nodes: ConcurrentHashMap<id, Node>
 * - edges: ConcurrentHashMap<id, Edge>
 * - outEdges: ConcurrentHashMap<nodeId, List<Long>>（出边索引）
 * - inEdges: ConcurrentHashMap<nodeId, List<Long>>（入边索引）
 * - labelIndex: ConcurrentHashMap<label, Set<nodeId>>（标签倒排索引）
 * - typeIndex: ConcurrentHashMap<edgeType, Set<edgeId>>（边类型索引）
 * <p>
 * 所有操作线程安全（ConcurrentHashMap + AtomicLong）。
 * POC 阶段为纯内存，后续 T2 加 LSM-tree/WAL 持久化。
 */
public class InMemoryGraphStore implements GraphStore {

    private final ConcurrentHashMap<Long, Node> nodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Edge> edges = new ConcurrentHashMap<>();

    /** nodeId → 该节点的出边 ID 列表 */
    private final ConcurrentHashMap<Long, List<Long>> outEdges = new ConcurrentHashMap<>();
    /** nodeId → 该节点的入边 ID 列表 */
    private final ConcurrentHashMap<Long, List<Long>> inEdges = new ConcurrentHashMap<>();

    /** label → 节点 ID 集合（倒排索引） */
    private final ConcurrentHashMap<String, Set<Long>> labelIndex = new ConcurrentHashMap<>();
    /** edgeType → 边 ID 集合 */
    private final ConcurrentHashMap<String, Set<Long>> typeIndex = new ConcurrentHashMap<>();
    /** 节点属性索引定义和倒排值索引。 */
    private final Set<IndexDefinition> propertyIndexDefinitions = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<IndexDefinition, ConcurrentHashMap<Object, Set<Long>>> propertyIndexes =
            new ConcurrentHashMap<>();

    private final AtomicLong nextNodeId = new AtomicLong(0);
    private final AtomicLong nextEdgeId = new AtomicLong(0);

    // ==================== 节点操作 ====================

    @Override
    public Node addNode(String label, Map<String, Object> properties) {
        return addNode(nextNodeId.getAndIncrement(), label, properties);
    }

    @Override
    public Node addNode(long id, String label, Map<String, Object> properties) {
        return addNode(id, (label != null && !label.isEmpty()) ? List.of(label) : List.of(), properties);
    }

    /** 以完整标签集合创建节点，供快照复制和版本合并保留多标签。 */
    public Node addNode(long id, Collection<String> labels, Map<String, Object> properties) {
        Node node = new Node(id, labels != null ? labels : List.of(), deepCopyMap(properties));
        Node previous = nodes.put(id, node);
        if (previous != null) {
            for (String oldLabel : previous.getLabels()) {
                labelIndex.computeIfPresent(oldLabel, (k, v) -> { v.remove(id); return v; });
            }
        }
        nextNodeId.accumulateAndGet(id + 1, Math::max);
        // 更新标签索引
        for (String l : node.getLabels()) {
            labelIndex.computeIfAbsent(l, k -> ConcurrentHashMap.newKeySet()).add(id);
        }
        rebuildPropertyIndexes();
        return node;
    }

    @Override
    public Node getNode(long id) {
        return nodes.get(id);
    }

    @Override
    public void updateNode(long id, Map<String, Object> properties) {
        Node node = nodes.get(id);
        if (node != null && properties != null) {
            node.getProperties().putAll(properties);
            rebuildPropertyIndexes();
        }
    }

    @Override
    public boolean removeNode(long id) {
        Node removed = nodes.remove(id);
        if (removed == null) { return false; }

        // 移除所有关联边
        List<Long> outList = outEdges.remove(id);
        List<Long> inList = inEdges.remove(id);
        if (outList != null) {
            for (long eid : outList) {
                Edge e = edges.remove(eid);
                if (e != null) {
                    inEdges.computeIfPresent(e.getEndNodeId(), (k, v) -> { v.remove(eid); return v; });
                    typeIndex.computeIfPresent(e.getType(), (k, v) -> { v.remove(eid); return v; });
                }
            }
        }
        if (inList != null) {
            for (long eid : inList) {
                Edge e = edges.remove(eid);
                if (e != null) {
                    outEdges.computeIfPresent(e.getStartNodeId(), (k, v) -> { v.remove(eid); return v; });
                    typeIndex.computeIfPresent(e.getType(), (k, v) -> { v.remove(eid); return v; });
                }
            }
        }
        // 移除标签索引
        for (String label : removed.getLabels()) {
            labelIndex.computeIfPresent(label, (k, v) -> { v.remove(id); return v; });
        }
        rebuildPropertyIndexes();
        return true;
    }

    @Override
    public List<Long> getNodeIdsByLabel(String label) {
        Set<Long> ids = labelIndex.get(label);
        return ids != null ? List.copyOf(ids) : List.of();
    }

    @Override
    public long getNodeCount() { return nodes.size(); }

    // ==================== 边操作 ====================

    @Override
    public Edge addEdge(String type, long startNodeId, long endNodeId, Map<String, Object> properties) {
        return addEdge(nextEdgeId.getAndIncrement(), type, startNodeId, endNodeId, properties);
    }

    @Override
    public Edge addEdge(long id, String type, long startNodeId, long endNodeId, Map<String, Object> properties) {
        if (!nodes.containsKey(startNodeId) || !nodes.containsKey(endNodeId)) {
            throw new IllegalArgumentException(
                    "Edge references non-existent node: start=" + startNodeId + ", end=" + endNodeId);
        }
        Edge edge = new Edge(id, type, startNodeId, endNodeId, deepCopyMap(properties));
        Edge previous = edges.put(id, edge);
        if (previous != null) {
            outEdges.computeIfPresent(previous.getStartNodeId(), (k, v) -> { v.remove(id); return v; });
            inEdges.computeIfPresent(previous.getEndNodeId(), (k, v) -> { v.remove(id); return v; });
            typeIndex.computeIfPresent(previous.getType(), (k, v) -> { v.remove(id); return v; });
        }
        nextEdgeId.accumulateAndGet(id + 1, Math::max);
        outEdges.computeIfAbsent(startNodeId, k -> Collections.synchronizedList(new ArrayList<>())).add(id);
        inEdges.computeIfAbsent(endNodeId, k -> Collections.synchronizedList(new ArrayList<>())).add(id);
        typeIndex.computeIfAbsent(type, k -> ConcurrentHashMap.newKeySet()).add(id);
        return edge;
    }

    @Override
    public Edge getEdge(long id) { return edges.get(id); }

    @Override
    public void updateEdge(long id, Map<String, Object> properties) {
        Edge edge = edges.get(id);
        if (edge != null && properties != null) {
            edge.getProperties().putAll(properties);
        }
    }

    @Override
    public boolean removeEdge(long id) {
        Edge removed = edges.remove(id);
        if (removed == null) { return false; }

        outEdges.computeIfPresent(removed.getStartNodeId(), (k, v) -> { v.remove(id); return v; });
        inEdges.computeIfPresent(removed.getEndNodeId(), (k, v) -> { v.remove(id); return v; });
        typeIndex.computeIfPresent(removed.getType(), (k, v) -> { v.remove(id); return v; });
        return true;
    }

    @Override
    public List<Edge> getOutEdges(long nodeId) {
        List<Long> eids = outEdges.get(nodeId);
        if (eids == null || eids.isEmpty()) { return List.of(); }
        return eids.stream().map(edges::get).filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    public List<Edge> getInEdges(long nodeId) {
        List<Long> eids = inEdges.get(nodeId);
        if (eids == null || eids.isEmpty()) { return List.of(); }
        return eids.stream().map(edges::get).filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    public List<Edge> getEdges(long nodeId) {
        List<Edge> result = new ArrayList<>();
        result.addAll(getOutEdges(nodeId));
        result.addAll(getInEdges(nodeId));
        return result;
    }

    @Override
    public List<Edge> getEdgesByType(String edgeType) {
        Set<Long> eids = typeIndex.get(edgeType);
        if (eids == null || eids.isEmpty()) { return List.of(); }
        return eids.stream().map(edges::get).filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    public long getEdgeCount() { return edges.size(); }

    // ==================== 遍历 ====================

    @Override
    public Set<Long> traverse(long startNodeId, int maxDepth, String edgeType) {
        Set<Long> visited = new LinkedHashSet<>();
        Queue<long[]> queue = new LinkedList<>(); // [nodeId, depth]
        queue.add(new long[]{startNodeId, 0});
        while (!queue.isEmpty()) {
            long[] current = queue.poll();
            long nodeId = current[0];
            int depth = (int) current[1];
            if (depth > maxDepth) { continue; }
            if (visited.contains(nodeId)) { continue; }

            visited.add(nodeId);
            if (depth < maxDepth) {
                for (Edge e : getOutEdges(nodeId)) {
                    if (edgeType == null || edgeType.equals(e.getType())) {
                        queue.add(new long[]{e.getEndNodeId(), depth + 1});
                    }
                }
            }
        }
        return visited;
    }

    // ==================== 属性索引（基础）====================

    @Override
    public List<Long> findNodesByProperty(String label, String propertyKey, Object propertyValue) {
        if (label != null) {
            IndexDefinition definition = new IndexDefinition(label, propertyKey);
            Map<Object, Set<Long>> index = propertyIndexes.get(definition);
            if (index != null) {
                Set<Long> ids = index.get(propertyValue);
                return ids == null ? List.of() : ids.stream().sorted().collect(Collectors.toList());
            }
        }
        List<Long> candidateIds = label != null ? getNodeIdsByLabel(label) : new ArrayList<>(nodes.keySet());
        return candidateIds.stream()
                .map(nodes::get)
                .filter(n -> n != null && Objects.equals(propertyValue, n.get(propertyKey)))
                .map(Node::getId)
                .sorted()
                .collect(Collectors.toList());
    }

    /** 创建节点属性索引，适配 Nebula tag/property index 的基础语义。 */
    public boolean createPropertyIndex(String label, String propertyKey) {
        IndexDefinition definition = new IndexDefinition(label, propertyKey);
        if (!propertyIndexDefinitions.add(definition)) return false;
        propertyIndexes.putIfAbsent(definition, new ConcurrentHashMap<>());
        rebuildPropertyIndexes();
        return true;
    }

    public boolean dropPropertyIndex(String label, String propertyKey) {
        IndexDefinition definition = new IndexDefinition(label, propertyKey);
        propertyIndexes.remove(definition);
        return propertyIndexDefinitions.remove(definition);
    }

    public boolean hasPropertyIndex(String label, String propertyKey) {
        return propertyIndexDefinitions.contains(new IndexDefinition(label, propertyKey));
    }

    private void rebuildPropertyIndexes() {
        for (IndexDefinition definition : propertyIndexDefinitions) {
            ConcurrentHashMap<Object, Set<Long>> index = propertyIndexes.computeIfAbsent(
                    definition, ignored -> new ConcurrentHashMap<>());
            index.clear();
            Set<Long> candidates = labelIndex.getOrDefault(definition.label(), Set.of());
            for (Long nodeId : candidates) {
                Node node = nodes.get(nodeId);
                if (node == null) continue;
                Object value = node.get(definition.propertyKey());
                if (value != null) {
                    index.computeIfAbsent(value, ignored -> ConcurrentHashMap.newKeySet()).add(nodeId);
                }
            }
        }
    }

    @Override
    public List<Node> getAllNodes() {
        return nodes.values().stream()
                .sorted(Comparator.comparingLong(Node::getId))
                .map(n -> new Node(n.getId(), n.getLabels(), deepCopyMap(n.getProperties())))
                .collect(Collectors.toList());
    }

    @Override
    public List<Edge> getAllEdges() {
        return edges.values().stream()
                .sorted(Comparator.comparingLong(Edge::getId))
                .map(e -> new Edge(e.getId(), e.getType(), e.getStartNodeId(), e.getEndNodeId(),
                        deepCopyMap(e.getProperties())))
                .collect(Collectors.toList());
    }

    /** 创建与当前图完全隔离的深拷贝，用于提交快照和分支工作区。 */
    public InMemoryGraphStore copy() {
        InMemoryGraphStore copy = new InMemoryGraphStore();
        for (Node node : getAllNodes()) {
            copy.addNode(node.getId(), node.getLabels(), node.getProperties());
        }
        for (Edge edge : getAllEdges()) {
            copy.addEdge(edge.getId(), edge.getType(), edge.getStartNodeId(), edge.getEndNodeId(), edge.getProperties());
        }
        for (IndexDefinition definition : propertyIndexDefinitions) {
            copy.createPropertyIndex(definition.label(), definition.propertyKey());
        }
        return copy;
    }

    private record IndexDefinition(String label, String propertyKey) {
    }

    static Map<String, Object> deepCopyMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, deepCopyValue(value)));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), deepCopyValue(item)));
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(InMemoryGraphStore::deepCopyValue).collect(Collectors.toList());
        }
        if (value instanceof Set<?> set) {
            return set.stream().map(InMemoryGraphStore::deepCopyValue)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return value;
    }

    // ==================== 事务（简化）====================

    @Override
    public void commit() { /* 纯内存模式无操作 */ }

    @Override
    public void rollback() { /* 纯内存模式无操作 */ }

    public List<List<String>> getPropertyIndexes() {
        return propertyIndexDefinitions.stream()
                .map(definition -> List.of(definition.label(), definition.propertyKey()))
                .sorted(Comparator.comparing(item -> item.get(0) + "\u0000" + item.get(1)))
                .collect(Collectors.toList());
    }

    // ==================== 诊断 ====================

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("nodeCount", nodes.size());
        stats.put("edgeCount", edges.size());
        stats.put("labelCount", labelIndex.size());
        stats.put("edgeTypeCount", typeIndex.size());
        stats.put("propertyIndexCount", propertyIndexDefinitions.size());
        return stats;
    }
}
