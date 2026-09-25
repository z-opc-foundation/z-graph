package com.zifang.z.graph.core;

import com.zifang.z.graph.api.Edge;
import com.zifang.z.graph.api.EdgeTypeSchema;
import com.zifang.z.graph.api.GraphStore;
import com.zifang.z.graph.api.Node;
import com.zifang.z.graph.api.TagSchema;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;

/**
 * 写事务的读改写覆盖层（MVCC 里的 private version）。
 *
 * <p>事务不再深拷贝整个分支 head：基底是上一个 commit 的不可读物化视图，
 * 本层只登记被触碰过的实体，读时把两层合并。因此 {@code beginWrite} 是 O(1)，
 * 而提交时登记出来的 {@link GraphDelta} 就是这个 commit 的全部数据。</p>
 *
 * <p>所有对外暴露的实体都是副本，避免调用方顺着句柄把不可变基底改脏。</p>
 */
final class VersionOverlayStore implements GraphStore {

    /** 实体 ID 分配器，由版本仓库统一持有以保证跨分支不重号。 */
    interface IdAllocator {
        long nextNodeId();

        long nextEdgeId();
    }

    private final GraphStore base;
    private final GraphDelta pending = new GraphDelta();
    private final IdAllocator ids;

    /** 事务内被触碰过的边的邻接桶，按实体当前端点维护。 */
    private final Map<Long, List<Long>> overlayOut = new LinkedHashMap<>();
    private final Map<Long, List<Long>> overlayIn = new LinkedHashMap<>();
    private final Map<Long, long[]> overlayEdgeEndpoints = new LinkedHashMap<>();
    private final Map<Long, Edge> overlayEdges = new LinkedHashMap<>();

    private long nodeCount;
    private long edgeCount;

    VersionOverlayStore(GraphStore base, IdAllocator ids) {
        this.base = Objects.requireNonNull(base, "base");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.nodeCount = base.getNodeCount();
        this.edgeCount = base.getEdgeCount();
    }

    GraphStore baseStore() {
        return base;
    }

    /**
     * 覆盖层套叠层数。提交后的覆盖层会直接充当该 commit 的物化视图，层数越深
     * 读穿透越贵，仓库据此决定何时摊平一次。平铺视图记作 0 层。
     */
    int viewDepth() {
        return 1 + (base instanceof VersionOverlayStore layered ? layered.viewDepth() : 0);
    }

    GraphDelta pendingDelta() {
        return pending;
    }

    /**
     * 本层自己登记的实体数。基底由它自己的缓存条目计费，套叠视图不能按合并后的
     * 全图规模计费，否则内存预算会把"共享基底的薄层"当成一份整图。
     */
    long ownEntityCount() {
        return pending.nodeUpserts().size() + pending.nodeDeletes().size()
                + pending.edgeUpserts().size() + pending.edgeDeletes().size();
    }

    /** 缓存视图计费：平铺视图按整图规模，覆盖层只按本层增量。 */
    static long billedEntities(GraphStore view) {
        return view instanceof VersionOverlayStore layered
                ? layered.ownEntityCount()
                : view.getNodeCount() + view.getEdgeCount();
    }

    // ==================== 节点 ====================

    @Override
    public Node addNode(String label, Map<String, Object> properties) {
        return addNode(ids.nextNodeId(), label, properties);
    }

    @Override
    public Node addNode(long id, String label, Map<String, Object> properties) {
        return addNode(id, (label != null && !label.isEmpty()) ? List.of(label) : List.of(), properties);
    }

    Node addNode(long id, Collection<String> labels, Map<String, Object> properties) {
        Collection<String> safeLabels = labels != null ? labels : List.of();
        validateTagSchemas(safeLabels, properties);
        Node node = new Node(id, safeLabels, InMemoryGraphStore.deepCopyMap(properties));
        if (mergedNode(id) == null) {
            nodeCount++;
        }
        pending.putNode(node);
        return node;
    }

    @Override
    public Node getNode(long id) {
        Node node = mergedNode(id);
        return node == null ? null : GraphDelta.copyNode(node);
    }

    /** 事务内部读取：命中暂存区时直接返回暂存实体，避免逐次复制。 */
    private Node mergedNode(long id) {
        if (pending.nodeDeletes().contains(id)) return null;
        Node upsert = pending.nodeUpsert(id);
        if (upsert != null) return upsert;
        return base.getNode(id);
    }

    @Override
    public void updateNode(long id, Map<String, Object> properties) {
        Node current = mergedNode(id);
        if (current == null || properties == null) return;
        Map<String, Object> merged = InMemoryGraphStore.deepCopyMap(current.getProperties());
        merged.putAll(properties);
        validateTagSchemas(current.getLabels(), merged);
        pending.putNode(new Node(id, current.getLabels(), merged));
    }

    @Override
    public boolean removeNode(long id) {
        if (mergedNode(id) == null) return false;
        for (Edge edge : mergedEdges(id)) {
            dropEdge(edge.getId());
        }
        pending.deleteNode(id);
        nodeCount--;
        return true;
    }

    @Override
    public List<Long> getNodeIdsByLabel(String label) {
        Set<Long> touched = new HashSet<>(pending.nodeDeletes());
        touched.addAll(collectNodeIds(pending.nodeUpserts()));
        List<Long> result = new ArrayList<>();
        for (Long id : base.getNodeIdsByLabel(label)) {
            if (!touched.contains(id)) result.add(id);
        }
        for (Node node : pending.nodeUpserts()) {
            if (node.hasLabel(label) && !result.contains(node.getId())) result.add(node.getId());
        }
        return result;
    }

    private static Set<Long> collectNodeIds(Collection<Node> nodes) {
        Set<Long> ids = new HashSet<>();
        for (Node node : nodes) ids.add(node.getId());
        return ids;
    }

    @Override
    public long getNodeCount() {
        return nodeCount;
    }

    // ==================== 边 ====================

    @Override
    public Edge addEdge(String type, long startNodeId, long endNodeId, Map<String, Object> properties) {
        return addEdge(ids.nextEdgeId(), type, startNodeId, endNodeId, properties);
    }

    @Override
    public Edge addEdge(long id, String type, long startNodeId, long endNodeId, Map<String, Object> properties) {
        if (mergedNode(startNodeId) == null || mergedNode(endNodeId) == null) {
            throw new IllegalArgumentException(
                    "Edge references non-existent node: start=" + startNodeId + ", end=" + endNodeId);
        }
        EdgeTypeSchema schema = mergedEdgeTypeSchema(type);
        if (schema != null) {
            schema.validate(properties);
        }
        Edge edge = new Edge(id, type, startNodeId, endNodeId,
                InMemoryGraphStore.deepCopyMap(properties));
        if (mergedEdge(id) == null) {
            edgeCount++;
        }
        recordEdge(edge);
        return edge;
    }

    @Override
    public Edge getEdge(long id) {
        Edge edge = mergedEdge(id);
        return edge == null ? null : GraphDelta.copyEdge(edge);
    }

    private Edge mergedEdge(long id) {
        if (pending.edgeDeletes().contains(id)) return null;
        Edge upsert = pending.edgeUpsert(id);
        if (upsert != null) return upsert;
        return base.getEdge(id);
    }

    @Override
    public void updateEdge(long id, Map<String, Object> properties) {
        Edge current = mergedEdge(id);
        if (current == null || properties == null) return;
        Map<String, Object> merged = InMemoryGraphStore.deepCopyMap(current.getProperties());
        merged.putAll(properties);
        EdgeTypeSchema schema = mergedEdgeTypeSchema(current.getType());
        if (schema != null) {
            schema.validate(merged);
        }
        recordEdge(new Edge(id, current.getType(), current.getStartNodeId(), current.getEndNodeId(), merged));
    }

    @Override
    public boolean removeEdge(long id) {
        if (mergedEdge(id) == null) return false;
        dropEdge(id);
        return true;
    }

    private void recordEdge(Edge edge) {
        long[] oldEndpoints = overlayEdgeEndpoints.get(edge.getId());
        if (oldEndpoints != null) {
            detachFromBucket(overlayOut, oldEndpoints[0], edge.getId());
            detachFromBucket(overlayIn, oldEndpoints[1], edge.getId());
        }
        overlayEdges.put(edge.getId(), edge);
        overlayOut.computeIfAbsent(edge.getStartNodeId(), ignored -> new ArrayList<>()).add(edge.getId());
        overlayIn.computeIfAbsent(edge.getEndNodeId(), ignored -> new ArrayList<>()).add(edge.getId());
        overlayEdgeEndpoints.put(edge.getId(), new long[]{edge.getStartNodeId(), edge.getEndNodeId()});
        pending.putEdge(edge);
    }

    private void dropEdge(long id) {
        Edge current = mergedEdge(id);
        if (current == null) return;
        detachFromBucket(overlayOut, current.getStartNodeId(), id);
        detachFromBucket(overlayIn, current.getEndNodeId(), id);
        overlayEdges.remove(id);
        overlayEdgeEndpoints.remove(id);
        pending.deleteEdge(id);
        edgeCount--;
    }

    private static void detachFromBucket(Map<Long, List<Long>> buckets, long key, long edgeId) {
        List<Long> bucket = buckets.get(key);
        if (bucket != null) {
            bucket.remove(Long.valueOf(edgeId));
            if (bucket.isEmpty()) buckets.remove(key);
        }
    }

    private List<Edge> mergedEdges(long nodeId) {
        List<Edge> result = new ArrayList<>();
        Set<Long> emitted = new HashSet<>();
        for (long id : baseEdgesFor(nodeId)) {
            if (isEdgeTouched(id)) continue;
            Edge edge = base.getEdge(id);
            if (edge != null && emitted.add(id)) result.add(edge);
        }
        for (long id : overlayBucket(overlayOut, nodeId)) {
            Edge edge = overlayEdges.get(id);
            if (edge != null && emitted.add(id)) result.add(edge);
        }
        for (long id : overlayBucket(overlayIn, nodeId)) {
            Edge edge = overlayEdges.get(id);
            if (edge != null && edge.getStartNodeId() != nodeId && emitted.add(id)) result.add(edge);
        }
        return result;
    }

    private static List<Long> overlayBucket(Map<Long, List<Long>> buckets, long nodeId) {
        List<Long> bucket = buckets.get(nodeId);
        return bucket == null ? List.of() : new ArrayList<>(bucket);
    }

    private List<Long> baseEdgesFor(long nodeId) {
        List<Long> result = new ArrayList<>();
        for (Edge edge : base.getOutEdges(nodeId)) result.add(edge.getId());
        for (Edge edge : base.getInEdges(nodeId)) result.add(edge.getId());
        return result;
    }

    private boolean isEdgeTouched(long id) {
        return pending.hasEdge(id) || pending.edgeDeletes().contains(id);
    }

    @Override
    public List<Edge> getOutEdges(long nodeId) {
        List<Edge> result = new ArrayList<>();
        Set<Long> emitted = new HashSet<>();
        for (long id : base.getOutEdges(nodeId).stream().map(Edge::getId).toList()) {
            if (isEdgeTouched(id)) continue;
            Edge edge = base.getEdge(id);
            if (edge != null && edge.getStartNodeId() == nodeId && emitted.add(id)) result.add(GraphDelta.copyEdge(edge));
        }
        for (long id : overlayBucket(overlayOut, nodeId)) {
            Edge edge = overlayEdges.get(id);
            if (edge != null && edge.getStartNodeId() == nodeId && emitted.add(id)) result.add(GraphDelta.copyEdge(edge));
        }
        return result;
    }

    @Override
    public List<Edge> getInEdges(long nodeId) {
        List<Edge> result = new ArrayList<>();
        Set<Long> emitted = new HashSet<>();
        for (long id : base.getInEdges(nodeId).stream().map(Edge::getId).toList()) {
            if (isEdgeTouched(id)) continue;
            Edge edge = base.getEdge(id);
            if (edge != null && edge.getEndNodeId() == nodeId && emitted.add(id)) result.add(GraphDelta.copyEdge(edge));
        }
        for (long id : overlayBucket(overlayIn, nodeId)) {
            Edge edge = overlayEdges.get(id);
            if (edge != null && edge.getEndNodeId() == nodeId && emitted.add(id)) result.add(GraphDelta.copyEdge(edge));
        }
        return result;
    }

    @Override
    public List<Edge> getEdges(long nodeId) {
        List<Edge> result = new ArrayList<>();
        Set<Long> emitted = new HashSet<>();
        for (Edge edge : getOutEdges(nodeId)) {
            if (emitted.add(edge.getId())) result.add(edge);
        }
        for (Edge edge : getInEdges(nodeId)) {
            if (emitted.add(edge.getId())) result.add(edge);
        }
        return result;
    }

    @Override
    public List<Edge> getEdgesByType(String edgeType) {
        List<Edge> result = new ArrayList<>();
        Set<Long> emitted = new HashSet<>();
        for (Edge edge : base.getEdgesByType(edgeType)) {
            if (isEdgeTouched(edge.getId())) continue;
            if (emitted.add(edge.getId())) result.add(GraphDelta.copyEdge(edge));
        }
        for (Edge edge : overlayEdges.values()) {
            if (edgeType.equals(edge.getType()) && emitted.add(edge.getId())) result.add(GraphDelta.copyEdge(edge));
        }
        return result;
    }

    @Override
    public long getEdgeCount() {
        return edgeCount;
    }

    // ==================== 遍历 ====================

    @Override
    public Set<Long> traverse(long startNodeId, int maxDepth, String edgeType) {
        Set<Long> visited = new LinkedHashSet<>();
        Queue<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{startNodeId, 0});
        while (!queue.isEmpty()) {
            Object[] current = queue.poll();
            long nodeId = (Long) current[0];
            int depth = (Integer) current[1];
            if (depth > maxDepth || !visited.add(nodeId)) continue;
            if (depth == maxDepth) continue;
            for (Edge edge : getOutEdges(nodeId)) {
                if (edgeType == null || edgeType.equals(edge.getType())) {
                    queue.add(new Object[]{edge.getEndNodeId(), depth + 1});
                }
            }
        }
        return visited;
    }

    // ==================== 属性检索 ====================

    @Override
    public List<Long> findNodesByProperty(String label, String propertyKey, Object propertyValue) {
        Set<Long> matched = new TreeSet<>();
        for (Long candidate : base.findNodesByProperty(label, propertyKey, propertyValue)) {
            Node node = mergedNode(candidate);
            if (node != null && Objects.equals(propertyValue, node.get(propertyKey))
                    && (label == null || node.hasLabel(label))) {
                matched.add(candidate);
            }
        }
        for (Node node : pending.nodeUpserts()) {
            if (label != null && !node.hasLabel(label)) continue;
            if (Objects.equals(propertyValue, node.get(propertyKey))) matched.add(node.getId());
        }
        return new ArrayList<>(matched);
    }

    @Override
    public List<Node> getAllNodes() {
        List<Node> result = new ArrayList<>();
        Set<Long> emitted = new HashSet<>();
        for (Node node : base.getAllNodes()) {
            if (isNodeTouched(node.getId())) continue;
            emitted.add(node.getId());
            result.add(node);
        }
        for (Node node : pending.nodeUpserts()) {
            if (emitted.add(node.getId())) result.add(GraphDelta.copyNode(node));
        }
        result.sort(Comparator.comparingLong(Node::getId));
        return result;
    }

    @Override
    public List<Edge> getAllEdges() {
        List<Edge> result = new ArrayList<>();
        Set<Long> emitted = new HashSet<>();
        for (Edge edge : base.getAllEdges()) {
            if (isEdgeTouched(edge.getId())) continue;
            emitted.add(edge.getId());
            result.add(edge);
        }
        for (Edge edge : overlayEdges.values()) {
            if (emitted.add(edge.getId())) result.add(GraphDelta.copyEdge(edge));
        }
        result.sort(Comparator.comparingLong(Edge::getId));
        return result;
    }

    @Override
    public List<Long> getAllNodeIds() {
        List<Long> result = new ArrayList<>();
        for (Node node : base.getAllNodes()) {
            if (!isNodeTouched(node.getId())) result.add(node.getId());
        }
        for (Node node : pending.nodeUpserts()) result.add(node.getId());
        result.sort(Comparator.naturalOrder());
        return result;
    }

    @Override
    public List<Long> getAllEdgeIds() {
        List<Long> result = new ArrayList<>();
        for (Edge edge : base.getAllEdges()) {
            if (!isEdgeTouched(edge.getId())) result.add(edge.getId());
        }
        for (Edge edge : overlayEdges.values()) result.add(edge.getId());
        result.sort(Comparator.naturalOrder());
        return result;
    }

    private boolean isNodeTouched(long id) {
        return pending.hasNode(id) || pending.nodeDeletes().contains(id);
    }

    // ==================== Schema ====================

    private void validateTagSchemas(Collection<String> labels, Map<String, Object> properties) {
        for (String label : labels) {
            TagSchema schema = mergedTagSchema(label);
            if (schema != null) {
                schema.validate(properties);
            }
        }
    }

    private TagSchema mergedTagSchema(String name) {
        if (pending.tagDeletes().contains(name)) return null;
        TagSchema upsert = pending.tagUpsert(name);
        if (upsert != null) return upsert;
        return base.getTagSchema(name);
    }

    private EdgeTypeSchema mergedEdgeTypeSchema(String name) {
        if (pending.edgeTypeDeletes().contains(name)) return null;
        EdgeTypeSchema upsert = pending.edgeTypeUpsert(name);
        if (upsert != null) return upsert;
        return base.getEdgeTypeSchema(name);
    }

    @Override
    public void createTag(TagSchema schema) {
        for (Long nodeId : getNodeIdsByLabel(schema.getName())) {
            Node node = mergedNode(nodeId);
            if (node != null) {
                schema.validate(node.getProperties());
            }
        }
        pending.putTag(schema);
    }

    @Override
    public boolean dropTag(String tagName) {
        if (mergedTagSchema(tagName) == null) return false;
        List<Long> holders = getNodeIdsByLabel(tagName);
        if (!holders.isEmpty()) {
            throw new IllegalStateException("Tag " + tagName + " still has " + holders.size() + " nodes");
        }
        pending.deleteTag(tagName);
        return true;
    }

    @Override
    public TagSchema getTagSchema(String tagName) {
        return mergedTagSchema(tagName);
    }

    @Override
    public List<String> listTags() {
        Set<String> names = new TreeSet<>();
        names.addAll(base.listTags());
        names.removeAll(pending.tagDeletes());
        for (TagSchema schema : pending.tagUpserts()) names.add(schema.getName());
        return List.copyOf(names);
    }

    @Override
    public void createEdgeType(EdgeTypeSchema schema) {
        for (Edge edge : getEdgesByType(schema.getName())) {
            schema.validate(edge.getProperties());
        }
        pending.putEdgeType(schema);
    }

    @Override
    public boolean dropEdgeType(String edgeTypeName) {
        if (mergedEdgeTypeSchema(edgeTypeName) == null) return false;
        List<Edge> holders = getEdgesByType(edgeTypeName);
        if (!holders.isEmpty()) {
            throw new IllegalStateException("EdgeType " + edgeTypeName + " still has " + holders.size() + " edges");
        }
        pending.deleteEdgeType(edgeTypeName);
        return true;
    }

    @Override
    public EdgeTypeSchema getEdgeTypeSchema(String edgeTypeName) {
        return mergedEdgeTypeSchema(edgeTypeName);
    }

    @Override
    public List<String> listEdgeTypes() {
        Set<String> names = new TreeSet<>();
        names.addAll(base.listEdgeTypes());
        names.removeAll(pending.edgeTypeDeletes());
        for (EdgeTypeSchema schema : pending.edgeTypeUpserts()) names.add(schema.getName());
        return List.copyOf(names);
    }

    // ==================== 属性索引定义 ====================

    boolean hasPropertyIndex(String label, String propertyKey) {
        GraphDelta.IndexKey key = new GraphDelta.IndexKey(label, propertyKey);
        if (pending.indexDeletes().contains(key)) return false;
        if (pending.indexUpserts().contains(key)) return true;
        return baseIndexDefinitions().contains(new InMemoryGraphStore.IndexDefinition(label, propertyKey));
    }

    boolean createPropertyIndex(String label, String propertyKey) {
        if (hasPropertyIndex(label, propertyKey)) return false;
        pending.putIndex(new GraphDelta.IndexKey(label, propertyKey));
        return true;
    }

    boolean dropPropertyIndex(String label, String propertyKey) {
        if (!hasPropertyIndex(label, propertyKey)) return false;
        pending.deleteIndex(new GraphDelta.IndexKey(label, propertyKey));
        return true;
    }

    List<List<String>> getPropertyIndexes() {
        List<List<String>> result = new ArrayList<>();
        for (InMemoryGraphStore.IndexDefinition definition : indexDefinitions()) {
            result.add(List.of(definition.label(), definition.propertyKey()));
        }
        return result;
    }

    /** 本层合并后的索引定义，供上一层也是覆盖层时继续透传。 */
    Set<InMemoryGraphStore.IndexDefinition> indexDefinitions() {
        Set<GraphDelta.IndexKey> keys = new TreeSet<>(Comparator
                .comparing((GraphDelta.IndexKey key) -> key.label())
                .thenComparing(GraphDelta.IndexKey::propertyKey));
        for (InMemoryGraphStore.IndexDefinition definition : baseIndexDefinitions()) {
            keys.add(new GraphDelta.IndexKey(definition.label(), definition.propertyKey()));
        }
        keys.removeAll(pending.indexDeletes());
        keys.addAll(pending.indexUpserts());
        Set<InMemoryGraphStore.IndexDefinition> result = new LinkedHashSet<>();
        for (GraphDelta.IndexKey key : keys) {
            result.add(new InMemoryGraphStore.IndexDefinition(key.label(), key.propertyKey()));
        }
        return result;
    }

    /** 基底可能是平铺视图，也可能是上一个 commit 的覆盖层，两者都要能取出索引定义。 */
    private Set<InMemoryGraphStore.IndexDefinition> baseIndexDefinitions() {
        if (base instanceof InMemoryGraphStore flat) return flat.indexDefinitions();
        if (base instanceof VersionOverlayStore layered) return layered.indexDefinitions();
        return Set.of();
    }

    @Override
    public void commit() {
        // 真正的版本提交在 GraphWriteTransaction.commit(author, message)。
    }

    @Override
    public void rollback() {
        // 覆盖层随事务对象一起丢弃。
    }
}
