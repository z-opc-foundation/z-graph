package com.zifang.z.graph.core;

import com.zifang.z.graph.api.*;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖 EXPLAIN / DESCRIBE / SHOW STATS / CREATE EDGE INDEX / EXISTS 子查询。
 */
class ExplainAndDescribeTest {

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

    // ==================== EXPLAIN ====================

    @Test
    void explainDdlAndMetaReturnsSingleStep() {
        List<Map<String, Object>> plan = engine.execute("EXPLAIN CREATE TAG Person (name STRING)");
        assertEquals(1, plan.size());
        assertEquals("DDL", plan.get(0).get("Operator"));
    }

    @Test
    void explainIndexSeekWhenIndexExists() {
        store.createPropertyIndex("Person", "name");
        List<Map<String, Object>> plan = engine.execute(
                "EXPLAIN MATCH (n:Person) WHERE n.name = 'Alice' RETURN n");
        assertEquals(2, plan.size());
        assertEquals("IndexSeek", plan.get(0).get("Operator"));
        assertEquals("Project", plan.get(1).get("Operator"));
    }

    @Test
    void explainLabelScanWhenNoIndex() {
        List<Map<String, Object>> plan = engine.execute(
                "EXPLAIN MATCH (n:Person) WHERE n.name = 'Alice' RETURN n");
        assertEquals(2, plan.size());
        assertEquals("LabelScan", plan.get(0).get("Operator"));
    }

    @Test
    void explainVariableLengthPath() {
        List<Map<String, Object>> plan = engine.execute(
                "EXPLAIN MATCH (a:Person)-[:KNOWS*1..3]->(b:Person) RETURN b");
        assertTrue(plan.stream().anyMatch(p -> "VarLenExpand".equals(p.get("Operator"))));
    }

    @Test
    void explainAggregateShowsGroupingKeys() {
        List<Map<String, Object>> plan = engine.execute(
                "EXPLAIN MATCH (n:Person) RETURN n.city AS city, count(n) AS cnt");
        Map<String, Object> agg = plan.stream()
                .filter(p -> "Aggregate".equals(p.get("Operator")))
                .findFirst()
                .orElseThrow();
        assertTrue(agg.get("Detail").toString().contains("cnt"));
        assertTrue(agg.get("Detail").toString().contains("city"));
    }

    // ==================== DESCRIBE ====================

    @Test
    void describeTagMatchesShowTag() {
        engine.execute("CREATE TAG Person (name STRING NOT NULL, age INT)");
        List<Map<String, Object>> a = engine.execute("DESCRIBE TAG Person");
        List<Map<String, Object>> b = engine.execute("SHOW TAG Person");
        assertEquals(a, b);
    }

    @Test
    void describeEdgeMatchesShowEdge() {
        engine.execute("CREATE EDGE KNOWS (since INT NOT NULL)");
        List<Map<String, Object>> a = engine.execute("DESCRIBE EDGE KNOWS");
        List<Map<String, Object>> b = engine.execute("SHOW EDGE KNOWS");
        assertEquals(a, b);
    }

    @Test
    void showStatsReportsGraphMetrics() {
        store.addNode("Person", Map.of("name", "Alice"));
        store.addNode("Person", Map.of("name", "Bob"));
        List<Map<String, Object>> rows = engine.execute("SHOW STATS");
        assertEquals(1, rows.size());
        Map<String, Object> stats = rows.get(0);
        assertEquals(2, stats.get("nodeCount"));
        assertEquals(0, stats.get("edgeCount"));
    }

    // ==================== CREATE EDGE INDEX ====================

    @Test
    void createEdgeIndexAndShowIt() {
        engine.execute("CREATE EDGE KNOWS (since INT)");
        List<Map<String, Object>> created = engine.execute(
                "CREATE EDGE INDEX ON KNOWS.since");
        assertEquals("EDGE", created.get(0).get("Kind"));
        assertEquals("KNOWS.since", created.get(0).get("Name"));
        assertTrue(store.hasPropertyIndex("KNOWS", "since"));

        List<Map<String, Object>> rows = engine.execute("SHOW INDEXES");
        Map<String, Object> edgeIndex = rows.stream()
                .filter(r -> "KNOWS".equals(r.get("On")) && "since".equals(r.get("Property")))
                .findFirst()
                .orElseThrow();
        assertEquals("EDGE", edgeIndex.get("Kind"));
    }

    @Test
    void dropEdgeIndex() {
        engine.execute("CREATE EDGE INDEX ON KNOWS.since");
        engine.execute("DROP EDGE INDEX ON KNOWS.since");
        assertFalse(store.hasPropertyIndex("KNOWS", "since"));
    }

    // ==================== EXISTS 子查询 ====================

    @Test
    void existsOutgoingEdgeFilters() {
        Node alice = store.addNode("Person", Map.of("name", "Alice"));
        Node bob = store.addNode("Person", Map.of("name", "Bob"));
        store.addEdge("KNOWS", alice.getId(), bob.getId(), Map.of());

        List<Map<String, Object>> rows = engine.execute(
                "MATCH (n:Person) WHERE EXISTS { (n)-[]->() } RETURN n.name AS name");
        assertEquals(1, rows.size());
        assertEquals("Alice", rows.get(0).get("name"));
    }

    @Test
    void existsReturnsFalseWhenNoEdges() {
        store.addNode("Person", Map.of("name", "Alice"));
        List<Map<String, Object>> rows = engine.execute(
                "MATCH (n:Person) WHERE EXISTS { (n)-[]->() } RETURN n.name AS name");
        assertTrue(rows.isEmpty());
    }

    @Test
    void notExistsInvertsSemantics() {
        store.addNode("Person", Map.of("name", "Alice"));
        store.addNode("Person", Map.of("name", "Bob"));
        List<Map<String, Object>> rows = engine.execute(
                "MATCH (n:Person) WHERE NOT EXISTS { (n)-[]->() } RETURN n.name AS name");
        assertEquals(2, rows.size());
    }
}
