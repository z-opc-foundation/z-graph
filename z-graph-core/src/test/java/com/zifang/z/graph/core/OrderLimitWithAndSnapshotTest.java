package com.zifang.z.graph.core;

import com.zifang.z.graph.api.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖 ORDER BY / LIMIT / SKIP、WITH、多语句和 snapshot 导入/导出。
 */
class OrderLimitWithAndSnapshotTest {

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

    // ==================== ORDER BY / LIMIT / SKIP ====================

    @Test
    void orderByAscending() {
        store.addNode("Person", Map.of("name", "Bob", "age", 25));
        store.addNode("Person", Map.of("name", "Alice", "age", 30));
        store.addNode("Person", Map.of("name", "Charlie", "age", 20));

        List<Map<String, Object>> rows = engine.execute(
                "MATCH (n:Person) RETURN n.name AS name ORDER BY n.age ASC");
        assertEquals(List.of("Charlie", "Bob", "Alice"),
                rows.stream().map(r -> r.get("name")).toList());
    }

    @Test
    void orderByDescending() {
        store.addNode("Person", Map.of("name", "Bob", "age", 25));
        store.addNode("Person", Map.of("name", "Alice", "age", 30));

        List<Map<String, Object>> rows = engine.execute(
                "MATCH (n:Person) RETURN n.name AS name ORDER BY n.age DESC");
        assertEquals(List.of("Alice", "Bob"),
                rows.stream().map(r -> r.get("name")).toList());
    }

    @Test
    void limitAndSkipCombine() {
        for (int i = 0; i < 5; i++) {
            store.addNode("Person", Map.of("name", "T" + i, "age", i));
        }
        List<Map<String, Object>> rows = engine.execute(
                "MATCH (n:Person) RETURN n.name AS name ORDER BY n.age ASC SKIP 1 LIMIT 2");
        assertEquals(List.of("T1", "T2"),
                rows.stream().map(r -> r.get("name")).toList());
    }

    // ==================== WITH ====================

    @Test
    void withRenamesVariables() {
        store.addNode("Person", Map.of("name", "Alice", "age", 30));
        store.addNode("Person", Map.of("name", "Bob", "age", 25));

        List<Map<String, Object>> rows = engine.execute(
                "MATCH (n:Person) WITH n.name AS name, n.age AS age RETURN name, age ORDER BY age ASC");
        assertEquals(List.of("Bob", "Alice"), rows.stream().map(r -> r.get("name")).toList());
        assertEquals(List.of(25, 30), rows.stream().map(r -> r.get("age")).toList());
    }

    // ==================== Multi-statement ====================

    @Test
    void multiStatementsExecuteInOrder() {
        engine.execute("CREATE TAG Person (name STRING NOT NULL)");
        engine.execute("CREATE TAG INDEX ON Person.name");
        store.addNode("Person", Map.of("name", "Alice"));
        store.addNode("Person", Map.of("name", "Bob"));

        // 三条语句一次执行：DDL + MATCH + SHOW
        List<Map<String, Object>> rows = engine.execute(
                "CREATE EDGE KNOWS (since INT);"
                        + "MATCH (n:Person) WHERE n.name = 'Alice' RETURN n.name AS name;"
                        + "SHOW INDEXES");
        // CREATE EDGE 一行 + MATCH 一行 + SHOW INDEXES 一行
        assertEquals(3, rows.size());
        assertEquals("Alice", rows.get(1).get("name"));
    }

    @Test
    void semicolonInStringLiteralIsRespected() {
        store.addNode("Person", Map.of("name", "Al;ice"));
        List<Map<String, Object>> rows = engine.execute(
                "MATCH (n:Person) WHERE n.name = 'Al;ice' RETURN n.name AS name");
        assertEquals(1, rows.size());
        assertEquals("Al;ice", rows.get(0).get("name"));
    }

    // ==================== Snapshot export / import ====================

    @Test
    void exportAndImportSnapshotRoundTrip(@TempDir Path tmp) throws IOException {
        Path repoDir = tmp.resolve("repo");
        Path snapFile = tmp.resolve("snapshot.bin");

        GraphVersionStore src = new GraphVersionStore(repoDir);
        GraphWriteTransaction tx = src.beginWrite("main");
        tx.addNode("Person", Map.of("name", "Alice", "age", 30));
        tx.addNode("Person", Map.of("name", "Bob", "age", 25));
        GraphCommit srcCommit = tx.commit("alice", "init");
        // 备份到这个 snapshot 文件
        src.exportSnapshot(srcCommit.getId(), snapFile);
        assertTrue(Files.exists(snapFile));

        // 在另一个仓库导入
        Path otherDir = tmp.resolve("other");
        GraphVersionStore dst = new GraphVersionStore(otherDir);
        GraphCommit imported = dst.importSnapshot(snapFile, "main", "restore", "from snapshot");
        assertNotNull(imported);

        GraphCheckout view = dst.checkout(imported.getId());
        assertEquals(2, view.getNodeCount());
        List<Map<String, Object>> names = view.query(
                "MATCH (n:Person) RETURN n.name AS name ORDER BY n.name ASC");
        assertEquals(List.of("Alice", "Bob"),
                names.stream().map(r -> r.get("name")).toList());
    }

    @Test
    void importSnapshotPreservesSchemas(@TempDir Path tmp) throws IOException {
        Path repoDir = tmp.resolve("repo");
        Path snapFile = tmp.resolve("snapshot.bin");

        GraphVersionStore src = new GraphVersionStore(repoDir);
        GraphWriteTransaction tx = src.beginWrite("main");
        tx.createTag(new TagSchema("Person", List.of(
                new TagSchema.Field("name", TagSchema.DataType.STRING, false))));
        tx.addNode("Person", Map.of("name", "Alice"));
        GraphCommit c = tx.commit("alice", "init");
        src.exportSnapshot(c.getId(), snapFile);

        GraphVersionStore dst = new GraphVersionStore(tmp.resolve("dst"));
        GraphCommit imported = dst.importSnapshot(snapFile, "main", "restore", "ok");
        GraphCheckout view = dst.checkout(imported.getId());
        assertNotNull(view.getTagSchema("Person"));
        assertTrue(view.getTagSchema("Person").hasField("name"));
    }

    @Test
    void exportUnknownCommitFails(@TempDir Path tmp) throws IOException {
        GraphVersionStore repo = new GraphVersionStore(tmp);
        assertThrows(IllegalArgumentException.class,
                () -> repo.exportSnapshot("missing", tmp.resolve("snap.bin")));
    }
}
