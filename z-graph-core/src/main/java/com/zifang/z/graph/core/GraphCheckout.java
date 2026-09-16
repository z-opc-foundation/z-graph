package com.zifang.z.graph.core;

import com.zifang.z.graph.api.EdgeTypeSchema;
import com.zifang.z.graph.api.GraphCommit;
import com.zifang.z.graph.api.GraphStore;
import com.zifang.z.graph.api.TagSchema;

import java.util.List;
import java.util.Map;

/**
 * 从指定 commit 切出的查询视图。视图绑定 immutable commit，不会跟随任何分支继续变化。
 */
public final class GraphCheckout {

    private final GraphCommit commit;
    private final ReadOnlyGraphStore store;
    private final CypherEngine cypherEngine;

    GraphCheckout(GraphCommit commit, InMemoryGraphStore snapshot) {
        this.commit = commit;
        this.store = new ReadOnlyGraphStore(snapshot);
        this.cypherEngine = new CypherEngine(store);
    }

    public GraphCommit getCommit() {
        return commit;
    }

    public GraphStore getStore() {
        return store;
    }

    public List<Map<String, Object>> query(String cypher) {
        return cypherEngine.execute(cypher);
    }

    public boolean hasPropertyIndex(String label, String propertyKey) {
        return store.hasPropertyIndex(label, propertyKey);
    }

    public long getNodeCount() {
        return store.getNodeCount();
    }

    public long getEdgeCount() {
        return store.getEdgeCount();
    }

    public TagSchema getTagSchema(String tagName) {
        return store.getTagSchema(tagName);
    }

    public List<String> listTags() {
        return store.listTags();
    }

    public EdgeTypeSchema getEdgeTypeSchema(String edgeTypeName) {
        return store.getEdgeTypeSchema(edgeTypeName);
    }

    public List<String> listEdgeTypes() {
        return store.listEdgeTypes();
    }
}
