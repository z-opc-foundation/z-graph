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
 * 所有操作线程安全（ConcurrentHashMap + AtomicLong）。属性倒排索引按单次变更增量维护，
 * 不再每次 addNode/updateNode 全量重建，批量装载因此是 O(N) 而不是 O(N²)。
 * <p>
 * 本类同时充当 {@link GraphVersionStore} 物化出来的 commit 视图：物化视图只允许通过
 * {@link #applyDelta} 整体演进，禁止就地改写，否则已经发出去的 checkout 会被追溯修改。
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
    /** Tag / EdgeType schema 表（NebulaGraph 风格的可选 schema）。 */
    private final ConcurrentHashMap<String, TagSchema> tagSchemas = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, EdgeTypeSchema> edgeTypeSchemas = new ConcurrentHashMap<>();

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
        Collection<String> safeLabels = labels != null ? labels : List.of();
        validateTagSchemas(safeLabels, properties);
        return installNode(new Node(id, safeLabels, deepCopyMap(properties)));
    }

    @Override
    public Node getNode(long id) {
        return nodes.get(id);
    }

    @Override
    public void updateNode(long id, Map<String, Object> properties) {
        Node node = nodes.get(id);
        if (node != null && properties != null) {
            boolean indexed = !propertyIndexDefinitions.isEmpty();
            if (indexed) {
                removeFromIndexes(node);
            }
            node.getProperties().putAll(properties);
            if (indexed) {
                addToIndexes(node);
            }
        }
    }

    @Override
    public boolean removeNode(long id) {
        Node removed = nodes.remove(id);
        if (removed == null) { return false; }

        forgetNodeFromIndexesAndLabels(removed);

        // 移除所有关联边
        List<Long> outList = outEdges.remove(id);
        List<Long> inList = inEdges.remove(id);
        if (outList != null) {
            for (long eid : outList) {
                forgetEdgeOnly(eid);
            }
        }
        if (inList != null) {
            for (long eid : inList) {
                forgetEdgeOnly(eid);
            }
        }
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
        validateEdgeTypeSchema(type, properties);
        return installEdge(new Edge(id, type, startNodeId, endNodeId, deepCopyMap(properties)));
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
        Edge removed = edges.get(id);
        if (removed == null) { return false; }
        forgetEdgeOnly(id);
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
        rebuildPropertyIndex(definition);
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

    /** 索引当前覆盖的标签+属性键集合，供版本层复制索引定义。 */
    Set<IndexDefinition> indexDefinitions() {
        return Set.copyOf(propertyIndexDefinitions);
    }

    private void rebuildPropertyIndex(IndexDefinition definition) {
        ConcurrentHashMap<Object, Set<Long>> index =
                propertyIndexes.computeIfAbsent(definition, ignored -> new ConcurrentHashMap<>());
        index.clear();
        for (Long nodeId : labelIndex.getOrDefault(definition.label(), Set.of())) {
            Node node = nodes.get(nodeId);
            if (node == null) continue;
            Object value = node.get(definition.propertyKey());
            if (value != null) {
                index.computeIfAbsent(value, ignored -> ConcurrentHashMap.newKeySet()).add(nodeId);
            }
        }
    }

    private void addToIndexes(Node node) {
        for (IndexDefinition definition : propertyIndexDefinitions) {
            if (!node.hasLabel(definition.label())) continue;
            Object value = node.get(definition.propertyKey());
            if (value == null) continue;
            propertyIndexes.computeIfAbsent(definition, ignored -> new ConcurrentHashMap<>())
                    .computeIfAbsent(value, ignored -> ConcurrentHashMap.newKeySet())
                    .add(node.getId());
        }
    }

    private void removeFromIndexes(Node node) {
        for (IndexDefinition definition : propertyIndexDefinitions) {
            if (!node.hasLabel(definition.label())) continue;
            Object value = node.get(definition.propertyKey());
            if (value == null) continue;
            ConcurrentHashMap<Object, Set<Long>> index = propertyIndexes.get(definition);
            if (index == null) continue;
            index.computeIfPresent(value, (k, ids) -> {
                ids.remove(node.getId());
                return ids.isEmpty() ? null : ids;
            });
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
        copy.installSchemaFrom(this);
        for (Node node : nodes.values()) {
            copy.installNode(new Node(node.getId(), node.getLabels(), deepCopyMap(node.getProperties())));
        }
        for (Edge edge : edges.values()) {
            copy.installEdge(new Edge(edge.getId(), edge.getType(), edge.getStartNodeId(), edge.getEndNodeId(),
                    deepCopyMap(edge.getProperties())));
        }
        for (IndexDefinition definition : copy.propertyIndexDefinitions) {
            copy.rebuildPropertyIndex(definition);
        }
        return copy;
    }

    // ==================== 版本层原语 ====================
    // 下面这组方法绕过 schema 校验并保证索引/邻接表一致，只服务于 GraphDelta 的回放和
    // 反序列化：delta 里的内容来自已经校验过的状态，重放时再校验既昂贵又会挡住删除语义。

    Node installNode(Node node) {
        Node previous = nodes.put(node.getId(), node);
        if (previous != null) {
            for (String oldLabel : previous.getLabels()) {
                if (!node.hasLabel(oldLabel)) {
                    labelIndex.computeIfPresent(oldLabel, (k, v) -> { v.remove(node.getId()); return v; });
                }
            }
            if (!propertyIndexDefinitions.isEmpty()) {
                removeFromIndexes(previous);
            }
        }
        nextNodeId.accumulateAndGet(node.getId() + 1, Math::max);
        for (String label : node.getLabels()) {
            labelIndex.computeIfAbsent(label, k -> ConcurrentHashMap.newKeySet()).add(node.getId());
        }
        if (!propertyIndexDefinitions.isEmpty()) {
            addToIndexes(node);
        }
        return node;
    }

    Edge installEdge(Edge edge) {
        Edge previous = edges.put(edge.getId(), edge);
        if (previous != null) {
            outEdges.computeIfPresent(previous.getStartNodeId(), (k, v) -> { v.remove(edge.getId()); return v; });
            inEdges.computeIfPresent(previous.getEndNodeId(), (k, v) -> { v.remove(edge.getId()); return v; });
            typeIndex.computeIfPresent(previous.getType(), (k, v) -> { v.remove(edge.getId()); return v; });
        }
        nextEdgeId.accumulateAndGet(edge.getId() + 1, Math::max);
        outEdges.computeIfAbsent(edge.getStartNodeId(), k -> Collections.synchronizedList(new ArrayList<>())).add(edge.getId());
        inEdges.computeIfAbsent(edge.getEndNodeId(), k -> Collections.synchronizedList(new ArrayList<>())).add(edge.getId());
        typeIndex.computeIfAbsent(edge.getType(), k -> ConcurrentHashMap.newKeySet()).add(edge.getId());
        return edge;
    }

    /** 只摘除节点自身（索引 + 邻接表骨架），不级联删边；级联已经记录在 delta 里。 */
    void forgetNodeOnly(long id) {
        Node removed = nodes.remove(id);
        if (removed != null) {
            forgetNodeFromIndexesAndLabels(removed);
        }
        outEdges.remove(id);
        inEdges.remove(id);
    }

    void forgetEdgeOnly(long id) {
        Edge removed = edges.remove(id);
        if (removed == null) return;
        outEdges.computeIfPresent(removed.getStartNodeId(), (k, v) -> { v.remove(id); return v; });
        inEdges.computeIfPresent(removed.getEndNodeId(), (k, v) -> { v.remove(id); return v; });
        typeIndex.computeIfPresent(removed.getType(), (k, v) -> {
            v.remove(id);
            return v.isEmpty() ? null : v;
        });
    }

    private void forgetNodeFromIndexesAndLabels(Node removed) {
        if (!propertyIndexDefinitions.isEmpty()) {
            removeFromIndexes(removed);
        }
        for (String label : removed.getLabels()) {
            labelIndex.computeIfPresent(label, (k, v) -> {
                v.remove(removed.getId());
                return v.isEmpty() ? null : v;
            });
        }
    }

    void putTagSchema(TagSchema schema) {
        tagSchemas.put(schema.getName(), schema);
    }

    void removeTagSchema(String tagName) {
        tagSchemas.remove(tagName);
    }

    void putEdgeTypeSchema(EdgeTypeSchema schema) {
        edgeTypeSchemas.put(schema.getName(), schema);
    }

    void removeEdgeTypeSchema(String edgeTypeName) {
        edgeTypeSchemas.remove(edgeTypeName);
    }

    /** 只登记索引定义，值倒排由调用方在整图回放完成后统一重建。 */
    void registerIndexDefinition(String label, String propertyKey) {
        propertyIndexDefinitions.add(new IndexDefinition(label, propertyKey));
    }

    void unregisterIndexDefinition(String label, String propertyKey) {
        IndexDefinition definition = new IndexDefinition(label, propertyKey);
        propertyIndexDefinitions.remove(definition);
        propertyIndexes.remove(definition);
    }

    Map<String, TagSchema> tagSchemaView() { return tagSchemas; }

    Map<String, EdgeTypeSchema> edgeTypeSchemaView() { return edgeTypeSchemas; }

    /** 直接暴露实体表，仅供同包的增量计算和编解码使用；调用方不得就地改写。 */
    Map<Long, Node> nodeMap() { return nodes; }

    Map<Long, Edge> edgeMap() { return edges; }

    /**
     * 就地回放一个增量。仅供物化过程中的临时视图和仓库加载使用，
     * 已经对外发布过的视图不允许再调用。
     */
    void applyDelta(GraphDelta delta) {
        for (long id : delta.nodeDeletes()) {
            forgetNodeOnly(id);
        }
        for (long id : delta.edgeDeletes()) {
            forgetEdgeOnly(id);
        }
        for (String tagName : delta.tagDeletes()) {
            removeTagSchema(tagName);
        }
        for (String edgeTypeName : delta.edgeTypeDeletes()) {
            removeEdgeTypeSchema(edgeTypeName);
        }
        for (GraphDelta.IndexKey key : delta.indexDeletes()) {
            unregisterIndexDefinition(key.label(), key.propertyKey());
        }
        for (TagSchema schema : delta.tagUpserts()) {
            putTagSchema(schema);
        }
        for (EdgeTypeSchema schema : delta.edgeTypeUpserts()) {
            putEdgeTypeSchema(schema);
        }
        for (Node node : delta.nodeUpserts()) {
            installNode(new Node(node.getId(), node.getLabels(), deepCopyMap(node.getProperties())));
        }
        for (Edge edge : delta.edgeUpserts()) {
            installEdge(new Edge(edge.getId(), edge.getType(), edge.getStartNodeId(), edge.getEndNodeId(),
                    deepCopyMap(edge.getProperties())));
        }
        Set<IndexDefinition> touchedIndexes = new LinkedHashSet<>();
        for (GraphDelta.IndexKey key : delta.indexUpserts()) {
            if (propertyIndexDefinitions.add(new IndexDefinition(key.label(), key.propertyKey()))) {
                touchedIndexes.add(new IndexDefinition(key.label(), key.propertyKey()));
            }
        }
        for (IndexDefinition definition : touchedIndexes) {
            rebuildPropertyIndex(definition);
        }
    }

    private void installSchemaFrom(InMemoryGraphStore source) {
        tagSchemas.putAll(source.tagSchemas);
        edgeTypeSchemas.putAll(source.edgeTypeSchemas);
        propertyIndexDefinitions.addAll(source.propertyIndexDefinitions);
    }

    private void validateTagSchemas(Collection<String> labels, Map<String, Object> properties) {
        for (String label : labels) {
            TagSchema schema = tagSchemas.get(label);
            if (schema != null) {
                schema.validate(properties);
            }
        }
    }

    private void validateEdgeTypeSchema(String type, Map<String, Object> properties) {
        EdgeTypeSchema schema = edgeTypeSchemas.get(type);
        if (schema != null) {
            schema.validate(properties);
        }
    }

    @Override
    public synchronized void createTag(TagSchema schema) {
        // 若存在同名 schema，需按新 schema 校验现有节点
        for (Long nodeId : getNodeIdsByLabel(schema.getName())) {
            Node node = nodes.get(nodeId);
            if (node != null) {
                schema.validate(node.getProperties());
            }
        }
        tagSchemas.put(schema.getName(), schema);
    }

    @Override
    public synchronized boolean dropTag(String tagName) {
        TagSchema schema = tagSchemas.remove(tagName);
        if (schema == null) return false;
        // 仅当没有该标签的节点时才允许删除，避免悬挂引用
        Set<Long> existing = labelIndex.get(tagName);
        if (existing != null && !existing.isEmpty()) {
            tagSchemas.put(tagName, schema);
            throw new IllegalStateException(
                    "Tag " + tagName + " still has " + existing.size() + " nodes");
        }
        return true;
    }

    @Override
    public TagSchema getTagSchema(String tagName) {
        return tagSchemas.get(tagName);
    }

    @Override
    public List<String> listTags() {
        return tagSchemas.keySet().stream().sorted().toList();
    }

    @Override
    public synchronized void createEdgeType(EdgeTypeSchema schema) {
        for (Edge edge : getEdgesByType(schema.getName())) {
            schema.validate(edge.getProperties());
        }
        edgeTypeSchemas.put(schema.getName(), schema);
    }

    @Override
    public synchronized boolean dropEdgeType(String edgeTypeName) {
        EdgeTypeSchema schema = edgeTypeSchemas.remove(edgeTypeName);
        if (schema == null) return false;
        Set<Long> existing = typeIndex.get(edgeTypeName);
        if (existing != null && !existing.isEmpty()) {
            edgeTypeSchemas.put(edgeTypeName, schema);
            throw new IllegalStateException(
                    "EdgeType " + edgeTypeName + " still has " + existing.size() + " edges");
        }
        return true;
    }

    @Override
    public EdgeTypeSchema getEdgeTypeSchema(String edgeTypeName) {
        return edgeTypeSchemas.get(edgeTypeName);
    }

    @Override
    public List<String> listEdgeTypes() {
        return edgeTypeSchemas.keySet().stream().sorted().toList();
    }

    record IndexDefinition(String label, String propertyKey) {
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
        stats.put("tagSchemaCount", tagSchemas.size());
        stats.put("edgeTypeSchemaCount", edgeTypeSchemas.size());
        return stats;
    }
}
