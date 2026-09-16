package com.zifang.z.graph.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CypherExecutor 单元测试 — POC 阶段只测 RETURN literal 子集。
 */
class CypherExecutorTest {

    private final CypherExecutor executor = new CypherExecutor();

    @Test
    void testReturnIntegerWithAlias() {
        List<Map<String, Object>> rows = executor.execute("RETURN 1 AS n");
        assertEquals(1, rows.size());
        assertEquals(1L, rows.get(0).get("n"));
    }

    @Test
    void testReturnStringWithAlias() {
        List<Map<String, Object>> rows = executor.execute("RETURN \"hello\" AS msg");
        assertEquals("hello", rows.get(0).get("msg"));
    }

    @Test
    void testReturnMultipleColumns() {
        List<Map<String, Object>> rows = executor.execute("RETURN 1 AS x, \"y\" AS y, true AS z");
        Map<String, Object> row = rows.get(0);
        assertEquals(1L, row.get("x"));
        assertEquals("y", row.get("y"));
        assertEquals(Boolean.TRUE, row.get("z"));
    }

    @Test
    void testReturnNegativeInteger() {
        List<Map<String, Object>> rows = executor.execute("RETURN -42 AS n");
        assertEquals(-42L, rows.get(0).get("n"));
    }

    @Test
    void testReturnWithoutAlias() {
        List<Map<String, Object>> rows = executor.execute("RETURN 1, 2, 3");
        Map<String, Object> row = rows.get(0);
        // 默认列名是索引: "0", "1", "2"
        assertEquals(1L, row.get("0"));
        assertEquals(2L, row.get("1"));
        assertEquals(3L, row.get("2"));
    }

    @Test
    void testReturnCaseInsensitive() {
        List<Map<String, Object>> rows = executor.execute("return 1 as n");
        assertEquals(1L, rows.get(0).get("n"));
    }

    @Test
    void testReturnWithLeadingTrailingSpaces() {
        List<Map<String, Object>> rows = executor.execute("  RETURN  1  AS  n  ");
        assertEquals(1L, rows.get(0).get("n"));
    }

    @Test
    void testInvalidCypherThrows() {
        assertThrows(IllegalArgumentException.class, () -> executor.execute("MATCH (n) RETURN n"));
    }

    @Test
    void testEmptyReturnThrows() {
        assertThrows(IllegalArgumentException.class, () -> executor.execute("RETURN"));
    }

    @Test
    void testReturnNullLiteral() {
        List<Map<String, Object>> rows = executor.execute("RETURN null AS x");
        assertNull(rows.get(0).get("x"));
    }

    @Test
    void testReturnBooleanFalse() {
        List<Map<String, Object>> rows = executor.execute("RETURN false AS flag");
        assertEquals(Boolean.FALSE, rows.get(0).get("flag"));
    }
}