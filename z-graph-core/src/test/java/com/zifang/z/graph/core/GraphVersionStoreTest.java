package com.zifang.z.graph.core;

import com.zifang.z.graph.api.GraphCommit;
import com.zifang.z.graph.api.GraphMergeResult;
import com.zifang.z.graph.api.GraphStore;
import com.zifang.z.graph.api.Node;
import com.zifang.z.graph.api.TagSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.zifang.z.graph.api.Colls;

/** GraphVersionStore 的 commit、分支、checkout、merge 语义测试。 */
class GraphVersionStoreTest {

    @Test
    void commitKeepsPreviousSnapshotIsolated() {
        GraphVersionStore repository = new GraphVersionStore();

        GraphWriteTransaction firstWrite = repository.beginWrite("main");
        firstWrite.addNode("Person", Colls.mapOf("name", "Alice"));
        GraphCommit first = firstWrite.commit("alice", "add Alice");

        GraphWriteTransaction secondWrite = repository.beginWrite("main");
        secondWrite.addNode("Person", Colls.mapOf("name", "Bob"));
        GraphCommit second = secondWrite.commit("bob", "add Bob");

        assertEquals(1, repository.checkout(first.getId()).getNodeCount());
        assertEquals(2, repository.checkout(second.getId()).getNodeCount());
        assertEquals(1, repository.checkout(first.getId())
                .query("MATCH (n:Person) RETURN n.name AS name").size());
    }

    @Test
    void branchCanBeCreatedFromCommitAndMergedBack() {
        GraphVersionStore repository = new GraphVersionStore();
        GraphWriteTransaction initialWrite = repository.beginWrite("main");
        initialWrite.addNode("Person", Colls.mapOf("name", "Alice"));
        GraphCommit base = initialWrite.commit("alice", "base graph");

        repository.createBranch("feature", base.getId());

        GraphWriteTransaction mainWrite = repository.beginWrite("main");
        mainWrite.addNode("Person", Colls.mapOf("name", "Bob"));
        mainWrite.commit("bob", "main change");

        GraphWriteTransaction featureWrite = repository.beginWrite("feature");
        featureWrite.addNode("Person", Colls.mapOf("name", "Carol"));
        featureWrite.commit("carol", "feature change");

        GraphMergeResult merge = repository.merge("main", "feature", "maintainer", "merge feature");
        assertTrue(merge.isMerged());
        assertFalse(merge.hasConflicts());
        assertNotNull(merge.getCommit());
        assertTrue(merge.getCommit().isMergeCommit());
        assertEquals(3, repository.checkoutBranch("main").getNodeCount());
    }

    @Test
    void checkoutIsReadOnlyAndDoesNotFollowBranchHead() {
        GraphVersionStore repository = new GraphVersionStore();
        GraphWriteTransaction write = repository.beginWrite("main");
        write.addNode("Person", Colls.mapOf("name", "Alice"));
        GraphCommit oldHead = write.commit("alice", "old");

        assertThrows(UnsupportedOperationException.class,
                () -> repository.checkout(oldHead.getId()).getStore()
                        .addNode("Person", Colls.mapOf("name", "cannot write")));

        GraphWriteTransaction nextWrite = repository.beginWrite("main");
        nextWrite.addNode("Person", Colls.mapOf("name", "Bob"));
        nextWrite.commit("bob", "new");

        List<Map<String, Object>> oldRows = repository.checkout(oldHead.getId())
                .query("MATCH (n:Person) RETURN n.name AS name");
        assertEquals(1, oldRows.size());
        assertEquals("Alice", oldRows.get(0).get("name"));
    }

    @Test
    void repositoryRestoresCommitsAndSnapshots(@TempDir Path repositoryDirectory) {
        GraphVersionStore repository = new GraphVersionStore(repositoryDirectory);
        GraphWriteTransaction write = repository.beginWrite("main");
        write.addNode("Person", Colls.mapOf("name", "Persistent Alice", "age", 30,
                "tags", Colls.listOf("graph", "storage"), "profile", Colls.mapOf("city", "Beijing")));
        write.createPropertyIndex("Person", "age");
        GraphCommit commit = write.commit("alice", "persist graph");

        GraphVersionStore reopened = new GraphVersionStore(repositoryDirectory);
        assertEquals(commit.getId(), reopened.getBranchHead("main").getId());
        assertEquals(2, reopened.listCommits().size());
        assertEquals(2, reopened.log("main").size());
        assertTrue(reopened.checkout(commit.getId()).hasPropertyIndex("Person", "age"));
        List<Map<String, Object>> rows = reopened.checkout(commit.getId())
                .query("MATCH (n:Person) RETURN n.name AS name, n.age AS age");
        assertEquals(1, rows.size());
        assertEquals("Persistent Alice", rows.get(0).get("name"));
        assertEquals(30, rows.get(0).get("age"));
        long nodeId = reopened.checkout(commit.getId()).getStore().getAllNodes().get(0).getId();
        assertEquals(Colls.listOf("graph", "storage"), reopened.checkout(commit.getId()).getStore().getNode(nodeId).get("tags"));
        assertEquals(Colls.mapOf("city", "Beijing"), reopened.checkout(commit.getId()).getStore().getNode(nodeId).get("profile"));
    }

    @Test
    void independentPropertyChangesAreMerged() {
        GraphVersionStore repository = new GraphVersionStore();
        GraphWriteTransaction initialWrite = repository.beginWrite("main");
        initialWrite.addNode("Person", Colls.mapOf("name", "Alice", "age", 30));
        GraphCommit base = initialWrite.commit("alice", "base");
        long nodeId = repository.checkout(base.getId()).getStore().getAllNodes().get(0).getId();
        repository.createBranch("feature", base.getId());

        GraphWriteTransaction mainWrite = repository.beginWrite("main");
        mainWrite.updateNode(nodeId, Colls.mapOf("city", "Beijing"));
        mainWrite.commit("main", "main property");

        GraphWriteTransaction featureWrite = repository.beginWrite("feature");
        featureWrite.updateNode(nodeId, Colls.mapOf("age", 31));
        featureWrite.commit("feature", "feature property");

        GraphMergeResult merge = repository.merge("main", "feature", "maintainer", "merge properties");
        assertTrue(merge.isMerged());
        assertEquals("Beijing", repository.checkoutBranch("main").getStore().getNode(nodeId).get("city"));
        assertEquals(31, repository.checkoutBranch("main").getStore().getNode(nodeId).get("age"));
    }

    @Test
    void conflictingChangesDoNotMoveTargetBranch() {
        GraphVersionStore repository = new GraphVersionStore();
        GraphWriteTransaction initialWrite = repository.beginWrite("main");
        initialWrite.addNode("Person", Colls.mapOf("name", "Alice", "age", 30));
        GraphCommit base = initialWrite.commit("alice", "base");
        long nodeId = repository.checkout(base.getId()).getStore().getAllNodes().get(0).getId();
        repository.createBranch("feature", base.getId());

        GraphWriteTransaction mainWrite = repository.beginWrite("main");
        mainWrite.updateNode(nodeId, Colls.mapOf("age", 31));
        GraphCommit mainHead = mainWrite.commit("main", "main age");

        GraphWriteTransaction featureWrite = repository.beginWrite("feature");
        featureWrite.updateNode(nodeId, Colls.mapOf("age", 32));
        featureWrite.commit("feature", "feature age");

        GraphMergeResult merge = repository.merge("main", "feature", "maintainer", "conflicting merge");
        assertFalse(merge.isMerged());
        assertTrue(merge.hasConflicts());
        assertEquals(mainHead.getId(), repository.getBranchHead("main").getId());
    }

    @Test
    void cypherCanWriteOnBranchAndQueryCommittedView() {
        GraphVersionStore repository = new GraphVersionStore();
        GraphWriteTransaction write = repository.beginWrite("main");
        new CypherEngine(write).execute(
                "CREATE (a:Person {name: 'Alice'}), (b:Person {name: 'Bob'}), (a)-[:KNOWS]->(b)");
        GraphCommit commit = write.commit("alice", "cypher graph");

        List<Map<String, Object>> rows = repository.checkout(commit.getId())
                .query("MATCH (a)-[:KNOWS]->(b) RETURN a.name AS from, b.name AS to");
        assertEquals(1, rows.size());
        assertEquals("Alice", rows.get(0).get("from"));
        assertEquals("Bob", rows.get(0).get("to"));
    }

    @Test
    void queryAndMetaServicesBindToVersionedRepository() {
        GraphVersionStore repository = new GraphVersionStore();
        GraphMetaService meta = new GraphMetaService(repository);
        GraphQueryService query = new GraphQueryService(repository);

        GraphWriteTransaction write = query.beginWrite("main");
        write.addNode("Person", Colls.mapOf("name", "Alice"));
        GraphCommit commit = write.commit("alice", "service facade");

        assertEquals(commit.getId(), meta.head("main").getId());
        assertEquals(2, meta.log("main").size());
        assertEquals(1, query.queryCommit(commit.getId(),
                "MATCH (n:Person) RETURN n.name AS name").size());
    }

    @Test
    void staleWriteCannotOverwriteAdvancedBranch() {
        GraphVersionStore repository = new GraphVersionStore();
        GraphWriteTransaction stale = repository.beginWrite("main");
        GraphWriteTransaction winner = repository.beginWrite("main");
        winner.addNode("Person", Colls.mapOf("name", "winner"));
        winner.commit("winner", "advance branch");

        stale.addNode("Person", Colls.mapOf("name", "stale"));
        assertThrows(IllegalStateException.class, () -> stale.commit("stale", "must fail"));
    }

    @Test
    void mergeIsNoOpWhenSourceHasNoWorkBeyondBase() {
        GraphVersionStore repository = new GraphVersionStore();
        GraphWriteTransaction initial = repository.beginWrite("main");
        initial.addNode("Person", Colls.mapOf("name", "Alice"));
        GraphCommit base = initial.commit("alice", "base");
        repository.createBranch("feature", base.getId());

        GraphWriteTransaction mainWrite = repository.beginWrite("main");
        mainWrite.addNode("Person", Colls.mapOf("name", "Bob"));
        GraphCommit mainHead = mainWrite.commit("main", "main only");

        // source 停在 base：already up to date，target 绝不能被回卷到 source。
        GraphMergeResult merge = repository.merge("main", "feature", "maintainer", "noop merge");
        assertFalse(merge.isMerged());
        assertFalse(merge.hasConflicts());
        assertEquals(mainHead.getId(), repository.getBranchHead("main").getId());
        assertEquals(2, repository.checkoutBranch("main").getStore().getNodeCount());
    }

    @Test
    void mergeFastForwardsWhenTargetHasNoDivergentWork() {
        GraphVersionStore repository = new GraphVersionStore();
        GraphWriteTransaction initial = repository.beginWrite("main");
        initial.addNode("Person", Colls.mapOf("name", "Alice"));
        GraphCommit base = initial.commit("alice", "base");
        repository.createBranch("feature", base.getId());

        GraphWriteTransaction featureWrite = repository.beginWrite("feature");
        featureWrite.addNode("Person", Colls.mapOf("name", "Bob"));
        GraphCommit featureHead = featureWrite.commit("feature", "feature only");

        GraphMergeResult merge = repository.merge("main", "feature", "maintainer", "ff merge");
        assertTrue(merge.isMerged());
        assertFalse(merge.hasConflicts());
        assertEquals(featureHead.getId(), repository.getBranchHead("main").getId());
        assertEquals(featureHead.getId(), merge.getCommit().getId(),
                "fast-forward 不产生新 commit，main head 直接指到 source head");
        assertEquals(2, repository.checkoutBranch("main").getNodeCount());
    }

    @Test
    void deletionsAndSchemaChangesPropagateThroughMerge() {
        GraphVersionStore repository = new GraphVersionStore();
        GraphWriteTransaction initial = repository.beginWrite("main");
        Node alice = initial.addNode("Person", Colls.mapOf("name", "Alice"));
        Node bob = initial.addNode("Person", Colls.mapOf("name", "Bob"));
        initial.addEdge("KNOWS", alice.getId(), bob.getId(), Colls.mapOf());
        initial.createPropertyIndex("Person", "name");
        GraphCommit base = initial.commit("seed", "base");
        repository.createBranch("feature", base.getId());

        // ours：删 bob（级联删 KNOWS）+ 删索引定义。
        GraphWriteTransaction mainWrite = repository.beginWrite("main");
        mainWrite.removeNode(bob.getId());
        mainWrite.dropPropertyIndex("Person", "name");
        mainWrite.commit("main", "prune bob and index");

        // theirs：加节点 + 新 tag 声明。
        GraphWriteTransaction featureWrite = repository.beginWrite("feature");
        featureWrite.addNode("Person", Colls.mapOf("name", "Carol"));
        featureWrite.createTag(new TagSchema("City", Colls.listOf(
                new TagSchema.Field("name", TagSchema.DataType.STRING, false))));
        featureWrite.commit("feature", "carol and city tag");

        GraphMergeResult merge = repository.merge("main", "feature", "maintainer", "merge deletions");
        assertTrue(merge.isMerged());
        GraphStore mergedView = repository.checkoutBranch("main").getStore();
        assertNull(mergedView.getNode(bob.getId()), "ours 侧的删除必须传播进合并结果");
        assertEquals(0, mergedView.getEdgeCount(), "级联删除的边也必须传播");
        assertEquals(2, mergedView.getNodeCount());
        assertFalse(mergedView.hasPropertyIndex("Person", "name"), "索引定义的删除必须传播");
        assertNotNull(mergedView.getTagSchema("City"), "theirs 侧的 tag 声明必须传播");
        assertEquals(2, merge.getCommit().getNodeCount());
        assertEquals(0, merge.getCommit().getEdgeCount());

        // base commit 的时间旅行不受合并影响。
        GraphStore baseView = repository.checkout(base.getId()).getStore();
        assertNotNull(baseView.getNode(bob.getId()));
        assertEquals(1, baseView.getEdgeCount());
        assertTrue(baseView.hasPropertyIndex("Person", "name"));
    }

    @Test
    void modifyDeleteConflictBlocksMerge() {
        GraphVersionStore repository = new GraphVersionStore();
        GraphWriteTransaction initial = repository.beginWrite("main");
        Node alice = initial.addNode("Person", Colls.mapOf("name", "Alice", "age", 30));
        GraphCommit base = initial.commit("seed", "base");
        repository.createBranch("feature", base.getId());
        long aliceId = alice.getId();

        GraphWriteTransaction mainWrite = repository.beginWrite("main");
        mainWrite.updateNode(aliceId, Colls.mapOf("age", 31));
        GraphCommit mainHead = mainWrite.commit("main", "update age");

        GraphWriteTransaction featureWrite = repository.beginWrite("feature");
        featureWrite.removeNode(aliceId);
        featureWrite.commit("feature", "delete alice");

        GraphMergeResult merge = repository.merge("main", "feature", "maintainer", "conflicting merge");
        assertFalse(merge.isMerged());
        assertTrue(merge.hasConflicts());
        assertTrue(merge.getConflicts().stream().anyMatch(c -> c.startsWith("node:" + aliceId)),
                "modify/delete 必须以该节点为冲突主体: " + merge.getConflicts());
        assertEquals(mainHead.getId(), repository.getBranchHead("main").getId());
        assertNull(merge.getCommit());
    }
}
