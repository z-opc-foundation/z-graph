package com.zifang.z.graph.core;

import com.zifang.z.graph.api.Colls;
import com.zifang.z.graph.api.Edge;
import com.zifang.z.graph.api.GraphCommit;
import com.zifang.z.graph.api.GraphMergeResult;
import com.zifang.z.graph.api.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-20261012-027 演练：<b>GC 可达性</b>与<b>快照导出</b>。
 *
 * <p><b>只在临时 data-dir 上跑</b>（JUnit {@code @TempDir}）—— 生产目录首轮只观察，
 * 本测试不会、也不允许指向生产路径。</p>
 *
 * <p>断言全部按 {@link GraphVersionStore#garbageCollect(String...)} 的**真实行为**写，
 * 不是按想象写：可达集 = keep 分支 head 的「第一父链 ∪ 全父祖先闭包」，
 * 不在可达集里的 commit 被回收，指向不可达 commit 的分支 ref 被剪掉。
 * 口径文档：{@code _doc/002_ops/01_GC可达性与回收规则.md}。</p>
 */
class GraphMaintenanceRehearsalTest {

    /** 演练产物目录：默认落在 target/rehearsal，可用 -Dzgraph.rehearsal.dir=... 改。 */
    static Path rehearsalDir() {
        String configured = System.getProperty("zgraph.rehearsal.dir");
        Path dir = configured == null || configured.trim().isEmpty()
                ? Paths.get("target", "rehearsal")
                : Paths.get(configured.trim());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create rehearsal dir " + dir, e);
        }
        return dir;
    }

    @Test
    void gcKeepsReachableCommitsAndPrunesBranchOutsideKeepSet(@TempDir Path dataDir) {
        GraphVersionStore store = new GraphVersionStore(dataDir);
        GraphMaintenanceService maintenance = new GraphMaintenanceService(store);

        GraphWriteTransaction w1 = store.beginWrite("main");
        w1.addNode("Person", Colls.mapOf("name", "Alice"));
        GraphCommit alice = w1.commit("alice", "add Alice");

        GraphWriteTransaction w2 = store.beginWrite("main");
        w2.addNode("Person", Colls.mapOf("name", "Bob"));
        GraphCommit bob = w2.commit("bob", "add Bob");

        store.createBranch("feature", alice.getId());
        GraphWriteTransaction w3 = store.beginWrite("feature");
        w3.addNode("Person", Colls.mapOf("name", "Carol"));
        GraphCommit carol = w3.commit("carol", "add Carol");

        GraphWriteTransaction w4 = store.beginWrite("main");
        w4.addNode("Person", Colls.mapOf("name", "Dave"));
        GraphCommit dave = w4.commit("dave", "add Dave");

        int commitsBefore = store.listCommits().size();
        assertEquals(5, commitsBefore, "初始 + alice + bob + carol + dave");

        // 只保留 main ⇒ feature 的历史不在 main 的祖先闭包里 ⇒ 回收 1 个 commit、分支被剪
        GraphMaintenanceService.GcOutcome outcome = maintenance.collectGarbage("main");

        assertEquals(1, outcome.getCollected(), "只应回收 feature 上那一个不可达 commit");
        assertEquals(commitsBefore - 1, outcome.getCommitsAfter());
        assertEquals(new java.util.TreeSet<>(Arrays.asList("main", "feature")),
                new java.util.TreeSet<>(outcome.getBranchesBefore()));
        assertEquals(Collections.singleton("main"), new java.util.TreeSet<>(outcome.getBranchesAfter()),
                "指向不可达 commit 的分支 ref 会被剪掉");

        // 被回收的 commit 真的没了
        assertThrows(GraphVersionStore.UnknownReferenceException.class,
                () -> store.getCommit(carol.getId()));
        // 可达 commit 仍然可按 commit 打开（历史视图不丢）
        assertEquals(1, store.checkout(alice.getId()).getNodeCount());
        assertEquals(2, store.checkout(bob.getId()).getNodeCount());
        assertEquals(3, store.checkout(dave.getId()).getNodeCount());

        // 计数可观测（失败可见的前提）
        Map<String, Object> stats = maintenance.stats();
        assertEquals(1L, stats.get("gcRuns"));
        assertEquals(1L, stats.get("gcCollectedCommits"));
        assertEquals(0L, stats.get("gcFailures"));
        assertTrue((Long) stats.get("lastGcAtMillis") > 0L);
    }

    @Test
    void gcWithoutKeepKeepsEveryBranch(@TempDir Path dataDir) {
        GraphVersionStore store = new GraphVersionStore(dataDir);
        GraphMaintenanceService maintenance = new GraphMaintenanceService(store);

        GraphWriteTransaction w1 = store.beginWrite("main");
        w1.addNode("Person", Colls.mapOf("name", "Alice"));
        GraphCommit alice = w1.commit("alice", "add Alice");

        store.createBranch("feature", alice.getId());
        GraphWriteTransaction w2 = store.beginWrite("feature");
        w2.addNode("Person", Colls.mapOf("name", "Carol"));
        GraphCommit carol = w2.commit("carol", "add Carol");

        // 不传 keep ⇒ 引擎默认保留**全部分支** ⇒ 什么都不该被回收
        GraphMaintenanceService.GcOutcome outcome = maintenance.collectGarbage();

        assertEquals(0, outcome.getCollected());
        assertEquals(new java.util.TreeSet<>(Arrays.asList("main", "feature")),
                new java.util.TreeSet<>(outcome.getBranchesAfter()));
        assertNotNull(store.getCommit(carol.getId()));
    }

    @Test
    void mergedSecondParentHistoryStaysReachable(@TempDir Path dataDir) {
        GraphVersionStore store = new GraphVersionStore(dataDir);
        GraphMaintenanceService maintenance = new GraphMaintenanceService(store);

        GraphWriteTransaction w1 = store.beginWrite("main");
        w1.addNode("Person", Colls.mapOf("name", "Alice"));
        GraphCommit alice = w1.commit("alice", "add Alice");

        store.createBranch("feature", alice.getId());
        GraphWriteTransaction w2 = store.beginWrite("feature");
        w2.addNode("Person", Colls.mapOf("name", "Carol"));
        GraphCommit carol = w2.commit("carol", "add Carol");

        GraphWriteTransaction w3 = store.beginWrite("main");
        w3.addNode("Person", Colls.mapOf("name", "Bob"));
        w3.commit("bob", "add Bob");

        GraphMergeResult merge = store.merge("main", "feature", "maintainer", "merge feature");
        assertTrue(merge.isMerged());

        // merge 双父 ⇒ feature 的历史进入 main 的祖先闭包 ⇒ 回收 0，分支也不被剪
        GraphMaintenanceService.GcOutcome outcome = maintenance.collectGarbage("main");

        assertEquals(0, outcome.getCollected(), "被合并分支的历史在闭包里，不该被回收");
        assertEquals(new java.util.TreeSet<>(Arrays.asList("main", "feature")),
                new java.util.TreeSet<>(outcome.getBranchesAfter()));
        assertNotNull(store.getCommit(carol.getId()));
    }

    @Test
    void unknownKeepBranchFailsLoudlyAndCountsFailure(@TempDir Path dataDir) {
        GraphVersionStore store = new GraphVersionStore(dataDir);
        GraphMaintenanceService maintenance = new GraphMaintenanceService(store);

        assertThrows(GraphVersionStore.UnknownReferenceException.class,
                () -> maintenance.collectGarbage("no-such-branch"));

        // 失败不静默：计数 +1，最后一错有值
        Map<String, Object> stats = maintenance.stats();
        assertEquals(1L, stats.get("gcFailures"));
        assertTrue(String.valueOf(stats.get("lastError")).contains("gc failed"));
        assertEquals(0L, stats.get("gcCollectedCommits"));
    }

    @Test
    void exportsSnapshotWithDigestAndContentManifest(@TempDir Path dataDir) throws IOException {
        Path outDir = rehearsalDir();
        GraphVersionStore store = new GraphVersionStore(dataDir);
        GraphMaintenanceService maintenance = new GraphMaintenanceService(store);

        GraphWriteTransaction write = store.beginWrite("main");
        Node alice = write.addNode("Person", Colls.mapOf("name", "Alice", "age", 30L));
        Node bob = write.addNode("Person", Colls.mapOf("name", "Bob", "age", 25L));
        write.addEdge("KNOWS", alice.getId(), bob.getId(), Colls.mapOf("since", 2020L));
        write.commit("alice", "seed graph");

        GraphMaintenanceService.SnapshotArtifact artifact = maintenance.exportSnapshot("main", outDir);

        assertTrue(Files.exists(artifact.getPath()));
        assertEquals(artifact.getBytes(), Files.size(artifact.getPath()));
        assertEquals(64, artifact.getSha256().length());
        assertEquals(artifact.getSha256(), GraphMaintenanceService.sha256(artifact.getPath()));
        assertNotNull(store.getCommit(artifact.getCommitId()));

        // 快照落盘名固定化，供 z-oss 备份链与回读校验按名取用
        Path snapshot = outDir.resolve("zgraph-main.snapshot");
        Files.copy(artifact.getPath(), snapshot, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.write(outDir.resolve("expected-content.txt"),
                contentManifest(store.checkoutBranch("main")).getBytes(StandardCharsets.UTF_8));

        Map<String, Object> stats = maintenance.stats();
        assertEquals(1L, stats.get("snapshotExports"));
        assertEquals(0L, stats.get("snapshotFailures"));
        assertFalse(stats.get("lastError") instanceof String);
    }

    /**
     * 内容清单（回读校验的**期望值**）：节点/边按 id 排序，逐行文本化。
     * 读回快照后必须逐行一致 —— 这才是"回读校验通过"，而不是"文件在不在"。
     */
    static String contentManifest(GraphCheckout checkout) {
        List<String> lines = new ArrayList<>();
        List<Node> nodes = new ArrayList<>(checkout.getStore().getAllNodes());
        nodes.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        for (Node node : nodes) {
            List<String> labels = new ArrayList<>(node.getLabels());
            Collections.sort(labels);
            lines.add("NODE|" + node.getId() + "|" + String.join(",", labels) + "|"
                    + properties(node.getProperties()));
        }
        List<Edge> edges = new ArrayList<>(checkout.getStore().getAllEdges());
        edges.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        for (Edge edge : edges) {
            lines.add("EDGE|" + edge.getId() + "|" + edge.getType() + "|"
                    + edge.getStartNodeId() + "|" + edge.getEndNodeId() + "|"
                    + properties(edge.getProperties()));
        }
        return String.join("\n", lines) + "\n";
    }

    private static String properties(Map<String, Object> properties) {
        if (properties == null || properties.isEmpty()) {
            return "";
        }
        Map<String, Object> sorted = new TreeMap<>(properties);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (sb.length() > 0) {
                sb.append(";");
            }
            sb.append(entry.getKey()).append("=").append(String.valueOf(entry.getValue()));
        }
        return sb.toString();
    }
}
