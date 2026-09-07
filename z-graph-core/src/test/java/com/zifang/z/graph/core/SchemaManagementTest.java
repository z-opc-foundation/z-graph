package com.zifang.z.graph.core;

import com.zifang.z.graph.api.*;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TagSchema / EdgeTypeSchema 行为校验。验证 NebulaGraph 风格 schema 的写前校验、
 * 现有数据兼容性、删除保护以及快照复制一致性。
 */
class SchemaManagementTest {

    private InMemoryGraphStore store;

    @BeforeEach
    void setUp() { store = new InMemoryGraphStore(); }

    @AfterEach
    void tearDown() { store = null; }

    @Test
    void createTagAcceptsMatchingProperties() {
        store.createTag(new TagSchema("Person", List.of(
                new TagSchema.Field("name", TagSchema.DataType.STRING, false),
                new TagSchema.Field("age", TagSchema.DataType.INT))));
        Node n = store.addNode("Person", Map.of("name", "Alice", "age", 30));
        assertEquals("Alice", n.get("name"));
    }

    @Test
    void createTagRejectsUnknownField() {
        store.createTag(new TagSchema("Person", List.of(
                new TagSchema.Field("name", TagSchema.DataType.STRING, false))));
        assertThrows(TagSchema.SchemaViolationException.class,
                () -> store.addNode("Person", Map.of("name", "Alice", "extra", 1)));
    }

    @Test
    void createTagRejectsMissingNotNullField() {
        store.createTag(new TagSchema("Person", List.of(
                new TagSchema.Field("name", TagSchema.DataType.STRING, false))));
        assertThrows(TagSchema.SchemaViolationException.class,
                () -> store.addNode("Person", Map.of("age", 30)));
    }

    @Test
    void createTagRejectsWrongType() {
        store.createTag(new TagSchema("Person", List.of(
                new TagSchema.Field("age", TagSchema.DataType.INT))));
        assertThrows(TagSchema.SchemaViolationException.class,
                () -> store.addNode("Person", Map.of("age", "thirty")));
    }

    @Test
    void createTagValidatesExistingNodesOnReplace() {
        store.addNode("Person", Map.of("name", "Alice", "age", 30));
        // 同标签重新声明合法 schema 应成功
        store.createTag(new TagSchema("Person", List.of(
                new TagSchema.Field("name", TagSchema.DataType.STRING, false),
                new TagSchema.Field("age", TagSchema.DataType.INT))));
        // 替换为更严格的 schema 后现有数据会被校验
        assertThrows(TagSchema.SchemaViolationException.class,
                () -> store.createTag(new TagSchema("Person", List.of(
                        new TagSchema.Field("name", TagSchema.DataType.INT)))));
    }

    @Test
    void dropTagFailsWhenNodesExist() {
        store.createTag(new TagSchema("Person", List.of(
                new TagSchema.Field("name", TagSchema.DataType.STRING))));
        store.addNode("Person", Map.of("name", "Alice"));
        assertThrows(IllegalStateException.class, () -> store.dropTag("Person"));
        assertNotNull(store.getTagSchema("Person"));
    }

    @Test
    void dropTagSucceedsWhenEmpty() {
        store.createTag(new TagSchema("Person", List.of(
                new TagSchema.Field("name", TagSchema.DataType.STRING))));
        assertTrue(store.dropTag("Person"));
        assertNull(store.getTagSchema("Person"));
    }

    @Test
    void edgeTypeSchemaValidatesOnAddEdge() {
        store.addNode("Person", Map.of("name", "Alice"));
        store.addNode("Person", Map.of("name", "Bob"));
        store.createEdgeType(new EdgeTypeSchema("KNOWS", List.of(
                new TagSchema.Field("since", TagSchema.DataType.INT, false))));
        long alice = store.getNodeIdsByLabel("Person").get(0);
        long bob = store.getNodeIdsByLabel("Person").get(1);
        assertThrows(TagSchema.SchemaViolationException.class,
                () -> store.addEdge("KNOWS", alice, bob, Map.of()));
        Edge ok = store.addEdge("KNOWS", alice, bob, Map.of("since", 2020));
        assertEquals(2020, ok.get("since"));
    }

    @Test
    void listTagsAndEdgeTypesIsSorted() {
        store.createTag(new TagSchema("Zoo", List.of()));
        store.createTag(new TagSchema("Animal", List.of()));
        store.createEdgeType(new EdgeTypeSchema("OWNS", List.of()));
        store.createEdgeType(new EdgeTypeSchema("LIKES", List.of()));
        assertEquals(List.of("Animal", "Zoo"), store.listTags());
        assertEquals(List.of("LIKES", "OWNS"), store.listEdgeTypes());
    }

    @Test
    void snapshotCopyPreservesSchemas() {
        store.createTag(new TagSchema("Person", List.of(
                new TagSchema.Field("name", TagSchema.DataType.STRING, false))));
        store.createEdgeType(new EdgeTypeSchema("KNOWS", List.of(
                new TagSchema.Field("since", TagSchema.DataType.INT))));
        store.addNode("Person", Map.of("name", "Alice"));

        InMemoryGraphStore copy = store.copy();
        assertNotNull(copy.getTagSchema("Person"));
        assertNotNull(copy.getEdgeTypeSchema("KNOWS"));
        assertEquals(1, copy.getNodeCount());
    }

    @Test
    void freeFormLabelStillAcceptedWithoutSchema() {
        // 未声明 schema 的标签走自由属性路径
        Node n = store.addNode("FreeForm", Map.of("anything", 1));
        assertEquals(1, n.get("anything"));
    }

    @Test
    void statsIncludeSchemaCounts() {
        store.createTag(new TagSchema("Person", List.of()));
        store.createEdgeType(new EdgeTypeSchema("KNOWS", List.of()));
        java.util.Map<String, Object> stats = store.getStats();
        assertEquals(1, stats.get("tagSchemaCount"));
        assertEquals(1, stats.get("edgeTypeSchemaCount"));
    }
}
