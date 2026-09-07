package com.zifang.z.graph.core;

import com.zifang.z.graph.api.*;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖：
 * <ul>
 *     <li>索引下推优化：单节点 MATCH + WHERE 等值 + 索引命中直接走索引；</li>
 *     <li>ALTER TAG / ALTER EDGE 的 ADD / DROP 子句；</li>
 *     <li>REBUILD INDEX 命令。</li>
 * </ul>
 */
class IndexOptimizerAndAlterTest {

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

    // ==================== 索引下推 ====================

    @Test
    void indexPushdownFindsSingleHit() {
        store.addNode("Person", Map.of("name", "Alice", "age", 30));
        store.addNode("Person", Map.of("name", "Bob", "age", 25));
        store.createPropertyIndex("Person", "name");

        List<Map<String, Object>> rows = engine.execute(
                "MATCH (n:Person) WHERE n.name = 'Alice' RETURN n.age AS age");
        assertEquals(1, rows.size());
        assertEquals(30, rows.get(0).get("age"));
    }

    @Test
    void indexPushdownFindsMultipleHits() {
        store.addNode("Person", Map.of("name", "Alice", "city", "Beijing"));
        store.addNode("Person", Map.of("name", "Alice", "city", "Shanghai"));
        store.addNode("Person", Map.of("name", "Bob", "city", "Beijing"));
        store.createPropertyIndex("Person", "name");

        List<Map<String, Object>> rows = engine.execute(
                "MATCH (n:Person) WHERE n.name = 'Alice' RETURN n.city AS city");
        assertEquals(2, rows.size());
        List<String> cities = rows.stream().map(r -> (String) r.get("city")).sorted().toList();
        assertEquals(List.of("Beijing", "Shanghai"), cities);
    }

    @Test
    void indexPushdownReturnsEmptyWhenNoHit() {
        store.addNode("Person", Map.of("name", "Alice"));
        store.createPropertyIndex("Person", "name");

        List<Map<String, Object>> rows = engine.execute(
                "MATCH (n:Person) WHERE n.name = 'Ghost' RETURN n.name AS name");
        assertTrue(rows.isEmpty());
    }

    @Test
    void indexPushdownFallsBackToScanWithoutIndex() {
        // 没建索引的场景仍然能正确返回结果（走全表扫描 + WHERE 过滤）
        store.addNode("Person", Map.of("name", "Alice"));
        store.addNode("Person", Map.of("name", "Bob"));
        List<Map<String, Object>> rows = engine.execute(
                "MATCH (n:Person) WHERE n.name = 'Alice' RETURN n.name AS name");
        assertEquals(1, rows.size());
        assertEquals("Alice", rows.get(0).get("name"));
    }

    // ==================== ALTER TAG ====================

    @Test
    void alterTagAddField() {
        engine.execute("CREATE TAG Person (name STRING NOT NULL)");
        List<Map<String, Object>> result = engine.execute(
                "ALTER TAG Person ADD (age INT, city STRING)");
        assertEquals(1, result.size());
        assertEquals("Person", result.get(0).get("Altered"));
        TagSchema schema = store.getTagSchema("Person");
        assertTrue(schema.hasField("age"));
        assertTrue(schema.hasField("city"));

        // 添加新字段后写入新字段的数据仍然合规
        store.addNode("Person", Map.of("name", "Alice", "age", 30, "city", "Beijing"));
        assertEquals(1, store.getNodeCount());
    }

    @Test
    void alterTagDropField() {
        engine.execute("CREATE TAG Person (name STRING NOT NULL, age INT, city STRING)");
        List<Map<String, Object>> result = engine.execute(
                "ALTER TAG Person DROP (age, city)");
        assertEquals(1, result.size());
        TagSchema schema = store.getTagSchema("Person");
        assertTrue(schema.hasField("name"));
        assertFalse(schema.hasField("age"));
        assertFalse(schema.hasField("city"));
    }

    @Test
    void alterTagAddExistingFieldFails() {
        engine.execute("CREATE TAG Person (name STRING NOT NULL)");
        assertThrows(CypherEngine.CypherException.class,
                () -> engine.execute("ALTER TAG Person ADD (name INT)"));
    }

    @Test
    void alterTagUnknownTagFails() {
        assertThrows(CypherEngine.CypherException.class,
                () -> engine.execute("ALTER TAG Ghost ADD (x INT)"));
    }

    @Test
    void alterTagDropNonExistingFieldNoOp() {
        engine.execute("CREATE TAG Person (name STRING)");
        assertThrows(CypherEngine.CypherException.class,
                () -> engine.execute("ALTER TAG Person DROP (ghost)"));
    }

    // ==================== ALTER EDGE ====================

    @Test
    void alterEdgeAddField() {
        engine.execute("CREATE EDGE KNOWS (since INT)");
        List<Map<String, Object>> result = engine.execute(
                "ALTER EDGE KNOWS ADD (weight DOUBLE)");
        assertEquals(1, result.size());
        EdgeTypeSchema schema = store.getEdgeTypeSchema("KNOWS");
        assertTrue(schema.hasField("since"));
        assertTrue(schema.hasField("weight"));
    }

    @Test
    void alterEdgeDropField() {
        engine.execute("CREATE EDGE KNOWS (since INT, weight DOUBLE)");
        engine.execute("ALTER EDGE KNOWS DROP (weight)");
        EdgeTypeSchema schema = store.getEdgeTypeSchema("KNOWS");
        assertTrue(schema.hasField("since"));
        assertFalse(schema.hasField("weight"));
    }

    // ==================== REBUILD INDEX ====================

    @Test
    void rebuildIndexIsAccepted() {
        engine.execute("CREATE TAG Person (name STRING)");
        engine.execute("CREATE TAG INDEX ON Person.name");
        List<Map<String, Object>> result = engine.execute("REBUILD TAG INDEX Person.name");
        assertEquals(1, result.size());
        assertEquals("ok", result.get(0).get("Rebuilt"));
    }
}
