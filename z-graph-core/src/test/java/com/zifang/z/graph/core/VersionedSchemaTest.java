package com.zifang.z.graph.core;

import com.zifang.z.graph.api.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖：
 * <ul>
 *     <li>TagSchema / EdgeTypeSchema 通过 {@link GraphVersionStore} 的 commit / branch 流转；</li>
 *     <li>进程重启（重新打开 repository.bin）后能恢复 schema 与数据；</li>
 *     <li>并发写事务下的 head 推进不出现静默覆盖。</li>
 * </ul>
 */
class VersionedSchemaTest {

    @TempDir
    Path dataDir;

    // ==================== Schema 随 commit 流转 ====================

    @Test
    void schemasTravelThroughCommitAndBranch() {
        GraphVersionStore repository = new GraphVersionStore(dataDir);
        GraphWriteTransaction tx = repository.beginWrite("main");
        tx.createTag(new TagSchema("Person", List.of(
                new TagSchema.Field("name", TagSchema.DataType.STRING, false))));
        tx.addNode("Person", Map.of("name", "Alice"));
        GraphCommit first = tx.commit("alice", "init schema");

        // 在 main 上查看 schema
        GraphCheckout view = repository.checkout(first.getId());
        assertNotNull(view.getTagSchema("Person"));
        assertEquals(1, view.getNodeCount());

        // 从 first 切出 feature 分支，再添加 EdgeType schema
        repository.createBranch("feature", first.getId());
        GraphWriteTransaction feature = repository.beginWrite("feature");
        feature.createEdgeType(new EdgeTypeSchema("KNOWS", List.of(
                new TagSchema.Field("since", TagSchema.DataType.INT))));
        feature.addEdge("KNOWS", 0L, 0L, Map.of("since", 2020));
        GraphCommit second = feature.commit("bob", "extend schema");

        // feature 分支上的快照同时包含 tag 和 edgeType schema
        GraphCheckout featureView = repository.checkout(second.getId());
        assertNotNull(featureView.getTagSchema("Person"));
        assertNotNull(featureView.getEdgeTypeSchema("KNOWS"));

        // main 分支不感知 EdgeType schema（不同 commit 互不污染）
        GraphCheckout mainView = repository.checkout(first.getId());
        assertNotNull(mainView.getTagSchema("Person"));
        assertNull(mainView.getEdgeTypeSchema("KNOWS"));
    }

    @Test
    void schemasSurviveRepositoryReload() throws Exception {
        GraphVersionStore repository = new GraphVersionStore(dataDir);
        GraphWriteTransaction tx = repository.beginWrite("main");
        tx.createTag(new TagSchema("Person", List.of(
                new TagSchema.Field("name", TagSchema.DataType.STRING, false))));
        tx.addNode("Person", Map.of("name", "Alice"));
        GraphCommit first = tx.commit("alice", "init");

        // 重新打开仓库，验证 schema 恢复
        GraphVersionStore reloaded = new GraphVersionStore(dataDir);
        assertEquals("main", String.valueOf(reloaded.listBranches().get(0)));
        GraphCheckout checkout = reloaded.checkout(first.getId());
        assertNotNull(checkout.getTagSchema("Person"));
        assertEquals(1, checkout.getNodeCount());
        // 写入 schema 不一致的节点需要从新事务上发起：checkOut 不可写
        assertThrows(TagSchema.SchemaViolationException.class, () -> {
            GraphWriteTransaction tx2 = reloaded.beginWrite("main");
            tx2.addNode("Person", Map.of("name", "Bob", "extra", 1));
        });
    }

    // ==================== 并发写事务 ====================

    @Test
    void concurrentCommitsCannotSilentlyOverwrite() throws Exception {
        GraphVersionStore repository = new GraphVersionStore(dataDir);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++) {
            final int threadIdx = i;
            futures.add(executor.submit(() -> {
                try {
                    GraphWriteTransaction tx = repository.beginWrite("main");
                    tx.addNode("Person", Map.of("name", "T" + threadIdx));
                    tx.commit("user-" + threadIdx, "concurrent #" + threadIdx);
                    successCount.incrementAndGet();
                } catch (GraphVersionStore.StaleHeadException expected) {
                    conflictCount.incrementAndGet();
                }
            }));
        }
        for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertTrue(successCount.get() >= 1, "at least one commit should succeed");
        assertEquals(16, successCount.get() + conflictCount.get());
        // 持久化的最终 commit 数量应当与成功 commit 数一致
        List<GraphCommit> persisted = new GraphVersionStore(dataDir).listCommits();
        // commits 列表里至少包含一个 root + 成功的若干 commit
        assertTrue(persisted.size() >= successCount.get());
    }
}
