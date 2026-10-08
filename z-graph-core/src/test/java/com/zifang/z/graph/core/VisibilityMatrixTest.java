package com.zifang.z.graph.core;

import com.zifang.z.graph.api.Colls;
import com.zifang.z.graph.api.GraphMergeResult;
import com.zifang.z.graph.api.GraphCommit;
import com.zifang.z.graph.api.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MVCC 可见性矩阵（仓库级）：main 分叉出 A/B 后，A 的提交对 B 不可见、对 main
 * 不可见；merge 之后双方闭包并集互见。这是引擎原生 MVCC 的核心不变量。
 */
class VisibilityMatrixTest {

    private static long nodeIdByName(GraphCheckout checkout, String name) {
        for (Node node : checkout.getStore().getAllNodes()) {
            if (name.equals(node.get("name"))) return node.getId();
        }
        throw new AssertionError("node not found: " + name);
    }

    private static String render(GraphVersionStore repository, String ref) {
        StringBuilder out = new StringBuilder();
        for (Node node : repository.checkoutBranch(ref).getStore().getAllNodes()) {
            out.append("N").append(node.getId()).append(new TreeMap<String, Object>(node.getProperties())).append('\n');
        }
        return out.toString();
    }

    @Test
    void divergedBranchesCannotSeeEachOthersCommits(@TempDir Path dir) {
        GraphVersionStore repository = new GraphVersionStore(dir);
        GraphVersionStore write1 = repository;
        GraphWriteTransaction base = write1.beginWrite("main");
        base.addNode("Person", Colls.mapOf("name", "P0", "age", 20));
        GraphCommit baseCommit = base.commit("seed", "base");

        repository.createBranch("featureA", baseCommit.getId());
        repository.createBranch("featureB", baseCommit.getId());

        GraphWriteTransaction a = repository.beginWrite("featureA");
        a.addNode("Person", Colls.mapOf("name", "OnlyA"));
        GraphCommit aCommit = a.commit("a", "A adds");

        GraphWriteTransaction b = repository.beginWrite("featureB");
        b.addNode("Person", Colls.mapOf("name", "OnlyB"));
        GraphCommit bCommit = b.commit("b", "B adds");

        // A 视图：看得到自己，看不到 B，也看不到 main 的后续
        assertEquals(2, repository.checkout(aCommit.getId()).getNodeCount());
        assertNull(repository.checkout(aCommit.getId()).getStore().getAllNodes().stream()
                .filter(n -> "OnlyB".equals(n.get("name"))).findFirst().orElse(null));

        // B 视图：对称
        assertEquals(2, repository.checkout(bCommit.getId()).getNodeCount());
        assertNull(repository.checkout(bCommit.getId()).getStore().getAllNodes().stream()
                .filter(n -> "OnlyA".equals(n.get("name"))).findFirst().orElse(null));

        // main 仍停在 base：A/B 的提交都不可见
        assertEquals(baseCommit.getId(), repository.getBranchHead("main").getId());
        assertEquals(1, repository.checkoutBranch("main").getNodeCount());

        // base 视图不受任何分支推进影响
        assertEquals(1, repository.checkout(baseCommit.getId()).getNodeCount());
    }

    @Test
    void mergeUnionsVisibilityOfBothSides(@TempDir Path dir) {
        GraphVersionStore repository = new GraphVersionStore(dir);
        GraphWriteTransaction base = repository.beginWrite("main");
        base.addNode("Person", Colls.mapOf("name", "P0", "age", 20));
        GraphCommit baseCommit = base.commit("seed", "base");

        repository.createBranch("featureA", baseCommit.getId());
        GraphWriteTransaction a = repository.beginWrite("featureA");
        a.addNode("Person", Colls.mapOf("name", "OnlyA"));
        a.commit("a", "A adds");
        GraphWriteTransaction b = repository.beginWrite("main");
        b.addNode("Person", Colls.mapOf("name", "OnlyMain"));
        b.commit("b", "main adds");

        GraphMergeResult merged = repository.merge("main", "featureA", "merger", "merge A");
        assertTrue(merged.isMerged());

        // merge 后 main 视图 = 双方并集
        assertEquals(3, repository.checkoutBranch("main").getNodeCount());
        String rendered = render(repository, "main");
        assertTrue(rendered.contains("OnlyA"));
        assertTrue(rendered.contains("OnlyMain"));

        // 历史 commit 的视图不被 merge 追溯改写
        assertEquals(1, repository.checkout(baseCommit.getId()).getNodeCount());
    }

    @Test
    void branchWriteThenCheckpointReadsOwnLineage(@TempDir Path dir) {
        GraphVersionStore repository = new GraphVersionStore(dir);
        GraphWriteTransaction seed = repository.beginWrite("main");
        seed.addNode("Person", Colls.mapOf("name", "P0", "age", 20));
        GraphCommit seedCommit = seed.commit("seed", "seed");
        long p0 = nodeIdByName(repository.checkoutBranch("main"), "P0");

        repository.createBranch("release", seedCommit.getId());
        GraphWriteTransaction mainWrite = repository.beginWrite("main");
        mainWrite.updateNode(p0, Colls.mapOf("age", 41));
        GraphCommit mainCommit = mainWrite.commit("m", "41 on main");
        GraphWriteTransaction releaseWrite = repository.beginWrite("release");
        releaseWrite.updateNode(p0, Colls.mapOf("age", 7));
        releaseWrite.commit("r", "7 on release");

        assertEquals(41, repository.checkout(mainCommit.getId()).getStore().getNode(p0).get("age"));
        assertEquals(7, repository.checkoutBranch("release").getStore().getNode(p0).get("age"));
        assertEquals(20, repository.checkout(seedCommit.getId()).getStore().getNode(p0).get("age"));
        // release 的可见链里没有 main 的 41
        List<GraphEntityVersion> releaseChain = repository.nodeHistory(p0, "release");
        assertEquals(2, releaseChain.size());
        assertFalse(releaseChain.stream().anyMatch(v -> v.getCommitId().equals(mainCommit.getId())));
        assertEquals(2, repository.nodeHistory(p0, "main").size());
    }
}
