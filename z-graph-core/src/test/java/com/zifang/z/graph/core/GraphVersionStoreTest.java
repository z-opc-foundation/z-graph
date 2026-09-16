package com.zifang.z.graph.core;

import com.zifang.z.graph.api.GraphCommit;
import com.zifang.z.graph.api.GraphMergeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GraphVersionStore 的 commit、分支、checkout、merge 语义测试。 */
class GraphVersionStoreTest {

    @Test
    void commitKeepsPreviousSnapshotIsolated() {
        GraphVersionStore repository = new GraphVersionStore();

        GraphWriteTransaction firstWrite = repository.beginWrite("main");
        firstWrite.addNode("Person", Map.of("name", "Alice"));
        GraphCommit first = firstWrite.commit("alice", "add Alice");

        GraphWriteTransaction secondWrite = repository.beginWrite("main");
        secondWrite.addNode("Person", Map.of("name", "Bob"));
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
        initialWrite.addNode("Person", Map.of("name", "Alice"));
        GraphCommit base = initialWrite.commit("alice", "base graph");

        repository.createBranch("feature", base.getId());

        GraphWriteTransaction mainWrite = repository.beginWrite("main");
        mainWrite.addNode("Person", Map.of("name", "Bob"));
        mainWrite.commit("bob", "main change");

        GraphWriteTransaction featureWrite = repository.beginWrite("feature");
        featureWrite.addNode("Person", Map.of("name", "Carol"));
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
        write.addNode("Person", Map.of("name", "Alice"));
        GraphCommit oldHead = write.commit("alice", "old");

        assertThrows(UnsupportedOperationException.class,
                () -> repository.checkout(oldHead.getId()).getStore()
                        .addNode("Person", Map.of("name", "cannot write")));

        GraphWriteTransaction nextWrite = repository.beginWrite("main");
        nextWrite.addNode("Person", Map.of("name", "Bob"));
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
        write.addNode("Person", Map.of("name", "Persistent Alice", "age", 30,
                "tags", List.of("graph", "storage"), "profile", Map.of("city", "Beijing")));
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
        assertEquals(List.of("graph", "storage"), reopened.checkout(commit.getId()).getStore().getNode(nodeId).get("tags"));
        assertEquals(Map.of("city", "Beijing"), reopened.checkout(commit.getId()).getStore().getNode(nodeId).get("profile"));
    }

    @Test
    void independentPropertyChangesAreMerged() {
        GraphVersionStore repository = new GraphVersionStore();
        GraphWriteTransaction initialWrite = repository.beginWrite("main");
        initialWrite.addNode("Person", Map.of("name", "Alice", "age", 30));
        GraphCommit base = initialWrite.commit("alice", "base");
        long nodeId = repository.checkout(base.getId()).getStore().getAllNodes().get(0).getId();
        repository.createBranch("feature", base.getId());

        GraphWriteTransaction mainWrite = repository.beginWrite("main");
        mainWrite.updateNode(nodeId, Map.of("city", "Beijing"));
        mainWrite.commit("main", "main property");

        GraphWriteTransaction featureWrite = repository.beginWrite("feature");
        featureWrite.updateNode(nodeId, Map.of("age", 31));
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
        initialWrite.addNode("Person", Map.of("name", "Alice", "age", 30));
        GraphCommit base = initialWrite.commit("alice", "base");
        long nodeId = repository.checkout(base.getId()).getStore().getAllNodes().get(0).getId();
        repository.createBranch("feature", base.getId());

        GraphWriteTransaction mainWrite = repository.beginWrite("main");
        mainWrite.updateNode(nodeId, Map.of("age", 31));
        GraphCommit mainHead = mainWrite.commit("main", "main age");

        GraphWriteTransaction featureWrite = repository.beginWrite("feature");
        featureWrite.updateNode(nodeId, Map.of("age", 32));
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
        write.addNode("Person", Map.of("name", "Alice"));
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
        winner.addNode("Person", Map.of("name", "winner"));
        winner.commit("winner", "advance branch");

        stale.addNode("Person", Map.of("name", "stale"));
        assertThrows(IllegalStateException.class, () -> stale.commit("stale", "must fail"));
    }
}
