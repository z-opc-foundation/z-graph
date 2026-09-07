package com.zifang.z.graph.core;

import com.zifang.z.graph.api.*;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖 NebulaGraph 风格的 DDL Cypher 与变长路径匹配、OPTIONAL MATCH。
 */
class DdlAndPathTest {

    private InMemoryGraphStore store;
    private CypherEngine engine;

    @BeforeEach
    void setUp() {
        store = new InMemoryGraphStore();
        engine = new CypherEngine(store);
    }

    @AfterEach
    void tearDown() {
        store = null;
        engine = null;
    }

    // ==================== DDL: CREATE/DROP TAG ====================

    @Test
    void createTagAndDropTag() {
        List<Map<String, Object>> created = engine.execute(
                "CREATE TAG Person (name STRING NOT NULL, age INT)");
        assertEquals(1, created.size());
        assertEquals("Person", created.get(0).get("Name"));
        assertNotNull(store.getTagSchema("Person"));

        List<Map<String, Object>> dropped = engine.execute("DROP TAG Person");
        assertEquals("Person", dropped.get(0).get("Dropped"));
        assertNull(store.getTagSchema("Person"));
    }

    @Test
    void dropUnknownTagFails() {
        assertThrows(CypherEngine.CypherException.class,
                () -> engine.execute("DROP TAG Unknown"));
    }

    @Test
    void createTagWithBadTypeFails() {
        assertThrows(CypherEngine.CypherException.class,
                () -> engine.execute("CREATE TAG Person (name BLOB)"));
    }

    // ==================== DDL: CREATE/DROP EDGE TYPE ====================

    @Test
    void createEdgeAndDropEdge() {
        List<Map<String, Object>> created = engine.execute(
                "CREATE EDGE KNOWS (since INT NOT NULL)");
        assertEquals(1, created.size());
        assertEquals("KNOWS", created.get(0).get("Name"));
        assertNotNull(store.getEdgeTypeSchema("KNOWS"));

        List<Map<String, Object>> dropped = engine.execute("DROP EDGE KNOWS");
        assertEquals("KNOWS", dropped.get(0).get("Dropped"));
        assertNull(store.getEdgeTypeSchema("KNOWS"));
    }

    @Test
    void createEdgeWithEdgeTypeKeywordWorks() {
        engine.execute("CREATE EDGE TYPE LIKES (weight DOUBLE)");
        assertNotNull(store.getEdgeTypeSchema("LIKES"));
    }

    // ==================== DDL: CREATE/DROP TAG INDEX ====================

    @Test
    void createTagIndexAndUseIt() {
        engine.execute("CREATE TAG Person (name STRING NOT NULL)");
        engine.execute("CREATE TAG INDEX ON Person.name");
        assertTrue(store.hasPropertyIndex("Person", "name"));
        List<Map<String, Object>> indexes = engine.execute("SHOW INDEXES");
        assertEquals(1, indexes.size());
        assertEquals("Person", indexes.get(0).get("Tag"));

        engine.execute("DROP TAG INDEX ON Person.name");
        assertFalse(store.hasPropertyIndex("Person", "name"));
    }

    @Test
    void dropUnknownIndexFails() {
        assertThrows(CypherEngine.CypherException.class,
                () -> engine.execute("DROP TAG INDEX ON Person.name"));
    }

    // ==================== Variable-length path ====================

    @Test
    void variableLengthPathFindsTwoHops() {
        Node a = store.addNode("City", Map.of("name", "A"));
        Node b = store.addNode("City", Map.of("name", "B"));
        Node c = store.addNode("City", Map.of("name", "C"));
        Node d = store.addNode("City", Map.of("name", "D"));
        store.addEdge("ROAD", a.getId(), b.getId(), Map.of());
        store.addEdge("ROAD", b.getId(), c.getId(), Map.of());
        store.addEdge("ROAD", c.getId(), d.getId(), Map.of());

        List<Map<String, Object>> rows = engine.execute(
                "MATCH (a:City)-[:ROAD*1..3]->(b:City) WHERE a.name = 'A' RETURN b.name AS name");
        // A → B (1), A → B → C (2), A → B → C → D (3)
        assertTrue(rows.size() >= 3);
        List<String> names = rows.stream().map(r -> (String) r.get("name")).toList();
        assertTrue(names.contains("B"));
        assertTrue(names.contains("C"));
        assertTrue(names.contains("D"));
    }

    @Test
    void variableLengthPathTypeFiltering() {
        Node a = store.addNode("City", Map.of("name", "A"));
        Node b = store.addNode("City", Map.of("name", "B"));
        Node c = store.addNode("City", Map.of("name", "C"));
        store.addEdge("ROAD", a.getId(), b.getId(), Map.of());
        store.addEdge("FLIGHT", b.getId(), c.getId(), Map.of());

        List<Map<String, Object>> rows = engine.execute(
                "MATCH (a:City)-[:ROAD*1..3]->(b:City) WHERE a.name = 'A' RETURN b.name AS name");
        // 只走 ROAD 应当只到 B
        assertEquals(1, rows.size());
        assertEquals("B", rows.get(0).get("name"));
    }

    @Test
    void variableLengthPathRespectsMinHops() {
        Node a = store.addNode("City", Map.of("name", "A"));
        Node b = store.addNode("City", Map.of("name", "B"));
        Node c = store.addNode("City", Map.of("name", "C"));
        store.addEdge("ROAD", a.getId(), b.getId(), Map.of());
        store.addEdge("ROAD", b.getId(), c.getId(), Map.of());

        List<Map<String, Object>> rows = engine.execute(
                "MATCH (a:City)-[:ROAD*2..2]->(c:City) WHERE a.name = 'A' RETURN c.name AS name");
        // 至少 2 跳，必须跳到 C
        assertEquals(1, rows.size());
        assertEquals("C", rows.get(0).get("name"));
    }

    // ==================== OPTIONAL MATCH ====================

    @Test
    void optionalMatchReturnsNullRowWhenNothingMatches() {
        List<Map<String, Object>> rows = engine.execute(
                "OPTIONAL MATCH (n:Ghost) RETURN n.name AS name");
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).containsKey("name"));
        assertNull(rows.get(0).get("name"));
    }

    @Test
    void optionalMatchFallsBackToNormalMatchWhenHits() {
        store.addNode("Person", Map.of("name", "Alice"));
        List<Map<String, Object>> rows = engine.execute(
                "OPTIONAL MATCH (n:Person) RETURN n.name AS name");
        assertEquals(1, rows.size());
        assertEquals("Alice", rows.get(0).get("name"));
    }

    @Test
    void optionalMatchRespectsWhereFilter() {
        store.addNode("Person", Map.of("name", "Alice", "age", 30));
        store.addNode("Person", Map.of("name", "Bob", "age", 20));
        // 命中 Alice
        List<Map<String, Object>> hit = engine.execute(
                "OPTIONAL MATCH (n:Person) WHERE n.age > 25 RETURN n.name AS name");
        assertEquals(1, hit.size());
        assertEquals("Alice", hit.get(0).get("name"));

        // 不命中任何行
        List<Map<String, Object>> none = engine.execute(
                "OPTIONAL MATCH (n:Person) WHERE n.age > 100 RETURN n.name AS name");
        assertEquals(1, none.size());
        assertNull(none.get(0).get("name"));
    }
}
