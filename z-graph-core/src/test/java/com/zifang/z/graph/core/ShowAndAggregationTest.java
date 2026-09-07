package com.zifang.z.graph.core;

import com.zifang.z.graph.api.*;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖 NebulaGraph 风格的管理 Cypher：
 * - SHOW TAGS / SHOW EDGES / SHOW INDEXES
 * - SHOW TAG <name> / SHOW EDGE <name>
 * - 聚合：count / sum / avg / min / max，含 GROUP BY 语义
 */
class ShowAndAggregationTest {

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

    // ==================== SHOW ====================

    @Test
    void showTagsReturnsRegisteredTags() {
        store.createTag(new TagSchema("Person", List.of()));
        store.createTag(new TagSchema("City", List.of()));
        List<Map<String, Object>> rows = engine.execute("SHOW TAGS");
        assertEquals(2, rows.size());
        assertEquals("City", rows.get(0).get("Name"));
        assertEquals("Person", rows.get(1).get("Name"));
    }

    @Test
    void showEdgesReturnsRegisteredEdgeTypes() {
        store.createEdgeType(new EdgeTypeSchema("KNOWS"));
        store.createEdgeType(new EdgeTypeSchema("LIVES_IN"));
        List<Map<String, Object>> rows = engine.execute("SHOW EDGES");
        assertEquals(2, rows.size());
        assertEquals("KNOWS", rows.get(0).get("Name"));
        assertEquals("LIVES_IN", rows.get(1).get("Name"));
    }

    @Test
    void showIndexesListsPropertyIndexes() {
        store.createPropertyIndex("Person", "name");
        List<Map<String, Object>> rows = engine.execute("SHOW INDEXES");
        assertEquals(1, rows.size());
        assertEquals("Person", rows.get(0).get("Tag"));
        assertEquals("name", rows.get(0).get("Property"));
    }

    @Test
    void showTagDescribesFields() {
        store.createTag(new TagSchema("Person", List.of(
                new TagSchema.Field("name", TagSchema.DataType.STRING, false),
                new TagSchema.Field("age", TagSchema.DataType.INT))));
        List<Map<String, Object>> rows = engine.execute("SHOW TAG Person");
        assertEquals(2, rows.size());
        assertEquals("name", rows.get(0).get("Field"));
        assertEquals("STRING", rows.get(0).get("Type"));
        assertEquals("NO", rows.get(0).get("Null"));
    }

    @Test
    void showEdgeDescribesFields() {
        store.createEdgeType(new EdgeTypeSchema("KNOWS", List.of(
                new TagSchema.Field("since", TagSchema.DataType.INT))));
        List<Map<String, Object>> rows = engine.execute("SHOW EDGE KNOWS");
        assertEquals(1, rows.size());
        assertEquals("since", rows.get(0).get("Field"));
        assertEquals("INT", rows.get(0).get("Type"));
    }

    @Test
    void showUnknownTagFails() {
        assertThrows(CypherEngine.CypherException.class, () -> engine.execute("SHOW TAG Missing"));
    }

    // ==================== 聚合 ====================

    @Test
    void countStarReturnsRowCount() {
        store.addNode("Person", Map.of("name", "Alice"));
        store.addNode("Person", Map.of("name", "Bob"));
        store.addNode("City", Map.of("name", "Beijing"));
        List<Map<String, Object>> rows = engine.execute("MATCH (n) RETURN count(*) AS c");
        assertEquals(1, rows.size());
        assertEquals(3L, rows.get(0).get("c"));
    }

    @Test
    void countOnExpressionIgnoresNull() {
        store.addNode("Person", Map.of("name", "Alice", "age", 30));
        store.addNode("Person", Map.of("name", "Bob"));
        store.addNode("Person", Map.of("name", "Charlie", "age", 25));
        List<Map<String, Object>> rows = engine.execute(
                "MATCH (n:Person) RETURN count(n.age) AS c");
        assertEquals(1, rows.size());
        assertEquals(2L, rows.get(0).get("c"));
    }

    @Test
    void sumAndAvgOverNumericProperty() {
        store.addNode("Person", Map.of("age", 30));
        store.addNode("Person", Map.of("age", 25));
        store.addNode("Person", Map.of("age", 35));
        List<Map<String, Object>> sumRows = engine.execute(
                "MATCH (n:Person) RETURN sum(n.age) AS total");
        assertEquals(1, sumRows.size());
        assertEquals(90.0, ((Number) sumRows.get(0).get("total")).doubleValue(), 1e-9);

        List<Map<String, Object>> avgRows = engine.execute(
                "MATCH (n:Person) RETURN avg(n.age) AS avg_age");
        assertEquals(1, avgRows.size());
        assertEquals(30.0, ((Number) avgRows.get(0).get("avg_age")).doubleValue(), 1e-9);
    }

    @Test
    void minAndMaxAcrossMixedValues() {
        store.addNode("Person", Map.of("age", 30, "name", "Alice"));
        store.addNode("Person", Map.of("age", 25, "name", "Bob"));
        store.addNode("Person", Map.of("age", 35, "name", "Charlie"));

        List<Map<String, Object>> minRows = engine.execute(
                "MATCH (n:Person) RETURN min(n.age) AS youngest");
        assertEquals(1, minRows.size());
        assertEquals(25.0, ((Number) minRows.get(0).get("youngest")).doubleValue(), 1e-9);

        List<Map<String, Object>> maxRows = engine.execute(
                "MATCH (n:Person) RETURN max(n.age) AS oldest");
        assertEquals(1, maxRows.size());
        assertEquals(35.0, ((Number) maxRows.get(0).get("oldest")).doubleValue(), 1e-9);
    }

    @Test
    void aggregationGroupsByNonAggregateColumn() {
        store.addNode("Person", Map.of("city", "Beijing", "age", 30));
        store.addNode("Person", Map.of("city", "Beijing", "age", 25));
        store.addNode("Person", Map.of("city", "Shanghai", "age", 40));

        List<Map<String, Object>> rows = engine.execute(
                "MATCH (n:Person) RETURN n.city AS city, count(n) AS cnt, avg(n.age) AS avg_age");
        assertEquals(2, rows.size());
        Map<String, Object> beijing = rows.stream()
                .filter(r -> "Beijing".equals(r.get("city")))
                .findFirst()
                .orElseThrow();
        assertEquals(2L, beijing.get("cnt"));
        assertEquals(27.5, ((Number) beijing.get("avg_age")).doubleValue(), 1e-9);
        Map<String, Object> shanghai = rows.stream()
                .filter(r -> "Shanghai".equals(r.get("city")))
                .findFirst()
                .orElseThrow();
        assertEquals(1L, shanghai.get("cnt"));
        assertEquals(40.0, ((Number) shanghai.get("avg_age")).doubleValue(), 1e-9);
    }

    @Test
    void emptyAggregationReturnsNull() {
        List<Map<String, Object>> rows = engine.execute(
                "MATCH (n:Ghost) RETURN sum(n.age) AS total, count(n.age) AS cnt");
        assertEquals(1, rows.size());
        assertNull(rows.get(0).get("total"));
        assertEquals(0L, rows.get(0).get("cnt"));
    }
}
