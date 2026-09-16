package com.zifang.z.graph.core;

import com.zifang.z.graph.api.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InMemoryGraphStore 单元测试 — 覆盖节点/边/遍历/属性操作。
 */
class InMemoryGraphStoreTest {

    private InMemoryGraphStore store;

    @BeforeEach
    void setUp() { store = new InMemoryGraphStore(); }

    @AfterEach
    void tearDown() { store = null; }

    // ==================== 节点操作 ====================

    @Test
    void addNodeAndRetrieve() {
        Node n = store.addNode("Person", Map.of("name", "Alice", "age", 30));
        assertEquals(0, n.getId());
        assertTrue(n.hasLabel("Person"));
        assertEquals("Alice", n.get("name"));
        assertEquals(30, n.get("age"));
    }

    @Test
    void addMultipleNodes() {
        store.addNode("Person", Map.of("name", "Alice"));
        store.addNode("Person", Map.of("name", "Bob"));
        store.addNode("City", Map.of("name", "Beijing"));
        assertEquals(3, store.getNodeCount());
        assertEquals(2, store.getNodeIdsByLabel("Person").size());
        assertEquals(1, store.getNodeIdsByLabel("City").size());
    }

    @Test
    void updateNode() {
        Node n = store.addNode("Person", Map.of("name", "Alice", "age", 30));
        store.updateNode(n.getId(), Map.of("age", 31));
        assertEquals(31, store.getNode(n.getId()).get("age"));
    }

    @Test
    void removeNodeRemovesEdges() {
        Node a = store.addNode("Person", Map.of("name", "Alice"));
        Node b = store.addNode("Person", Map.of("name", "Bob"));
        store.addEdge("FRIEND", a.getId(), b.getId(), Map.of());
        assertEquals(1, store.getEdgeCount());
        store.removeNode(a.getId());
        assertEquals(0, store.getEdgeCount());
    }

    // ==================== 边操作 ====================

    @Test
    void addEdgeAndRetrieve() {
        Node a = store.addNode("Person", Map.of("name", "Alice"));
        Node b = store.addNode("Person", Map.of("name", "Bob"));
        Edge e = store.addEdge("FRIEND", a.getId(), b.getId(), Map.of("since", 2020));
        assertEquals("FRIEND", e.getType());
        assertEquals(a.getId(), e.getStartNodeId());
        assertEquals(b.getId(), e.getEndNodeId());
        assertEquals(2020, e.get("since"));
    }

    @Test
    void getOutEdgesAndInEdges() {
        Node a = store.addNode("Person", Map.of("name", "Alice"));
        Node b = store.addNode("Person", Map.of("name", "Bob"));
        Node c = store.addNode("Person", Map.of("name", "Charlie"));
        store.addEdge("FRIEND", a.getId(), b.getId(), Map.of());
        store.addEdge("FRIEND", a.getId(), c.getId(), Map.of());
        store.addEdge("FRIEND", b.getId(), c.getId(), Map.of());
        assertEquals(2, store.getOutEdges(a.getId()).size());
        assertEquals(2, store.getInEdges(c.getId()).size());
    }

    @Test
    void addEdgeToNonExistentNodeThrows() {
        Node a = store.addNode("Person", Map.of("name", "Alice"));
        assertThrows(IllegalArgumentException.class,
                () -> store.addEdge("FRIEND", a.getId(), 9999L, Map.of()));
    }

    @Test
    void removeEdge() {
        Node a = store.addNode("Person", Map.of("name", "Alice"));
        Node b = store.addNode("Person", Map.of("name", "Bob"));
        Edge e = store.addEdge("FRIEND", a.getId(), b.getId(), Map.of());
        assertTrue(store.removeEdge(e.getId()));
        assertEquals(0, store.getEdgeCount());
    }

    // ==================== 遍历 ====================

    @Test
    void traverse() {
        Node a = store.addNode("City", Map.of("name", "A"));
        Node b = store.addNode("City", Map.of("name", "B"));
        Node c = store.addNode("City", Map.of("name", "C"));
        Node d = store.addNode("City", Map.of("name", "D"));
        store.addEdge("ROAD", a.getId(), b.getId(), Map.of());
        store.addEdge("ROAD", b.getId(), c.getId(), Map.of());
        store.addEdge("ROAD", c.getId(), d.getId(), Map.of());

        Set<Long> reachable = store.traverse(a.getId(), 2, "ROAD");
        assertTrue(reachable.contains(a.getId()));
        assertTrue(reachable.contains(b.getId()));
        assertTrue(reachable.contains(c.getId()));
        assertFalse(reachable.contains(d.getId())); // depth=2, A→B→C only
    }

    // ==================== 属性索引 ====================

    @Test
    void findNodesByProperty() {
        store.addNode("Person", Map.of("name", "Alice", "age", 30));
        store.addNode("Person", Map.of("name", "Bob", "age", 25));
        store.addNode("Person", Map.of("name", "Charlie", "age", 30));
        List<Long> result = store.findNodesByProperty("Person", "age", 30);
        assertEquals(2, result.size());
    }

    // ==================== 属性索引 ====================

    @Test
    void propertyIndexSupportsLookupAndUpdates() {
        Node alice = store.addNode("Person", Map.of("name", "Alice", "city", "Beijing"));
        store.addNode("Person", Map.of("name", "Bob", "city", "Shanghai"));
        assertTrue(store.createPropertyIndex("Person", "city"));
        assertTrue(store.hasPropertyIndex("Person", "city"));
        assertEquals(List.of(alice.getId()), store.findNodesByProperty("Person", "city", "Beijing"));

        store.updateNode(alice.getId(), Map.of("city", "Shenzhen"));
        assertTrue(store.findNodesByProperty("Person", "city", "Beijing").isEmpty());
        assertEquals(List.of(alice.getId()), store.findNodesByProperty("Person", "city", "Shenzhen"));
        assertTrue(store.dropPropertyIndex("Person", "city"));
    }

    // ==================== 诊断 ====================

    @Test
    void getStats() {
        store.addNode("Person", Map.of("name", "Alice"));
        store.addNode("Person", Map.of("name", "Bob"));
        Node a = store.getNode(0);
        Node b = store.getNode(1);
        store.addEdge("FRIEND", a.getId(), b.getId(), Map.of());
        Map<String, Object> stats = store.getStats();
        assertEquals(2, stats.get("nodeCount"));
        assertEquals(1, stats.get("edgeCount"));
    }
}
