package com.zifang.z.graph.core;

import com.zifang.z.graph.api.GraphCommit;
import com.zifang.z.graph.api.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.zifang.z.graph.api.Colls;

/** v4 → v5 迁移：手工构造 v4 布局，open 必须无损搬到版本链引擎并保留 legacyId 反查。 */
class LegacyMigrationTest {

    private static final String LEGACY_ROOT = "legacyroot000000000000000000000000000000001";
    private static final String LEGACY_SEED = "legacyseed000000000000000000000000000000001";

    @TempDir
    Path directory;

    @Test
    void v4RepositoryMigratesLosslesslyAndKeepsLegacyIdLookup() throws Exception {
        writeV4Repository();
        assertFalse(Files.exists(directory.resolve("store").resolve("header.bin")));

        GraphVersionStore repository = new GraphVersionStore(directory);

        // v5 布局成立，v4 源归档不删。
        assertTrue(Files.exists(directory.resolve("store").resolve("header.bin")));
        assertTrue(Files.exists(directory.resolve("objects.v4.bak")));
        assertTrue(Files.exists(directory.resolve("repository.v4.bak")));
        assertFalse(Files.exists(directory.resolve("objects")));

        // 分支与读值无损：head 视图有迁移来的节点。
        assertEquals(Colls.listOf("main"), repository.listBranches());
        assertEquals(1, repository.checkoutBranch("main").getStore().getNodeCount());
        Node alice = repository.checkoutBranch("main").getStore().getAllNodes().get(0);
        assertEquals(7L, alice.getId());
        assertEquals("Alice", alice.getProperties().get("name"));

        // 历史 commit id 全变，但旧 id 按 legacyId 反查仍然可打开。
        GraphCommit migratedSeed = repository.getCommit(LEGACY_SEED);
        assertFalse(migratedSeed.getId().equals(LEGACY_SEED));
        assertEquals(1, migratedSeed.getNodeCount());
        assertEquals(0, repository.checkout(LEGACY_ROOT).getStore().getNodeCount(),
                "根 commit 视图应为空图，顺带守卫 legacyId 反查路径");

        // commit 图完整：根 + seed，父子关系保持。
        assertEquals(2, repository.listCommits().size());
        assertEquals(2, repository.log("main").size());

        // 分配器水位保留：新节点 id 不与已删除历史 id 撞车，序号接着 v4 走。
        assertEquals(3L, repository.versionStats().get("nextCommitSeq"));
        GraphWriteTransaction write = repository.beginWrite("main");
        Node fresh = write.addNode("Person", Colls.mapOf("name", "Post-migration"));
        write.commit("post", "after migration");
        assertEquals(11L, fresh.getId(), "v4 头 nextNodeId=11 必须被继承");

        // reopen 幂等：不再迁移，head 稳定。
        GraphVersionStore reopened = new GraphVersionStore(directory);
        assertEquals(repository.getBranchHead("main").getId(), reopened.getBranchHead("main").getId());
        assertEquals(2, reopened.checkoutBranch("main").getStore().getNodeCount());
        assertEquals(3, reopened.listCommits().size());
    }

    @Test
    void preV4RepositoryIsRejectedWithClearMessage() throws Exception {
        try (OutputStream output = Files.newOutputStream(directory.resolve("repository.bin"))) {
            java.io.DataOutputStream out = new java.io.DataOutputStream(output);
            out.writeInt(GraphCodec.STORAGE_MAGIC);
            out.writeInt(3);
            out.writeLong(0L);
        }
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new GraphVersionStore(directory));
        assertTrue(e.getCause() instanceof java.io.IOException);
        assertTrue(e.getCause().getMessage().contains("cannot be migrated"),
                "v2/v3 仓库必须给出明确拒绝信息: " + e.getCause().getMessage());
    }

    /** 按 v4 持久化格式逐字节写一个双 commit 仓库（根 + 一个节点）。 */
    private void writeV4Repository() throws Exception {
        Files.createDirectories(directory.resolve("objects"));

        GraphDelta rootDelta = new GraphDelta();
        writeV4CommitObject(LEGACY_ROOT, Collections.emptyList(), "Initial graph", 0, 0, 1L, rootDelta);

        GraphDelta seedDelta = new GraphDelta();
        seedDelta.putNode(new Node(7L, Collections.singletonList("Person"),
                Colls.mapOf("name", "Alice", "age", 30)));
        writeV4CommitObject(LEGACY_SEED, Collections.singletonList(LEGACY_ROOT), "seed", 1, 0, 2L,
                seedDelta.freeze());

        try (OutputStream output = Files.newOutputStream(directory.resolve("repository.bin"))) {
            java.io.DataOutputStream out = new java.io.DataOutputStream(output);
            out.writeInt(GraphCodec.STORAGE_MAGIC);
            out.writeInt(4);
            out.writeLong(2L);   // sequence
            out.writeLong(11L);  // nextNodeId
            out.writeLong(5L);   // nextEdgeId
            out.writeInt(1);
            GraphCodec.writeString(out, "main");
            GraphCodec.writeString(out, LEGACY_SEED);
            out.writeInt(2);
            GraphCodec.writeString(out, LEGACY_ROOT);
            GraphCodec.writeString(out, LEGACY_SEED);
        }
    }

    /** v4 commit object：magic | 4 | id | parents | branch | author | message | ts | n | e | seq | delta。 */
    private void writeV4CommitObject(String id, java.util.List<String> parents, String message,
                                     long nodeCount, long edgeCount, long sequence,
                                     GraphDelta delta) throws Exception {
        Path object = directory.resolve("objects").resolve(id + ".bin");
        try (OutputStream output = Files.newOutputStream(object)) {
            java.io.DataOutputStream out = new java.io.DataOutputStream(output);
            out.writeInt(GraphCodec.STORAGE_MAGIC);
            out.writeInt(4);
            GraphCodec.writeString(out, id);
            out.writeInt(parents.size());
            for (String parent : parents) {
                GraphCodec.writeString(out, parent);
            }
            GraphCodec.writeString(out, "main");
            GraphCodec.writeString(out, "system");
            GraphCodec.writeString(out, message);
            out.writeLong(1700000000000L);
            out.writeLong(nodeCount);
            out.writeLong(edgeCount);
            out.writeLong(sequence);
            delta.writeTo(out);
        }
        assertNotNull(object);
    }
}
