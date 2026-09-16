package com.zifang.z.graph.core;

import com.zifang.z.graph.api.Edge;
import com.zifang.z.graph.api.EdgeTypeSchema;
import com.zifang.z.graph.api.GraphCommit;
import com.zifang.z.graph.api.GraphStore;
import com.zifang.z.graph.api.Node;
import com.zifang.z.graph.api.TagSchema;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个分支上的隔离写工作区。提交前的变更只存在于当前事务，不会污染已有 commit。
 */
public final class GraphWriteTransaction implements GraphStore {

    private final GraphVersionStore repository;
    private final String branch;
    private final String baseCommitId;
    private final InMemoryGraphStore workingStore;
    private boolean closed;

    GraphWriteTransaction(GraphVersionStore repository,
                          String branch,
                          String baseCommitId,
                          InMemoryGraphStore workingStore) {
        this.repository = repository;
        this.branch = branch;
        this.baseCommitId = baseCommitId;
        this.workingStore = workingStore;
    }

    public String getBranch() {
        return branch;
    }

    public String getBaseCommitId() {
        return baseCommitId;
    }

    public boolean isClosed() {
        return closed;
    }

    public GraphCommit commit(String author, String message) {
        ensureOpen();
        GraphCommit commit = repository.commit(branch, baseCommitId, workingStore, author, message);
        closed = true;
        return commit;
    }

    @Override
    public void rollback() {
        closed = true;
    }

    @Override
    public void commit() {
        ensureOpen();
        // GraphStore 的无参 commit 保持兼容语义，真正的版本提交使用 commit(author, message)。
    }

    @Override
    public Node addNode(String label, Map<String, Object> properties) {
        ensureOpen();
        return workingStore.addNode(repository.allocateNodeId(), label, properties);
    }

    @Override
    public Node addNode(long id, String label, Map<String, Object> properties) {
        ensureOpen();
        repository.reserveNodeId(id);
        return workingStore.addNode(id, label, properties);
    }

    @Override
    public Node getNode(long id) {
        ensureOpen();
        return workingStore.getNode(id);
    }

    @Override
    public void updateNode(long id, Map<String, Object> properties) {
        ensureOpen();
        workingStore.updateNode(id, properties);
    }

    @Override
    public boolean removeNode(long id) {
        ensureOpen();
        return workingStore.removeNode(id);
    }

    @Override
    public List<Long> getNodeIdsByLabel(String label) {
        ensureOpen();
        return workingStore.getNodeIdsByLabel(label);
    }

    @Override
    public long getNodeCount() {
        ensureOpen();
        return workingStore.getNodeCount();
    }

    @Override
    public Edge addEdge(String type, long startNodeId, long endNodeId, Map<String, Object> properties) {
        ensureOpen();
        return workingStore.addEdge(repository.allocateEdgeId(), type, startNodeId, endNodeId, properties);
    }

    @Override
    public Edge addEdge(long id, String type, long startNodeId, long endNodeId, Map<String, Object> properties) {
        ensureOpen();
        repository.reserveEdgeId(id);
        return workingStore.addEdge(id, type, startNodeId, endNodeId, properties);
    }

    @Override
    public Edge getEdge(long id) {
        ensureOpen();
        return workingStore.getEdge(id);
    }

    @Override
    public void updateEdge(long id, Map<String, Object> properties) {
        ensureOpen();
        workingStore.updateEdge(id, properties);
    }

    @Override
    public boolean removeEdge(long id) {
        ensureOpen();
        return workingStore.removeEdge(id);
    }

    @Override
    public List<Edge> getOutEdges(long nodeId) {
        ensureOpen();
        return workingStore.getOutEdges(nodeId);
    }

    @Override
    public List<Edge> getInEdges(long nodeId) {
        ensureOpen();
        return workingStore.getInEdges(nodeId);
    }

    @Override
    public List<Edge> getEdges(long nodeId) {
        ensureOpen();
        return workingStore.getEdges(nodeId);
    }

    @Override
    public List<Edge> getEdgesByType(String edgeType) {
        ensureOpen();
        return workingStore.getEdgesByType(edgeType);
    }

    @Override
    public long getEdgeCount() {
        ensureOpen();
        return workingStore.getEdgeCount();
    }

    @Override
    public Set<Long> traverse(long startNodeId, int maxDepth, String edgeType) {
        ensureOpen();
        return workingStore.traverse(startNodeId, maxDepth, edgeType);
    }

    @Override
    public List<Long> findNodesByProperty(String label, String propertyKey, Object propertyValue) {
        ensureOpen();
        return workingStore.findNodesByProperty(label, propertyKey, propertyValue);
    }

    public boolean createPropertyIndex(String label, String propertyKey) {
        ensureOpen();
        return workingStore.createPropertyIndex(label, propertyKey);
    }

    public boolean dropPropertyIndex(String label, String propertyKey) {
        ensureOpen();
        return workingStore.dropPropertyIndex(label, propertyKey);
    }

    @Override
    public void createTag(TagSchema schema) {
        ensureOpen();
        workingStore.createTag(schema);
    }

    @Override
    public boolean dropTag(String tagName) {
        ensureOpen();
        return workingStore.dropTag(tagName);
    }

    @Override
    public TagSchema getTagSchema(String tagName) {
        ensureOpen();
        return workingStore.getTagSchema(tagName);
    }

    @Override
    public List<String> listTags() {
        ensureOpen();
        return workingStore.listTags();
    }

    @Override
    public void createEdgeType(EdgeTypeSchema schema) {
        ensureOpen();
        workingStore.createEdgeType(schema);
    }

    @Override
    public boolean dropEdgeType(String edgeTypeName) {
        ensureOpen();
        return workingStore.dropEdgeType(edgeTypeName);
    }

    @Override
    public EdgeTypeSchema getEdgeTypeSchema(String edgeTypeName) {
        ensureOpen();
        return workingStore.getEdgeTypeSchema(edgeTypeName);
    }

    @Override
    public List<String> listEdgeTypes() {
        ensureOpen();
        return workingStore.listEdgeTypes();
    }

    @Override
    public List<Node> getAllNodes() {
        ensureOpen();
        return workingStore.getAllNodes();
    }

    @Override
    public List<Edge> getAllEdges() {
        ensureOpen();
        return workingStore.getAllEdges();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Graph write transaction is already closed");
        }
    }
}
