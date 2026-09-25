package com.zifang.z.graph.core;

import com.zifang.z.graph.api.Edge;
import com.zifang.z.graph.api.GraphCommit;
import com.zifang.z.graph.api.GraphStore;
import com.zifang.z.graph.api.Node;
import com.zifang.z.graph.api.TagSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MVCC 语义测试：节点多版本链、按 commit 打开视图、增量化存储、检查点回放、
 * 并发提交、持久化往返和版本回收。
 *
 * <p>{@link GraphVersionStore} 的构造函数会先建一个空的 root commit，因此下面
 * 所有提交数和版本数都是把 root 一起算进去的；seed 出的 Person 节点 age 为
 * {@code 20 + i}。</p>
 */
class MvccVersioningTest {

    /** 建立含 {@code count} 个 Person 节点的基础图，返回提交点。 */
    private static GraphCommit seed(GraphVersionStore repository, int count) {
        GraphWriteTransaction write = repository.beginWrite("main");
        for (int i = 0; i < count; i++) {
            write.addNode("Person", Map.of("name", "P" + i, "age", 20 + i));
        }
        return write.commit("seed", "seed " + count);
    }

    private static long onlyNodeId(GraphCheckout checkout, String name) {
        for (Node node : checkout.getStore().getAllNodes()) {
            if (name.equals(node.get("name"))) return node.getId();
        }
        throw new AssertionError("node not found: " + name);
    }

    /** 把整图视图渲染成可逐字节比较的规范化文本，用于比对两条不同回放策略的链。 */
    private static String render(GraphStore store) {
        StringBuilder out = new StringBuilder();
        for (Node node : store.getAllNodes()) {
            out.append("N").append(node.getId()).append('[').append(new TreeSet<>(node.getLabels()))
                    .append(']').append(new TreeMap<>(node.getProperties())).append('\n');
        }
        for (Edge edge : store.getAllEdges()) {
            out.append("E").append(edge.getId()).append('{').append(edge.getType()).append('}')
                    .append(edge.getStartNodeId()).append("->").append(edge.getEndNodeId())
                    .append(new TreeMap<>(edge.getProperties())).append('\n');
        }
        return out.toString();
    }

    private static long stat(Map<String, Object> stats, String key) {
        return ((Number) stats.get(key)).longValue();
    }

    @Test
    void nodeCarriesOneVersionPerCommitThatChangedIt() {
        GraphVersionStore repository = new GraphVersionStore();
        GraphCommit base = seed(repository, 3);
        long alice = onlyNodeId(repository.checkout(base.getId()), "P0");

        List<String> commitIds = new ArrayList<>();
        for (int age = 30; age < 33; age++) {
            GraphWriteTransaction write = repository.beginWrite("main");
            write.updateNode(alice, Map.of("age", age));
            commitIds.add(write.commit("editor", "age=" + age).getId());
        }

        List<GraphEntityVersion> history = repository.nodeVersions(alice);
        // 1 个创建版本 + 3 个改属性版本；同一次提交里改多个属性也只算一个版本。
        assertEquals(4, history.size());
        assertEquals(Set.of("name", "age"), new HashSet<>(history.get(0).getNode().getProperties().keySet()));
        assertEquals(20, history.get(0).getNode().get("age"));
        for (int i = 0; i < commitIds.size(); i++) {
            GraphEntityVersion version = history.get(i + 1);
            assertEquals(commitIds.get(i), version.getCommitId());
            assertEquals(30 + i, version.getNode().get("age"));
            assertFalse(version.isDelete());
        }
        // 版本链按提交序号单调递增，不依赖墙上时钟。
        for (int i = 1; i < history.size(); i++) {
            assertTrue(history.get(i).getCommitSequence() > history.get(i - 1).getCommitSequence());
        }
        // 没被碰过的兄弟节点只有一条创建版本 —— 版本链是实体级的，不是提交级的。
        assertEquals(1, repository.nodeVersions(onlyNodeId(repository.checkout(base.getId()), "P2")).size());
    }

    @Test
    void everyCommitViewShowsItsOwnVersionOfTheNode() {
        GraphVersionStore repository = new GraphVersionStore();
        GraphCommit base = seed(repository, 2);
        long target = onlyNodeId(repository.checkout(base.getId()), "P0");

        GraphWriteTransaction first = repository.beginWrite("main");
        first.updateNode(target, Map.of("age", 31));
        GraphCommit firstCommit = first.commit("editor", "31");

        GraphWriteTransaction second = repository.beginWrite("main");
        second.updateNode(target, Map.of("city", "Hangzhou"));
        GraphCommit secondCommit = second.commit("editor", "city");

        assertEquals(20, repository.checkout(base.getId()).getStore().getNode(target).get("age"));
        assertNull(repository.checkout(base.getId()).getStore().getNode(target).get("city"));
        assertEquals(31, repository.checkout(firstCommit.getId()).getStore().getNode(target).get("age"));
        assertNull(repository.checkout(firstCommit.getId()).getStore().getNode(target).get("city"));
        assertEquals(31, repository.checkout(secondCommit.getId()).getStore().getNode(target).get("age"));
        assertEquals("Hangzhou", repository.checkout(secondCommit.getId()).getStore().getNode(target).get("city"));

        // 节点在指定 ref 上的可见版本 = 最近一次真正改动过它的祖先提交。
        assertEquals(base.getId(),
                repository.nodeVersionAt(target, base.getId()).orElseThrow().getCommitId());
        assertEquals(firstCommit.getId(),
                repository.nodeVersionAt(target, firstCommit.getId()).orElseThrow().getCommitId());
        assertEquals(secondCommit.getId(),
                repository.nodeVersionAt(target, "main").orElseThrow().getCommitId());
        // 历史包含创建版本，因此比"改动次数"多一条。
        assertEquals(3, repository.nodeHistory(target, "main").size());
        assertEquals(2, repository.nodeHistory(target, firstCommit.getId()).size());
        assertEquals(1, repository.nodeHistory(target, base.getId()).size());
    }

    @Test
    void deletionIsAVersionAndOlderViewsKeepTheNode() {
        GraphVersionStore repository = new GraphVersionStore();
        GraphCommit base = seed(repository, 3);
        long doomed = onlyNodeId(repository.checkout(base.getId()), "P1");
        long survivor = onlyNodeId(repository.checkout(base.getId()), "P2");

        GraphWriteTransaction write = repository.beginWrite("main");
        assertTrue(write.removeNode(doomed));
        GraphCommit deleted = write.commit("editor", "delete P1");

        assertNotNull(repository.checkout(base.getId()).getStore().getNode(doomed));
        assertNull(repository.checkout(deleted.getId()).getStore().getNode(doomed));
        assertEquals(2, repository.checkout(deleted.getId()).getNodeCount());
        assertNotNull(repository.checkout(deleted.getId()).getStore().getNode(survivor));

        GraphEntityVersion last = repository.nodeVersionAt(doomed, deleted.getId()).orElseThrow();
        assertTrue(last.isDelete());
        assertEquals(GraphEntityVersion.Kind.DELETE, last.getKind());
        assertNull(last.getNode());
        // 删除版本也占链上的一格，旧 ref 上仍然解析到创建版本。
        assertEquals(List.of(GraphEntityVersion.Kind.UPSERT, GraphEntityVersion.Kind.DELETE),
                repository.nodeVersions(doomed).stream().map(GraphEntityVersion::getKind).toList());
        assertEquals(base.getId(), repository.nodeVersionAt(doomed, base.getId()).orElseThrow().getCommitId());
        assertFalse(repository.nodeVersionAt(survivor, deleted.getId()).orElseThrow().isDelete());
    }

    /**
     * 这是本次升级的核心不变式：存储成本必须随"变更量"增长，而不是随
     * "提交数 × 图规模"增长。旧实现每个 commit 存一份整图副本，这里会是 41 × 2000。
     */
    @Test
    void commitsStoreDeltasNotWholeGraphCopies() {
        GraphVersionStore repository = new GraphVersionStore();
        int graphSize = 2000;
        seed(repository, graphSize);

        for (int i = 0; i < 40; i++) {
            GraphWriteTransaction write = repository.beginWrite("main");
            write.addNode("Audit", Map.of("seq", i));
            write.commit("auditor", "audit " + i);
        }

        Map<String, Object> stats = repository.versionStats();
        // 40 次提交各写 1 个节点，加上 seed 阶段的 2000 个。
        assertEquals(2040L, stat(stats, "retainedDeltaEntities"));
        assertEquals(0L, stat(stats, "retainedDeltaDeletes"));
        assertEquals(42L, stat(stats, "commitCount"));
        assertEquals(2040L, stat(stats, "nodeVersionRecords"));
        assertEquals(2040L, stat(stats, "versionedNodeCount"));
        // 整图副本方案的量级下界：41 × 2000，比实际留存量高两个数量级。
        assertTrue(41L * graphSize > 20 * stat(stats, "retainedDeltaEntities"));

        // 更硬的证据：再多提交 60 次空事务，留存量必须一动不动。
        for (int i = 0; i < 60; i++) {
            repository.beginWrite("main").commit("no-op", "empty " + i);
        }
        Map<String, Object> afterEmpty = repository.versionStats();
        assertEquals(102L, stat(afterEmpty, "commitCount"));
        assertEquals(2040L, stat(afterEmpty, "retainedDeltaEntities"));
        assertEquals(2040L, stat(afterEmpty, "nodeVersionRecords"));
    }

    @Test
    void writeTransactionRecordsOnlyTouchedEntities() {
        GraphVersionStore repository = new GraphVersionStore();
        seed(repository, 500);

        GraphWriteTransaction write = repository.beginWrite("main");
        assertTrue(write.pendingDelta().isEmpty());
        assertEquals(500, write.getNodeCount());

        Node added = write.addNode("Person", Map.of("name", "Newcomer"));
        assertEquals(1, write.pendingDelta().nodeUpserts().size());
        assertEquals(added.getId(), write.pendingDelta().nodeUpserts().iterator().next().getId());

        // 读操作不能污染 delta —— 否则"只记变更"的前提不成立。
        write.getAllNodes();
        write.getNode(0L);
        write.findNodesByProperty("Person", "name", "P0");
        assertEquals(1, write.pendingDelta().touchedEntityCount());
        write.rollback();

        GraphWriteTransaction rewritten = repository.beginWrite("main");
        assertTrue(rewritten.pendingDelta().isEmpty(), "rollback 后的新事务不应继承上一次变更");
        rewritten.rollback();
    }

    @Test
    void materializedViewsAreSharedAndStayImmutableAcrossLaterCommits() {
        GraphVersionStore repository = new GraphVersionStore();
        GraphCommit base = seed(repository, 5);
        GraphCheckout view = repository.checkout(base.getId());
        long target = onlyNodeId(view, "P0");
        long doomed = onlyNodeId(view, "P4");

        GraphWriteTransaction write = repository.beginWrite("main");
        write.updateNode(target, Map.of("age", 99));
        write.removeNode(doomed);
        write.commit("editor", "mutate");

        // 已经交出去的 checkout 绝不能被后续提交追溯修改。
        assertEquals(5, view.getNodeCount());
        assertEquals(20, view.getStore().getNode(target).get("age"));
        assertNotNull(view.getStore().getNode(doomed));
        assertThrows(UnsupportedOperationException.class, () -> view.getStore().updateNode(target, Map.of("age", 1)));
        assertThrows(UnsupportedOperationException.class, () -> view.query("MATCH (n:Person) SET n.age = 1"));
        // 同一 commit 的视图被复用，后续提交只产生新视图。
        assertEquals(20, repository.checkout(base.getId()).getStore().getNode(target).get("age"));
        assertEquals(4, repository.checkoutBranch("main").getNodeCount());
    }

    @Test
    void longDeltaChainsReplayToTheSameViewAsFrequentCheckpoints() {
        GraphVersionStore frequent = new GraphVersionStore().withCheckpointInterval(1);
        GraphVersionStore sparse = new GraphVersionStore().withCheckpointInterval(1000)
                .withEagerCheckpoints(false)
                .withMaxRetainedViews(1);

        List<String> frequentCommits = new ArrayList<>();
        List<String> sparseCommits = new ArrayList<>();
        for (GraphVersionStore repository : List.of(frequent, sparse)) {
            GraphCommit base = seed(repository, 60);
            long first = onlyNodeId(repository.checkout(base.getId()), "P0");
            (repository == frequent ? frequentCommits : sparseCommits).add(base.getId());
            for (int i = 0; i < 30; i++) {
                GraphWriteTransaction write = repository.beginWrite("main");
                write.updateNode(first, Map.of("age", 100 + i));
                Node trail = write.addNode("Trail", Map.of("i", i));
                write.addEdge("POINTS", first, trail.getId(), Map.of("step", i));
                GraphCommit commit = write.commit("torture", "step " + i);
                (repository == frequent ? frequentCommits : sparseCommits).add(commit.getId());
            }
            assertEquals(90, repository.checkoutBranch("main").getNodeCount());
            assertEquals(30, repository.checkoutBranch("main").getEdgeCount());
        }

        // 逐步比对两个仓库的同源视图：稀疏检查点 + 单视图 LRU 必须回放出一模一样的图。
        assertEquals(frequentCommits.size(), sparseCommits.size());
        for (int i = 0; i < frequentCommits.size(); i++) {
            String expected = render(frequent.checkout(frequentCommits.get(i)).getStore());
            assertEquals(expected, render(sparse.checkout(sparseCommits.get(i)).getStore()),
                    "view diverges at chain position " + i);
        }
        // 两侧的缓存策略必须真的不同，否则上面的相等比较只是拿同一份缓存自证。
        long sparseViews = stat(sparse.versionStats(), "materializedViews");
        long frequentViews = stat(frequent.versionStats(), "materializedViews");
        assertTrue(frequentViews >= 32, "间隔 1 应驻留全部视图，实际 " + frequentViews);
        assertTrue(sparseViews < 16, "稀疏配置必须靠回放而非缓存，实际驻留 " + sparseViews);
        assertTrue(sparse.reachableCommitCount() > 30);
    }

    @Test
    void headViewsStayLayeredAfterCommitAndIndexDefinitionsReadThroughLayers() {
        GraphVersionStore repository = new GraphVersionStore();
        GraphCommit seeded = seed(repository, 20);
        long target = onlyNodeId(repository.checkout(seeded.getId()), "P3");

        List<String> chain = new ArrayList<>();
        List<Object> expectedAges = new ArrayList<>();
        chain.add(seeded.getId());
        expectedAges.add(23);
        for (int i = 0; i < 5; i++) {
            GraphWriteTransaction write = repository.beginWrite("main");
            write.updateNode(target, Map.of("age", 200 + i));
            chain.add(write.commit("editor", "bump " + i).getId());
            expectedAges.add(200 + i);
        }
        // 提交后 head 视图必须还是套叠的覆盖层。每次提交都摊平成整图副本就是这个断言要抓的回归。
        long layers = stat(repository.versionStats(), "maxViewLayers");
        assertTrue(layers >= 3, "head 视图应当仍在套叠，实际最深 " + layers + " 层");

        GraphWriteTransaction indexed = repository.beginWrite("main");
        indexed.createPropertyIndex("Person", "name");
        chain.add(indexed.commit("editor", "index").getId());
        expectedAges.add(204);

        // 索引定义写在祖先层，之后的事务必须穿透覆盖层读得到，并且能按它查。
        GraphWriteTransaction later = repository.beginWrite("main");
        assertTrue(later.hasPropertyIndex("Person", "name"));
        assertEquals(List.of(List.of("Person", "name")), later.getPropertyIndexes());
        assertEquals(List.of(target), later.findNodesByProperty("Person", "name", "P3"));
        later.updateNode(target, Map.of("age", 300));
        chain.add(later.commit("editor", "bump with ancestor index").getId());
        expectedAges.add(300);

        // 每个已发布 commit 的视图都要逐字相符：套叠和摊平都不许追溯改写历史。
        for (int i = 0; i < chain.size(); i++) {
            Node atCommit = repository.checkout(chain.get(i)).getStore().getNode(target);
            assertEquals(expectedAges.get(i), atCommit.get("age"), "view diverges at position " + i);
        }

        // 层数封顶：超过上限必须摊平一次，否则读要穿透任意深的链。
        for (int i = 0; i < 60; i++) {
            GraphWriteTransaction write = repository.beginWrite("main");
            write.updateNode(target, Map.of("age", 400 + i));
            write.commit("torture", "depth " + i);
        }
        long bounded = stat(repository.versionStats(), "maxViewLayers");
        assertTrue(bounded <= GraphVersionStore.DEFAULT_VIEW_LAYER_LIMIT,
                "层数封顶失效，最深 " + bounded + " 层");
        assertEquals(459, repository.checkoutBranch("main").getStore().getNode(target).get("age"));
    }

    @Test
    void entityBudgetEvictsColdViewsWithoutBreakingHistoricalReads() {
        // 检查点间隔取 1：每次提交都摊平成整图副本，实体预算才有东西可淘汰
        // （套叠的薄层只按本层增量计费，本来就很便宜）。
        GraphVersionStore repository = new GraphVersionStore()
                .withMaxRetainedEntities(1_200)
                .withCheckpointInterval(1)
                .withEagerCheckpoints(false);
        GraphCommit seeded = seed(repository, 300);
        long target = onlyNodeId(repository.checkout(seeded.getId()), "P7");
        List<String> chain = new ArrayList<>();
        chain.add(seeded.getId());
        for (int i = 0; i < 20; i++) {
            GraphWriteTransaction write = repository.beginWrite("main");
            write.updateNode(target, Map.of("age", 500 + i));
            chain.add(write.commit("mem", "bump " + i).getId());
        }

        // 只按数量封顶的话这里会驻留 21 份 300 节点的整图；实体预算必须把它压住。
        long retained = stat(repository.versionStats(), "retainedViewEntities");
        assertTrue(retained <= 1_200, "驻留实体 " + retained + " 未受预算约束");
        assertTrue(stat(repository.versionStats(), "materializedViews") < 21);
        // 淘汰只影响快慢：淘汰掉的 commit 仍然要能回放成当时的视图。
        assertEquals(500, repository.checkout(chain.get(1)).getStore().getNode(target).get("age"));
        assertEquals(519, repository.checkout(chain.get(20)).getStore().getNode(target).get("age"));
        assertEquals(300, repository.checkout(chain.get(0)).getStore().getNodeCount());
        // head 视图不参与淘汰，否则下一次 beginWrite 又要回放整图。
        assertEquals(519, repository.beginWrite("main").getNode(target).get("age"));
    }

    @Test
    void concurrentBranchesCommitIndependentlyAndBothViewsSurvive() {
        GraphVersionStore repository = new GraphVersionStore();
        GraphCommit base = seed(repository, 10);
        repository.createBranch("feature", base.getId());

        // 两个分支各自开事务、各自提交，互不覆盖。
        GraphWriteTransaction mainWrite = repository.beginWrite("main");
        mainWrite.addNode("Person", Map.of("name", "Main-only"));
        GraphWriteTransaction featureWrite = repository.beginWrite("feature");
        featureWrite.addNode("Person", Map.of("name", "Feature-only"));

        GraphCommit mainHead = mainWrite.commit("main", "main change");
        assertEquals(11, repository.checkout(mainHead.getId()).getNodeCount());
        assertEquals(11, repository.checkout(featureWrite.commit("feature", "feature change").getId()).getNodeCount());

        assertNull(repository.checkout(mainHead.getId()).getStore().getAllNodes().stream()
                .filter(n -> "Feature-only".equals(n.get("name"))).findFirst().orElse(null));
        assertEquals(10, repository.checkout(base.getId()).getNodeCount());

        // 乐观并发：head 已被推进的旧事务必须失败，而不是静默覆盖。
        GraphWriteTransaction stale = repository.beginWrite("main");
        stale.addNode("Person", Map.of("name", "too late"));
        GraphCommit raced = repository.beginWrite("main").commit("other", "race");
        assertThrows(GraphVersionStore.StaleHeadException.class, () -> stale.commit("x", "must fail"));
        assertEquals(raced.getId(), repository.getBranchHead("main").getId());
        // 失败的事务不留版本。
        assertEquals(11, repository.checkout(raced.getId()).getNodeCount());
    }

    @Test
    void versionChainsAndViewsSurviveRestart(@TempDir Path directory) {
        GraphVersionStore repository = new GraphVersionStore(directory);
        GraphCommit base = seed(repository, 4);
        long target = onlyNodeId(repository.checkout(base.getId()), "P0");
        repository.createBranch("release", base.getId());

        GraphWriteTransaction first = repository.beginWrite("main");
        first.updateNode(target, Map.of("age", 41));
        GraphCommit c1 = first.commit("editor", "41");

        GraphWriteTransaction second = repository.beginWrite("release");
        second.updateNode(target, Map.of("age", 7));
        GraphCommit c2 = second.commit("editor", "7 on release");

        GraphVersionStore reopened = new GraphVersionStore(directory);
        assertEquals(c1.getId(), reopened.getBranchHead("main").getId());
        assertEquals(c2.getId(), reopened.getBranchHead("release").getId());
        // root + seed + c1 + c2
        assertEquals(4, reopened.listCommits().size());
        // 版本链是从增量重建的：seed 创建 + 两条分支各改一次。
        assertEquals(3, reopened.nodeVersions(target).size());
        assertEquals(c1.getId(), reopened.nodeVersionAt(target, "main").orElseThrow().getCommitId());
        assertEquals(c2.getId(), reopened.nodeVersionAt(target, "release").orElseThrow().getCommitId());

        // 序号必须恢复，重启后新提交不能和历史撞号。
        GraphWriteTransaction third = reopened.beginWrite("main");
        third.updateNode(target, Map.of("age", 42));
        GraphCommit c3 = third.commit("editor", "42");
        assertNotEqualsAnyOf(c3.getId(), c1.getId(), c2.getId(), base.getId());
        assertTrue(c3.getTimestampEpochMillis() > 0);
        assertEquals(4, reopened.nodeVersions(target).size());
        long lastSequence = reopened.nodeVersions(target).get(3).getCommitSequence();
        assertTrue(lastSequence > reopened.nodeVersions(target).get(2).getCommitSequence());

        assertEquals(41, reopened.checkout(c1.getId()).getStore().getNode(target).get("age"));
        assertEquals(7, reopened.checkout(c2.getId()).getStore().getNode(target).get("age"));
        assertEquals(42, reopened.checkout(c3.getId()).getStore().getNode(target).get("age"));
        // release 分支上看不到 main 的后续变更。
        assertEquals(2, reopened.nodeHistory(target, c2.getId()).size());
        assertEquals(3, reopened.nodeHistory(target, c3.getId()).size());
        assertEquals(7, reopened.checkoutBranch("release").getStore().getNode(target).get("age"));

        GraphVersionStore again = new GraphVersionStore(directory);
        assertEquals(c3.getId(), again.getBranchHead("main").getId());
        assertEquals(42, again.checkout(c3.getId()).getStore().getNode(target).get("age"));
        assertEquals(41, again.checkout(c1.getId()).getStore().getNode(target).get("age"));
        assertEquals(4, again.nodeVersions(target).size());
    }

    private static void assertNotEqualsAnyOf(String candidate, String... others) {
        for (String other : others) {
            assertFalse(candidate.equals(other), "commit id collided with " + other);
        }
    }

    @Test
    void garbageCollectDropsUnreachableHistoryButKeepsRetainedBranches(@TempDir Path directory) {
        GraphVersionStore repository = new GraphVersionStore(directory);
        GraphCommit seedCommit = seed(repository, 5);
        repository.createBranch("scratch", repository.getBranchHead("main").getId());
        long p0 = onlyNodeId(repository.checkout(seedCommit.getId()), "P0");

        GraphWriteTransaction scratchWrite = repository.beginWrite("scratch");
        scratchWrite.addNode("Temp", Map.of("keep", false));
        GraphCommit scratchHead = scratchWrite.commit("trash", "throwaway");
        assertEquals(6, repository.checkout(scratchHead.getId()).getNodeCount());
        // root + seed + scratchHead
        assertEquals(3, repository.reachableCommitCount());

        assertTrue(repository.listBranches().contains("scratch"));
        int collected = repository.garbageCollect("main");
        assertEquals(1, collected);
        assertNull(repository.listCommits().stream()
                .filter(commit -> commit.getId().equals(scratchHead.getId())).findFirst().orElse(null));
        // 被丢弃分支的指针必须一起消失，否则 repository.bin 会引用已删除的对象。
        assertFalse(repository.listBranches().contains("scratch"));
        assertEquals(2, repository.reachableCommitCount());
        assertThrows(IllegalArgumentException.class, () -> repository.getBranchHead("scratch"));
        assertTrue(repository.nodeVersions(p0).size() > 0);
        // 保留分支的历史视图在回收后仍然完整可读。
        List<GraphCommit> mainLog = repository.log("main");
        assertEquals(2, mainLog.size());
        assertEquals(5, repository.materializeView(mainLog.get(0).getId()).getNodeCount());
        assertEquals(0, repository.materializeView(mainLog.get(1).getId()).getNodeCount());

        // 回收也必须能在重启后成立。
        GraphVersionStore reopened = new GraphVersionStore(directory);
        assertEquals(2, reopened.listCommits().size());
        assertFalse(reopened.listBranches().contains("scratch"));
        assertEquals(5, reopened.checkoutBranch("main").getNodeCount());
    }

    @Test
    void schemaAndIndexChangesAreVersionedToo() {
        GraphVersionStore repository = new GraphVersionStore();
        GraphCommit base = seed(repository, 2);

        GraphWriteTransaction write = repository.beginWrite("main");
        write.createTag(new TagSchema("Person", List.of(
                new TagSchema.Field("name", TagSchema.DataType.STRING, false),
                new TagSchema.Field("age", TagSchema.DataType.INT, true))));
        write.createPropertyIndex("Person", "age");
        GraphCommit withSchema = write.commit("dba", "schema");

        assertTrue(repository.checkout(withSchema.getId()).hasPropertyIndex("Person", "age"));
        assertFalse(repository.checkout(base.getId()).hasPropertyIndex("Person", "age"));
        assertEquals(List.of("Person"), repository.checkout(withSchema.getId()).listTags());
        assertEquals(List.of(), repository.checkout(base.getId()).listTags());
        assertNotNull(repository.checkout(withSchema.getId()).getTagSchema("Person"));
        assertNull(repository.checkout(base.getId()).getTagSchema("Person"));

        // 索引定义是版本化的，值倒排必须在物化视图上真的建好，而不是只有定义。
        InMemoryGraphStore indexedView = repository.materializeView(withSchema.getId());
        assertTrue(indexedView.hasPropertyIndex("Person", "age"));
        assertEquals(List.of(1L), indexedView.findNodesByProperty("Person", "age", 21));
        assertEquals(List.of(), repository.materializeView(base.getId())
                .findNodesByProperty("Person", "age", 999));

        GraphWriteTransaction drop = repository.beginWrite("main");
        for (Long id : new ArrayList<>(drop.getNodeIdsByLabel("Person"))) {
            assertTrue(drop.removeNode(id));
        }
        assertTrue(drop.dropTag("Person"));
        assertTrue(drop.dropPropertyIndex("Person", "age"));
        GraphCommit dropped = drop.commit("dba", "drop");

        assertEquals(List.of(), repository.checkout(dropped.getId()).listTags());
        assertFalse(repository.checkout(dropped.getId()).hasPropertyIndex("Person", "age"));
        assertEquals(0, repository.checkout(dropped.getId()).getNodeCount());
        // 旧提交上的 schema、索引和节点都不受后续 drop 影响。
        assertEquals(List.of("Person"), repository.checkout(withSchema.getId()).listTags());
        assertTrue(repository.checkout(withSchema.getId()).hasPropertyIndex("Person", "age"));
        assertEquals(2, repository.checkout(withSchema.getId()).getNodeCount());
    }

    @Test
    void edgeUpdatesReindexAdjacencyOnBothEndpoints() {
        GraphVersionStore repository = new GraphVersionStore();
        GraphWriteTransaction setup = repository.beginWrite("main");
        Node a = setup.addNode("Person", Map.of("name", "A"));
        Node b = setup.addNode("Person", Map.of("name", "B"));
        Node c = setup.addNode("Person", Map.of("name", "C"));
        Edge knows = setup.addEdge("KNOWS", a.getId(), b.getId(), Map.of("since", 2020));
        GraphCommit base = setup.commit("seed", "triangle");

        GraphWriteTransaction move = repository.beginWrite("main");
        long edgeId = knows.getId();
        move.removeEdge(edgeId);
        move.addEdge(edgeId, "KNOWS", a.getId(), c.getId(), Map.of("since", 2021));
        // 同一事务内先删后加同一 id，净变更必须只是一条 UPSERT。
        assertEquals(0, move.pendingDelta().edgeDeletes().size());
        assertEquals(1, move.pendingDelta().edgeUpserts().size());
        GraphCommit moved = move.commit("editor", "rewire");

        assertEquals(1, repository.checkout(base.getId()).getStore().getOutEdges(a.getId()).size());
        assertEquals(b.getId(),
                repository.checkout(base.getId()).getStore().getOutEdges(a.getId()).get(0).getEndNodeId());
        List<Edge> after = repository.checkout(moved.getId()).getStore().getOutEdges(a.getId());
        assertEquals(1, after.size());
        assertEquals(c.getId(), after.get(0).getEndNodeId());
        assertEquals(2021, after.get(0).get("since"));
        assertEquals(1, repository.checkout(moved.getId()).getStore().getInEdges(c.getId()).size());
        assertEquals(0, repository.checkout(moved.getId()).getStore().getInEdges(b.getId()).size());
        assertEquals(1, repository.checkout(base.getId()).getStore().getInEdges(b.getId()).size());

        // 边版本链记录两个版本；链上最后一个才是当前可见内容。
        List<GraphEntityVersion> edgeHistory = repository.edgeVersions(edgeId);
        assertEquals(2, edgeHistory.size());
        assertEquals(base.getId(), edgeHistory.get(0).getCommitId());
        assertEquals(moved.getId(), edgeHistory.get(1).getCommitId());
        assertEquals(b.getId(), edgeHistory.get(0).getEdge().getEndNodeId());
        assertEquals(c.getId(), edgeHistory.get(1).getEdge().getEndNodeId());
        assertFalse(edgeHistory.get(1).isDelete());
        assertEquals(base.getId(), repository.edgeVersionAt(edgeId, base.getId()).orElseThrow().getCommitId());
        // 只改边的两端不会给端点节点增加版本。
        assertEquals(1, repository.nodeVersions(a.getId()).size());
        assertEquals(1, repository.nodeVersions(c.getId()).size());
    }

    @Test
    void cypherSetInsideWriteTransactionPersistsToTheCommit() {
        GraphVersionStore repository = new GraphVersionStore();
        GraphWriteTransaction write = repository.beginWrite("main");
        new CypherEngine(write).execute(
                "CREATE (a:Person {name: 'Alice', age: 30}), (b:Person {name: 'Bob'}), (a)-[:KNOWS]->(b)");
        GraphCommit created = write.commit("alice", "create");

        GraphWriteTransaction patch = repository.beginWrite("main");
        List<Map<String, Object>> updated = new CypherEngine(patch).execute(
                "MATCH (n:Person {name: 'Alice'}) SET n.age = 31, n.city = 'Beijing' RETURN n.age AS age");
        assertEquals(31, updated.get(0).get("age"));
        GraphCommit patched = patch.commit("alice", "set");

        // SET 之前是直接改句柄的，在只读物化视图上会静默丢失，这里钉住它真的落库了。
        List<Map<String, Object>> rows = repository.checkout(patched.getId())
                .query("MATCH (n:Person {name: 'Alice'}) RETURN n.age AS age, n.city AS city");
        assertEquals(31, rows.get(0).get("age"));
        assertEquals("Beijing", rows.get(0).get("city"));
        assertEquals(30, repository.checkout(created.getId()).getStore().getAllNodes().stream()
                .filter(node -> "Alice".equals(node.get("name"))).findFirst().orElseThrow().get("age"));
        long alice = repository.checkout(created.getId()).getStore().getAllNodes().stream()
                .filter(node -> "Alice".equals(node.get("name"))).findFirst().orElseThrow().getId();
        // 此刻链上两格：CREATE 一次、SET 一次。
        assertEquals(2, repository.nodeVersions(alice).size());
        // 两条属性改一次提交，仍然只加一个版本。
        GraphWriteTransaction again = repository.beginWrite("main");
        new CypherEngine(again).execute("MATCH (n:Person {name: 'Alice'}) SET n.age = 32, n.city = 'Shanghai'");
        GraphCommit third = again.commit("alice", "set twice");
        assertEquals(3, repository.nodeVersions(alice).size());
        assertEquals("Shanghai", repository.checkout(third.getId()).getStore().getNode(alice).get("city"));
        assertEquals(32, repository.checkout(third.getId()).getStore().getNode(alice).get("age"));
    }

    @Test
    void nodeDeletionCascadesToEdgesInViewAndDelta() {
        GraphVersionStore repository = new GraphVersionStore();
        GraphWriteTransaction setup = repository.beginWrite("main");
        Node hub = setup.addNode("Hub", Map.of("name", "hub"));
        Node leaf = setup.addNode("Leaf", Map.of("name", "leaf"));
        setup.addEdge("LINK", hub.getId(), leaf.getId(), Map.of());
        setup.addEdge("BACK", leaf.getId(), hub.getId(), Map.of());
        GraphCommit withEdges = setup.commit("seed", "star");
        assertEquals(2, repository.checkout(withEdges.getId()).getEdgeCount());

        GraphWriteTransaction remove = repository.beginWrite("main");
        assertTrue(remove.removeNode(hub.getId()));
        // 级联删除必须体现在 delta 里，否则回放旧视图时悬挂边会复活。
        assertEquals(2, remove.pendingDelta().edgeDeletes().size());
        assertEquals(1, remove.pendingDelta().nodeDeletes().size());
        GraphCommit withoutHub = remove.commit("editor", "drop hub");

        GraphCheckout view = repository.checkout(withoutHub.getId());
        assertEquals(1, view.getNodeCount());
        assertEquals(0, view.getEdgeCount());
        assertEquals(0, view.getStore().getInEdges(leaf.getId()).size());
        assertEquals(0, view.getStore().getOutEdges(leaf.getId()).size());
        assertNull(view.getStore().getNode(hub.getId()));
        // 旧视图仍然看得到被级联删掉的边。
        assertEquals(2, repository.checkout(withEdges.getId()).getStore().getEdges(hub.getId()).size());
        assertEquals(2, repository.edgeVersions(0L).size());
        assertTrue(repository.edgeVersions(1L).get(1).isDelete());
        assertNull(repository.edgeVersions(1L).get(1).getEdge());
        // 留存量 = seed 的 2 节点 + 2 边；删除记在 deletes 里，不混进 entities。
        Map<String, Object> stats = repository.versionStats();
        assertEquals(4L, stat(stats, "retainedDeltaEntities"));
        assertEquals(3L, stat(stats, "retainedDeltaDeletes"));
    }
}
