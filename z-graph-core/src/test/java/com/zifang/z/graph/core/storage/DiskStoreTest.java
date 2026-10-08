package com.zifang.z.graph.core.storage;

import com.zifang.z.graph.api.Edge;
import com.zifang.z.graph.api.Node;
import com.zifang.z.graph.core.GraphDelta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 磁盘内核测试：payload 往返、reopen 幂等、版本链按 Visibility 解析、
 * postings 墓碑 latest-wins、remap 窗口跨界、以及 MVCC 可见性矩阵
 * （main 分叉后 A/B 互不可见）。
 */
class DiskStoreTest {

    /** 闭包集合实现的 Visibility。 */
    private static Visibility visibleAt(final long... commitSeqs) {
        final Set<Long> closure = new HashSet<Long>();
        for (long seq : commitSeqs) {
            closure.add(seq);
        }
        return new Visibility() {
            @Override
            public boolean isVisible(long commitSeq) {
                return closure.contains(commitSeq);
            }
        };
    }

    private static GraphDelta deltaOf(Object... changes) {
        GraphDelta delta = new GraphDelta();
        for (Object change : changes) {
            if (change instanceof Node) {
                delta.putNode((Node) change);
            } else if (change instanceof Edge) {
                delta.putEdge((Edge) change);
            } else if (change instanceof Long) {
                delta.deleteNode((Long) change);
            } else {
                throw new IllegalArgumentException("Unsupported change: " + change);
            }
        }
        return delta;
    }

    private static Node node(long id, String label, Object... keyValues) {
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            props.put((String) keyValues[i], keyValues[i + 1]);
        }
        return new Node(id, java.util.Collections.singletonList(label), props);
    }

    private static long append(StorageEngine engine, GraphDelta delta) throws IOException {
        long seq = engine.allocateCommitSeq();
        engine.applyDelta(seq, new byte[16], delta);
        engine.persistAndForce();
        return seq;
    }

    @Test
    void nodeAndEdgePayloadRoundTrip(@TempDir Path dir) throws IOException {
        StorageEngine engine = StorageEngine.create(dir.resolve("store"));
        engine.allocateNodeId();
        engine.allocateEdgeId();
        long seq = append(engine, deltaOf(
                node(0L, "Person", "name", "Alice", "age", 30),
                new Edge(0L, "KNOWS", 0L, 1L, single("since", 2020L))));
        // 第二个节点补上，边才有终点
        append(engine, deltaOf(node(1L, "Person", "name", "Bob")));

        RefViewGraphStore view = new RefViewGraphStore(engine, visibleAt(seq));
        Node alice = view.getNode(0L);
        assertNotNull(alice);
        assertEquals("Alice", alice.get("name"));
        assertEquals(30, alice.get("age"));
        assertTrue(alice.hasLabel("Person"));

        Edge knows = view.getEdge(0L);
        assertNotNull(knows);
        assertEquals("KNOWS", knows.getType());
        assertEquals(0L, knows.getStartNodeId());
        assertEquals(1L, knows.getEndNodeId());
        assertEquals(2020L, knows.get("since"));
        engine.close();
    }

    @Test
    void reopenIsIdempotent(@TempDir Path dir) throws IOException {
        Path store = dir.resolve("store");
        StorageEngine engine = StorageEngine.create(store);
        engine.allocateNodeId();
        long seq1 = append(engine, deltaOf(node(0L, "Person", "age", 20)));
        long seq2 = append(engine, deltaOf(node(0L, "Person", "age", 30)));
        Map<String, Object> statsBefore = engine.stats();
        engine.close();

        StorageEngine reopened = StorageEngine.open(store);
        assertEquals(20, new RefViewGraphStore(reopened, visibleAt(seq1)).getNode(0L).get("age"));
        assertEquals(30, new RefViewGraphStore(reopened, visibleAt(seq2)).getNode(0L).get("age"));
        assertEquals(statsBefore.get("nextNodeId"), reopened.stats().get("nextNodeId"));
        assertEquals(statsBefore.get("versionRecords"), reopened.stats().get("versionRecords"));
        // 重开后还能继续追加，id 不撞号
        long seq3 = append(reopened, deltaOf(node(0L, "Person", "age", 40)));
        assertTrue(seq3 > seq2);
        assertEquals(40, new RefViewGraphStore(reopened, visibleAt(seq3)).getNode(0L).get("age"));
        reopened.close();
    }

    @Test
    void versionChainResolvesByVisibility(@TempDir Path dir) throws IOException {
        StorageEngine engine = StorageEngine.create(dir.resolve("store"));
        engine.allocateNodeId();
        append(engine, deltaOf(node(0L, "Person", "age", 20)));
        long seq2 = append(engine, deltaOf(node(0L, "Person", "age", 30)));
        long seq3 = append(engine, deltaOf(node(0L, "Person", "age", 40)));

        assertEquals(20, new RefViewGraphStore(engine, visibleAt(1)).getNode(0L).get("age"));
        assertEquals(30, new RefViewGraphStore(engine, visibleAt(1, seq2)).getNode(0L).get("age"));
        assertEquals(40, new RefViewGraphStore(engine, visibleAt(1, seq2, seq3)).getNode(0L).get("age"));
        // 跳过中间版本：可见性闭包里没有 seq2 时直接解析到 seq3
        assertEquals(40, new RefViewGraphStore(engine, visibleAt(1, seq3)).getNode(0L).get("age"));
        // 全链不可见 → 实体不存在
        assertNull(new RefViewGraphStore(engine, visibleAt(99)).getNode(0L));
        engine.close();
    }

    @Test
    void deletionIsVisiblePerRef(@TempDir Path dir) throws IOException {
        StorageEngine engine = StorageEngine.create(dir.resolve("store"));
        engine.allocateNodeId();
        append(engine, deltaOf(node(0L, "Person", "age", 20)));
        long deleteSeq = append(engine, deltaOf(0L));

        Visibility beforeDelete = visibleAt(1);
        Visibility afterDelete = visibleAt(1, deleteSeq);
        assertNotNull(new RefViewGraphStore(engine, beforeDelete).getNode(0L));
        assertNull(new RefViewGraphStore(engine, afterDelete).getNode(0L));
        assertEquals(0, new RefViewGraphStore(engine, afterDelete).getNodeCount());
        assertEquals(0, new RefViewGraphStore(engine, afterDelete).getNodeIdsByLabel("Person").size());
        engine.close();
    }

    @Test
    void labelPostingsTombstoneOnLabelMove(@TempDir Path dir) throws IOException {
        StorageEngine engine = StorageEngine.create(dir.resolve("store"));
        engine.allocateNodeId();
        append(engine, deltaOf(new Node(0L, java.util.Arrays.asList("A", "B"),
                new LinkedHashMap<String, Object>())));
        long moveSeq = append(engine, deltaOf(new Node(0L, java.util.Arrays.asList("A", "C"),
                new LinkedHashMap<String, Object>())));

        Visibility beforeMove = visibleAt(1);
        Visibility afterMove = visibleAt(1, moveSeq);
        RefViewGraphStore before = new RefViewGraphStore(engine, beforeMove);
        RefViewGraphStore after = new RefViewGraphStore(engine, afterMove);

        assertEquals(java.util.Collections.singletonList(0L), before.getNodeIdsByLabel("B"));
        assertTrue(after.getNodeIdsByLabel("B").isEmpty());
        assertEquals(java.util.Collections.singletonList(0L), after.getNodeIdsByLabel("C"));
        assertEquals(java.util.Collections.singletonList(0L), after.getNodeIdsByLabel("A"));
        engine.close();
    }

    @Test
    void adjacencyTombstoneOnEdgeRewire(@TempDir Path dir) throws IOException {
        StorageEngine engine = StorageEngine.create(dir.resolve("store"));
        engine.reserveNodeId(2);
        engine.allocateEdgeId();
        append(engine, deltaOf(
                node(0L, "P"), node(1L, "P"), node(2L, "P"),
                new Edge(0L, "KNOWS", 0L, 1L, new LinkedHashMap<String, Object>())));
        long rewireSeq = append(engine, deltaOf(
                new Edge(0L, "KNOWS", 0L, 2L, single("v", 2))));

        RefViewGraphStore before = new RefViewGraphStore(engine, visibleAt(1));
        RefViewGraphStore after = new RefViewGraphStore(engine, visibleAt(1, rewireSeq));

        assertEquals(1L, before.getOutEdges(0L).get(0).getEndNodeId());
        assertEquals(1, before.getInEdges(1L).size());
        assertEquals(2L, after.getOutEdges(0L).get(0).getEndNodeId());
        assertTrue(after.getInEdges(1L).isEmpty());
        assertEquals(1, after.getInEdges(2L).size());
        assertEquals("KNOWS", after.getEdgesByType("KNOWS").get(0).getType());
        engine.close();
    }

    @Test
    void visibilityMatrixMainAndBranchDiverge(@TempDir Path dir) throws IOException {
        // main: seq1 建 P0；seq2 改 P0 —— 分支 B 从 seq1 切出后 seq3 加 P1。
        // 不变量：main@seq2 看不到 P1；branch@seq3 看不到 seq2 的修改。
        StorageEngine engine = StorageEngine.create(dir.resolve("store"));
        engine.reserveNodeId(1);
        append(engine, deltaOf(node(0L, "Person", "age", 20)));
        long mainSeq2 = append(engine, deltaOf(node(0L, "Person", "age", 30)));
        long branchSeq3 = append(engine, deltaOf(node(1L, "Person", "age", 99)));

        RefViewGraphStore mainView = new RefViewGraphStore(engine, visibleAt(1, mainSeq2));
        RefViewGraphStore branchView = new RefViewGraphStore(engine, visibleAt(1, branchSeq3));

        assertEquals(30, mainView.getNode(0L).get("age"));
        assertNull(mainView.getNode(1L));
        assertEquals(1, mainView.getNodeCount());

        assertEquals(20, branchView.getNode(0L).get("age"));
        assertEquals(99, branchView.getNode(1L).get("age"));
        assertEquals(2, branchView.getNodeCount());

        // merge 视角：双方闭包的并集全部可见
        RefViewGraphStore mergedView = new RefViewGraphStore(engine, visibleAt(1, mainSeq2, branchSeq3));
        assertEquals(30, mergedView.getNode(0L).get("age"));
        assertEquals(99, mergedView.getNode(1L).get("age"));
        engine.close();
    }

    @Test
    void remapWindowsDoNotCorruptRecords(@TempDir Path dir) throws IOException {
        StorageEngine engine = StorageEngine.create(dir.resolve("store"));
        engine.allocateNodeId();
        // 1KB payload × 1200 版本 = 1.2MB，跨 AppendSegment 的 1MB 首窗口；
        // versions.idx 64B × 1200 也跨 FixedRecordFile 的 4096B 首窗口。
        char[] big = new char[1024];
        Arrays.fill(big, 'x');
        String bigString = new String(big);
        long headSeq = 0;
        for (int i = 0; i < 1200; i++) {
            headSeq = append(engine, deltaOf(node(0L, "Person", "blob", bigString + i)));
        }
        RefViewGraphStore head = new RefViewGraphStore(engine, visibleAt(headSeq));
        assertEquals(bigString + "1199", head.getNode(0L).get("blob"));
        assertEquals(1, head.getNodeCount());

        engine.close();
        StorageEngine reopened = StorageEngine.open(dir.resolve("store"));
        assertEquals(bigString + "1199",
                new RefViewGraphStore(reopened, visibleAt(headSeq)).getNode(0L).get("blob"));
        // 中段抽验
        long midSeq = headSeq - 600;
        assertEquals(bigString + "599",
                new RefViewGraphStore(reopened, visibleAt(1, midSeq)).getNode(0L).get("blob"));
        reopened.close();
    }

    @Test
    void schemaAndIndexDefinitionsResolvePerRef(@TempDir Path dir) throws IOException {
        StorageEngine engine = StorageEngine.create(dir.resolve("store"));
        engine.allocateNodeId();
        GraphDelta withSchema = deltaOf(node(0L, "Person", "age", 20));
        withSchema.putTag(new com.zifang.z.graph.api.TagSchema("Person", java.util.Collections.singletonList(
                new com.zifang.z.graph.api.TagSchema.Field("age", com.zifang.z.graph.api.TagSchema.DataType.INT, true))));
        withSchema.putIndex(new GraphDelta.IndexKey("Person", "age"));
        long schemaSeq = append(engine, withSchema);
        long dropSeq = append(engine, deltaOf(node(0L, "Person", "age", 21))
                .deleteIndex(new GraphDelta.IndexKey("Person", "age")));

        RefViewGraphStore before = new RefViewGraphStore(engine, visibleAt(1, schemaSeq));
        RefViewGraphStore after = new RefViewGraphStore(engine, visibleAt(1, schemaSeq, dropSeq));
        assertTrue(before.hasPropertyIndex("Person", "age"));
        assertFalse(after.hasPropertyIndex("Person", "age"));
        assertEquals("Person", before.listTags().get(0));
        assertNotNull(before.getTagSchema("Person"));
        assertTrue(before.findNodesByProperty("Person", "age", 20).contains(0L));
        assertEquals(java.util.Collections.singletonList(0L),
                after.findNodesByProperty("Person", "age", 21));
        engine.close();
    }

    @Test
    void strictMapRejectsLossyValueTypes(@TempDir Path dir) throws IOException {
        StorageEngine engine = StorageEngine.create(dir.resolve("store"));
        engine.allocateNodeId();
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("when", new java.util.Date(42L));
        GraphDelta delta = deltaOf(new Node(0L, java.util.Collections.singletonList("Person"), props));
        try {
            append(engine, delta);
            throw new AssertionError("lossy toString fallback must fail fast on v5 layout");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("lossy"));
        }
        engine.close();
    }

    private static Map<String, Object> single(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(key, value);
        return map;
    }
}
