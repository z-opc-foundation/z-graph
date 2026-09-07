package com.zifang.z.graph.core;

import com.zifang.z.graph.api.Edge;
import com.zifang.z.graph.api.GraphStore;
import com.zifang.z.graph.api.Node;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 提交快照的只读视图。查询引擎可以在该视图上执行 MATCH/RETURN，任何写操作都会被拒绝。
 */
public final class ReadOnlyGraphStore implements GraphStore {

    private final GraphStore delegate;

    public ReadOnlyGraphStore(GraphStore delegate) {
        this.delegate = delegate;
    }

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
        Node node = delegate.getNode(id);
        return node == null ? null : copyNode(node);
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
        return List.copyOf(delegate.getNodeIdsByLabel(label));
    }

    @Override
    public long getNodeCount() {
        return delegate.getNodeCount();
    }

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
        Edge edge = delegate.getEdge(id);
        return edge == null ? null : copyEdge(edge);
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
        return delegate.getOutEdges(nodeId).stream().map(ReadOnlyGraphStore::copyEdge).toList();
    }

    @Override
    public List<Edge> getInEdges(long nodeId) {
        return delegate.getInEdges(nodeId).stream().map(ReadOnlyGraphStore::copyEdge).toList();
    }

    @Override
    public List<Edge> getEdges(long nodeId) {
        return delegate.getEdges(nodeId).stream().map(ReadOnlyGraphStore::copyEdge).toList();
    }

    @Override
    public List<Edge> getEdgesByType(String edgeType) {
        return delegate.getEdgesByType(edgeType).stream().map(ReadOnlyGraphStore::copyEdge).toList();
    }

    @Override
    public long getEdgeCount() {
        return delegate.getEdgeCount();
    }

    @Override
    public Set<Long> traverse(long startNodeId, int maxDepth, String edgeType) {
        return Set.copyOf(delegate.traverse(startNodeId, maxDepth, edgeType));
    }

    @Override
    public List<Long> findNodesByProperty(String label, String propertyKey, Object propertyValue) {
        return List.copyOf(delegate.findNodesByProperty(label, propertyKey, propertyValue));
    }

    @Override
    public List<Node> getAllNodes() {
        return delegate.getAllNodes().stream().map(ReadOnlyGraphStore::copyNode).toList();
    }

    @Override
    public List<Edge> getAllEdges() {
        return delegate.getAllEdges().stream().map(ReadOnlyGraphStore::copyEdge).toList();
    }

    @Override
    public void commit() {
        // 只读视图没有待提交的变更。
    }

    @Override
    public void rollback() {
        // 只读视图没有待回滚的变更。
    }

    public boolean hasPropertyIndex(String label, String propertyKey) {
        return delegate instanceof InMemoryGraphStore store && store.hasPropertyIndex(label, propertyKey);
    }

    private static Node copyNode(Node node) {
        return new Node(node.getId(), node.getLabels(), InMemoryGraphStore.deepCopyMap(node.getProperties()));
    }

    private static Edge copyEdge(Edge edge) {
        return new Edge(edge.getId(), edge.getType(), edge.getStartNodeId(), edge.getEndNodeId(),
                InMemoryGraphStore.deepCopyMap(edge.getProperties()));
    }

    private UnsupportedOperationException readOnly() {
        return new UnsupportedOperationException("Graph checkout is read-only");
    }
}
