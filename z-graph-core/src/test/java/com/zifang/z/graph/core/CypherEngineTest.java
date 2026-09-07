package com.zifang.z.graph.core;

import com.zifang.z.graph.api.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CypherEngine 单元测试 — 覆盖 CREATE/MATCH/WHERE/SET/DELETE/RETURN/UNWIND。
 */
class CypherEngineTest {

    private InMemoryGraphStore store;
    private CypherEngine engine;

    @BeforeEach
    void setUp() {
        store = new InMemoryGraphStore();
        engine = new CypherEngine(store);
    }

    // ==================== RETURN literal（兼容 T1）====================

    @Test
    void returnInteger() {
        List<Map<String, Object>> rows = engine.execute("RETURN 42 AS n");
        assertEquals(1, rows.size());
        assertEquals(42, rows.get(0).get("n"));
    }

    @Test
    void returnString() {
        List<Map<String, Object>> rows = engine.execute("RETURN 'hello' AS msg");
        assertEquals("hello", rows.get(0).get("msg"));
    }

    @Test
    void returnMultiple() {
        List<Map<String, Object>> rows = engine.execute("RETURN 1 AS a, 'x' AS b, true AS c");
        assertEquals(1, rows.get(0).get("a"));
        assertEquals("x", rows.get(0).get("b"));
        assertEquals(true, rows.get(0).get("c"));
    }

    // ==================== CREATE ====================

    @Test
    void createNode() {
        engine.execute("CREATE (n:Person {name: 'Alice', age: 30})");
        assertEquals(1, store.getNodeCount());
        List<Long> ids = store.getNodeIdsByLabel("Person");
        assertEquals(1, ids.size());
        Node alice = store.getNode(ids.get(0));
        assertEquals("Alice", alice.get("name"));
        assertEquals(30, alice.get("age"));
    }

    @Test
    void createMultipleNodesAndEdge() {
        engine.execute("CREATE (a:Person {name: 'Alice'}), (b:Person {name: 'Bob'}), (a)-[:FRIEND]->(b)");
        assertEquals(2, store.getNodeCount());
        assertEquals(1, store.getEdgeCount());
    }

    // ==================== MATCH + WHERE + RETURN ====================

    @Test
    void matchByLabel() {
        engine.execute("CREATE (a:Person {name: 'Alice', age: 30})");
        engine.execute("CREATE (b:Person {name: 'Bob', age: 25})");
        List<Map<String, Object>> rows = engine.execute("MATCH (n:Person) RETURN n.name AS name");
        assertEquals(2, rows.size());
    }

    @Test
    void matchWithWhere() {
        engine.execute("CREATE (a:Person {name: 'Alice', age: 30})");
        engine.execute("CREATE (b:Person {name: 'Bob', age: 25})");
        List<Map<String, Object>> rows = engine.execute(
                "MATCH (n:Person) WHERE n.age > 25 RETURN n.name AS name");
        assertEquals(1, rows.size());
        assertEquals("Alice", rows.get(0).get("name"));
    }

    @Test
    void matchWithWhereEqual() {
        engine.execute("CREATE (a:Person {name: 'Alice', age: 30})");
        engine.execute("CREATE (b:Person {name: 'Bob', age: 30})");
        List<Map<String, Object>> rows = engine.execute(
                "MATCH (n:Person) WHERE n.age = 30 RETURN n.name AS name");
        assertEquals(2, rows.size());
    }

    // ==================== MATCH + CREATE (关系查询) ====================

    @Test
    void matchEdgePattern() {
        engine.execute("CREATE (a:Person {name: 'Alice'}), (b:Person {name: 'Bob'}), (a)-[:FRIEND]->(b)");
        List<Map<String, Object>> rows = engine.execute(
                "MATCH (a)-[r:FRIEND]->(b) RETURN a.name AS from, b.name AS to");
        assertEquals(1, rows.size());
        assertEquals("Alice", rows.get(0).get("from"));
        assertEquals("Bob", rows.get(0).get("to"));
    }

    // ==================== MATCH + SET ====================

    @Test
    void matchAndSet() {
        engine.execute("CREATE (a:Person {name: 'Alice', age: 30})");
        engine.execute("MATCH (n:Person) WHERE n.name = 'Alice' SET n.age = 31");
        Node alice = store.getNode(store.getNodeIdsByLabel("Person").get(0));
        assertEquals(31, alice.get("age"));
    }

    // ==================== MATCH + DELETE ====================

    @Test
    void matchAndDelete() {
        engine.execute("CREATE (a:Person {name: 'Alice'})");
        engine.execute("CREATE (b:Person {name: 'Bob'})");
        assertEquals(2, store.getNodeCount());
        engine.execute("MATCH (n:Person) WHERE n.name = 'Alice' DELETE n");
        assertEquals(1, store.getNodeCount());
    }

    // ==================== UNWIND ====================

    @Test
    void unwind() {
        List<Map<String, Object>> rows = engine.execute("UNWIND [1, 2, 3] AS x RETURN x");
        assertEquals(3, rows.size());
        assertEquals(1, rows.get(0).get("x"));
        assertEquals(2, rows.get(1).get("x"));
        assertEquals(3, rows.get(2).get("x"));
    }

    // ==================== 异常 ====================

    @Test
    void parametersCanBeUsedInWhereClause() {
        engine.execute("CREATE (a:Person {name: 'Alice', age: 30})");
        engine.execute("CREATE (b:Person {name: 'Bob', age: 20})");
        List<Map<String, Object>> rows = engine.execute(
                "MATCH (n:Person) WHERE n.age > $minimum RETURN n.name AS name",
                Map.of("minimum", 25));
        assertEquals(1, rows.size());
        assertEquals("Alice", rows.get(0).get("name"));
    }

    @Test
    void unsupportedStatementThrows() {
        assertThrows(CypherEngine.CypherException.class,
                () -> engine.execute("MERGE (n:Person {name: 'Alice'})"));
    }
}
