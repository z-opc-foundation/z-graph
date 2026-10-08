package com.zifang.z.graph.core;

import com.zifang.z.graph.api.Colls;
import com.zifang.z.graph.api.GraphCommit;
import com.zifang.z.graph.core.storage.CommitObjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * commit id 内容寻址回归闸：id = 整个 commit object 规范化字节的 SHA-256。
 * 重开仓读同一字节 ⇒ id 稳定；篡改字节 ⇒ 哈希校验必炸；任何元数据变化 ⇒ 新 id。
 */
class ContentHashTest {

    @Test
    void reopenKeepsCommitIdsByteStable(@TempDir Path dir) {
        GraphVersionStore repository = new GraphVersionStore(dir);
        GraphWriteTransaction write = repository.beginWrite("main");
        write.addNode("Person", Colls.mapOf("name", "Alice"));
        GraphCommit committed = write.commit("alice", "stable");
        GraphCommit base = repository.log("main").get(1);

        GraphVersionStore reopened = new GraphVersionStore(dir);
        assertEquals(committed.getId(), reopened.getBranchHead("main").getId());
        assertEquals(base.getId(), reopened.log("main").get(1).getId());
        // 校验路径是真的：reopen 时每个 commit object 都按内容重算哈希比对文件名。
        assertEquals(2, reopened.listCommits().size());
    }

    @Test
    void tamperedCommitObjectFailsHashCheck(@TempDir Path dir) throws IOException {
        GraphVersionStore repository = new GraphVersionStore(dir);
        GraphWriteTransaction write = repository.beginWrite("main");
        write.addNode("Person", Colls.mapOf("name", "Alice"));
        String id = write.commit("alice", "tamper me").getId();

        Path object = dir.resolve("commits").resolve(id + ".bin");
        byte[] bytes = Files.readAllBytes(object);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(object, bytes);

        CommitObjectStore store = new CommitObjectStore(dir.resolve("commits"));
        assertThrows(IOException.class, () -> store.read(id), "内容哈希校验必须拦住篡改");
        // 整仓 open 也必须拒绝被篡改的仓库，而不是静默读到假历史。
        assertThrows(IllegalStateException.class, () -> new GraphVersionStore(dir));
    }

    @Test
    void identicalCanonicalBytesDedupToOneObject(@TempDir Path dir) throws IOException {
        CommitObjectStore store = new CommitObjectStore(dir.resolve("commits"));
        byte[] canonical = CommitObjectStore.canonicalBytes(Collections.singletonList("aa"), "main",
                "alice", "same", 42L, 1L, 0L, 7L, null, new GraphDelta().freeze());
        String first = store.writeCanonical(canonical);
        String second = store.writeCanonical(canonical);
        assertEquals(first, second);
        assertEquals(1, countObjects(dir.resolve("commits")));
    }

    @Test
    void metadataChangeProducesNewCommitId() {
        GraphVersionStore repository = new GraphVersionStore();
        GraphWriteTransaction writeA = repository.beginWrite("main");
        writeA.addNode("Person", Colls.mapOf("name", "Alice"));
        GraphCommit a = writeA.commit("alice", "message A");

        GraphVersionStore forked = new GraphVersionStore();
        GraphWriteTransaction writeB = forked.beginWrite("main");
        writeB.addNode("Person", Colls.mapOf("name", "Alice"));
        GraphCommit b = writeB.commit("alice", "message B");
        assertNotEquals(a.getId(), b.getId(), "message 不同 ⇒ id 不同");

        GraphVersionStore third = new GraphVersionStore();
        GraphWriteTransaction writeC = third.beginWrite("main");
        writeC.addNode("Person", Colls.mapOf("name", "Alice"));
        GraphCommit c = writeC.commit("bob", "message A");
        assertNotEquals(c.getId(), a.getId(), "author 不同 ⇒ id 不同");
        assertTrue(a.getId().matches("[0-9a-f]{40}"));
    }

    private static int countObjects(Path dir) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            return (int) files.filter(path -> path.getFileName().toString().endsWith(".bin")).count();
        }
    }
}
