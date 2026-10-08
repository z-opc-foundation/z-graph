package com.zifang.z.graph.core.storage;

import com.zifang.z.graph.api.Edge;
import com.zifang.z.graph.api.EdgeTypeSchema;
import com.zifang.z.graph.api.GraphStore;
import com.zifang.z.graph.api.Node;
import com.zifang.z.graph.api.TagSchema;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 绑定某个 ref（具体是一个 commit 的 Visibility）的图视图 —— 引擎原生 MVCC 读路径。
 *
 * <p>没有任何「物化出的图状态」：每个读操作都即时解析——实体走版本链回溯，
 * 邻接/label/edgeType 走 postings 墓碑解析（ancestry 内 latest-wins）。main head
 * 与任意历史 commit 走同一条路；commit 一旦生成就不可变，因此实例级的计数缓存
 * 是安全的（视图被 pinned 在一个 commit 上）。</p>
 */
public final class RefViewGraphStore implements GraphStore {

    private final StorageEngine engine;
    private final Visibility visibility;
    private Long cachedNodeCount;
    private Long cachedEdgeCount;

    public RefViewGraphStore(StorageEngine engine, Visibility visibility) {
        this(engine, visibility, null, null);
    }

    /**
     * commit 不可变 ⇒ 其实体计数也是常量，直接用 commit object 里声明的计数播种，
     * beginWrite 的 TxBuffer 构造（要读 base 计数）才能保持 O(1)。
     */
    public RefViewGraphStore(StorageEngine engine, Visibility visibility, Long pinnedNodeCount, Long pinnedEdgeCount) {
        this.engine = engine;
        this.visibility = visibility;
        this.cachedNodeCount = pinnedNodeCount;
        this.cachedEdgeCount = pinnedEdgeCount;
    }

    private static UnsupportedOperationException readOnly() {
        return new UnsupportedOperationException("Ref view is read-only");
    }

    // ==================== 节点 ====================

    @Override
    public Node addNode(String label, Map<String, Object> properties) {
        throw readOnly();
    }

    @Override
    public Node addNode(long id, String label, Map<String, Object> properties) {
        throw readOnly();
    }

    @Override
    public Node getNode(long id) {
        try {
            VersionStore.VersionRecord record = engine.versionStore()
                    .latestVisible(VersionStore.KIND_UPSERT_NODE, id, visibility);
            if (record == null || record.isDelete()) {
                return null;
            }
            PayloadCodec.NodePayload payload = PayloadCodec.decodeNode(
                    engine.versionStore().payload(record), engine.labelDictionary());
            return new Node(id, payload.labels, payload.properties);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot resolve node " + id, e);
        }
    }

    @Override
    public void updateNode(long id, Map<String, Object> properties) {
        throw readOnly();
    }

    @Override
    public boolean removeNode(long id) {
        throw readOnly();
    }

    @Override
    public List<Long> getNodeIdsByLabel(String label) {
        int dictId = engine.labelDictionary().idOf(label);
        if (dictId < 0) {
            return new ArrayList<>();
        }
        try {
            return engine.labelPostings().resolveLive(dictId, visibility);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot resolve label postings: " + label, e);
        }
    }

    @Override
    public long getNodeCount() {
        if (cachedNodeCount == null) {
            long count = 0;
            for (long id = 0; id < engine.nextNodeId(); id++) {
                if (getNode(id) != null) count++;
            }
            cachedNodeCount = count;
        }
        return cachedNodeCount;
    }

    // ==================== 边 ====================

    @Override
    public Edge addEdge(String type, long startNodeId, long endNodeId, Map<String, Object> properties) {
        throw readOnly();
    }

    @Override
    public Edge addEdge(long id, String type, long startNodeId, long endNodeId, Map<String, Object> properties) {
        throw readOnly();
    }

    @Override
    public Edge getEdge(long id) {
        try {
            VersionStore.VersionRecord record = engine.versionStore()
                    .latestVisible(VersionStore.KIND_UPSERT_EDGE, id, visibility);
            if (record == null || record.isDelete()) {
                return null;
            }
            PayloadCodec.EdgePayload payload = PayloadCodec.decodeEdge(
                    engine.versionStore().payload(record), engine.typeDictionary());
            return new Edge(id, payload.type, payload.startNodeId, payload.endNodeId, payload.properties);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot resolve edge " + id, e);
        }
    }

    @Override
    public void updateEdge(long id, Map<String, Object> properties) {
        throw readOnly();
    }

    @Override
    public boolean removeEdge(long id) {
        throw readOnly();
    }

    @Override
    public List<Edge> getOutEdges(long nodeId) {
        List<Edge> result = new ArrayList<>();
        for (long edgeId : liveTargets(engine.adjOutPostings(), nodeId)) {
            Edge edge = getEdge(edgeId);
            if (edge != null && edge.getStartNodeId() == nodeId) {
                result.add(edge);
            }
        }
        return result;
    }

    @Override
    public List<Edge> getInEdges(long nodeId) {
        List<Edge> result = new ArrayList<>();
        for (long edgeId : liveTargets(engine.adjInPostings(), nodeId)) {
            Edge edge = getEdge(edgeId);
            if (edge != null && edge.getEndNodeId() == nodeId) {
                result.add(edge);
            }
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
        int dictId = engine.typeDictionary().idOf(edgeType);
        if (dictId < 0) {
            return new ArrayList<>();
        }
        List<Edge> result = new ArrayList<>();
        try {
            for (long edgeId : engine.etypePostings().resolveLive(dictId, visibility)) {
                Edge edge = getEdge(edgeId);
                if (edge != null && edgeType.equals(edge.getType())) {
                    result.add(edge);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot resolve edge type postings: " + edgeType, e);
        }
        return result;
    }

    @Override
    public long getEdgeCount() {
        if (cachedEdgeCount == null) {
            long count = 0;
            for (long id = 0; id < engine.nextEdgeId(); id++) {
                if (getEdge(id) != null) count++;
            }
            cachedEdgeCount = count;
        }
        return cachedEdgeCount;
    }

    private List<Long> liveTargets(PostingStore postings, long ownerSlot) {
        try {
            return postings.resolveLive(ownerSlot, visibility);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot resolve postings for owner " + ownerSlot, e);
        }
    }

    // ==================== 遍历 ====================

    @Override
    public Set<Long> traverse(long startNodeId, int maxDepth, String edgeType) {
        Set<Long> visited = new LinkedHashSet<>();
        Deque<Object[]> queue = new ArrayDeque<>();
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
        TreeSet<Long> matched = new TreeSet<>();
        Collection<Long> candidates;
        if (label == null) {
            candidates = new ArrayList<>();
            for (long id = 0; id < engine.nextNodeId(); id++) {
                candidates.add(id);
            }
        } else {
            candidates = getNodeIdsByLabel(label);
        }
        for (Long candidate : candidates) {
            Node node = getNode(candidate);
            if (node != null && Objects.equals(propertyValue, node.get(propertyKey))
                    && (label == null || node.hasLabel(label))) {
                matched.add(candidate);
            }
        }
        return new ArrayList<>(matched);
    }

    @Override
    public List<Node> getAllNodes() {
        List<Node> result = new ArrayList<>();
        for (long id = 0; id < engine.nextNodeId(); id++) {
            Node node = getNode(id);
            if (node != null) result.add(node);
        }
        return result;
    }

    @Override
    public List<Edge> getAllEdges() {
        List<Edge> result = new ArrayList<>();
        for (long id = 0; id < engine.nextEdgeId(); id++) {
            Edge edge = getEdge(id);
            if (edge != null) result.add(edge);
        }
        return result;
    }

    @Override
    public List<Long> getAllNodeIds() {
        List<Long> result = new ArrayList<>();
        for (long id = 0; id < engine.nextNodeId(); id++) {
            if (getNode(id) != null) result.add(id);
        }
        return result;
    }

    @Override
    public List<Long> getAllEdgeIds() {
        List<Long> result = new ArrayList<>();
        for (long id = 0; id < engine.nextEdgeId(); id++) {
            if (getEdge(id) != null) result.add(id);
        }
        return result;
    }

    // ==================== 事务 ====================

    @Override
    public void commit() {
        // 视图只读；提交走 GraphWriteTransaction。
    }

    @Override
    public void rollback() {
        // 视图只读。
    }

    // ==================== Schema ====================

    @Override
    public void createTag(TagSchema schema) {
        throw readOnly();
    }

    @Override
    public boolean dropTag(String tagName) {
        throw readOnly();
    }

    @Override
    public TagSchema getTagSchema(String tagName) {
        int dictId = engine.labelDictionary().idOf(tagName);
        if (dictId < 0) {
            return null;
        }
        try {
            VersionStore.VersionRecord record = engine.versionStore()
                    .latestVisible(VersionStore.KIND_UPSERT_TAG, dictId, visibility);
            if (record == null || record.isDelete()) {
                return null;
            }
            return PayloadCodec.decodeTagSchema(tagName, engine.versionStore().payload(record));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot resolve tag schema: " + tagName, e);
        }
    }

    @Override
    public List<String> listTags() {
        List<String> result = new ArrayList<>();
        for (String name : engine.labelDictionary().allNames()) {
            if (getTagSchema(name) != null) {
                result.add(name);
            }
        }
        return result;
    }

    @Override
    public void createEdgeType(EdgeTypeSchema schema) {
        throw readOnly();
    }

    @Override
    public boolean dropEdgeType(String edgeTypeName) {
        throw readOnly();
    }

    @Override
    public EdgeTypeSchema getEdgeTypeSchema(String edgeTypeName) {
        int dictId = engine.typeDictionary().idOf(edgeTypeName);
        if (dictId < 0) {
            return null;
        }
        try {
            VersionStore.VersionRecord record = engine.versionStore()
                    .latestVisible(VersionStore.KIND_UPSERT_EDGE_TYPE, dictId, visibility);
            if (record == null || record.isDelete()) {
                return null;
            }
            return PayloadCodec.decodeEdgeTypeSchema(edgeTypeName, engine.versionStore().payload(record));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot resolve edge type schema: " + edgeTypeName, e);
        }
    }

    @Override
    public List<String> listEdgeTypes() {
        List<String> result = new ArrayList<>();
        for (String name : engine.typeDictionary().allNames()) {
            if (getEdgeTypeSchema(name) != null) {
                result.add(name);
            }
        }
        return result;
    }

    // ==================== 属性索引 ====================

    @Override
    public boolean hasPropertyIndex(String label, String propertyKey) {
        int dictId = engine.indexNameDictionary().idOf(label + StorageEngine.INDEX_NAME_SEPARATOR + propertyKey);
        if (dictId < 0) {
            return false;
        }
        try {
            VersionStore.VersionRecord record = engine.versionStore()
                    .latestVisible(VersionStore.KIND_UPSERT_INDEX, dictId, visibility);
            return record != null && !record.isDelete();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot resolve index definition", e);
        }
    }

    @Override
    public boolean createPropertyIndex(String label, String propertyKey) {
        throw readOnly();
    }

    @Override
    public boolean dropPropertyIndex(String label, String propertyKey) {
        throw readOnly();
    }

    @Override
    public List<List<String>> getPropertyIndexes() {
        List<List<String>> result = new ArrayList<>();
        for (String composite : engine.indexNameDictionary().allNames()) {
            try {
                int dictId = engine.indexNameDictionary().idOf(composite);
                VersionStore.VersionRecord record = engine.versionStore()
                        .latestVisible(VersionStore.KIND_UPSERT_INDEX, dictId, visibility);
                if (record != null && !record.isDelete()) {
                    List<String> pair = new ArrayList<>(2);
                    pair.add(StorageEngine.indexLabel(composite));
                    pair.add(StorageEngine.indexPropertyKey(composite));
                    result.add(pair);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Cannot resolve index definition: " + composite, e);
            }
        }
        return result;
    }
}
