package com.zifang.z.graph.core;

import com.zifang.z.graph.api.Colls;
import com.zifang.z.graph.api.GraphCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 验收：显式配置 data-dir ⇒ 数据落指定目录，且**进程重启后仍在**。
 *
 * <p>「重启」在本测试里等于**新建一个 {@link GraphVersionStore} 实例指向同一目录**
 * —— 引擎是磁盘追加式的，实例不共享内存态，所以新实例读到的只能来自磁盘。
 * 这正是真实重启（进程退出 → 重新起进程指向同一目录）的等价物。</p>
 */
class GraphVersionStoreDataDirRecoveryTest {

    @TempDir
    Path dataDir;

    @Test
    void explicitDirectoryKeepsDataAcrossRestart() {
        // ---- 第一次运行：写一条数据 ----
        GraphVersionStore first = new GraphVersionStore(dataDir);
        assertEquals(dataDir.toAbsolutePath(), first.getStorageDirectory().toAbsolutePath(),
                "显式给的目录必须被用上");
        GraphWriteTransaction tx = first.beginWrite("main");
        tx.addNode("Person", Colls.mapOf("name", "Persisted Alice"));
        GraphCommit commit = tx.commit("alice", "durable write");

        // 目录布局：v5 = store/ + commits/ + refs/
        assertTrue(Files.isDirectory(dataDir.resolve("store")), "缺 store/：" + dataDir);
        assertTrue(Files.isDirectory(dataDir.resolve("commits")), "缺 commits/：" + dataDir);

        // ---- 模拟进程重启：全新实例指向同一目录 ----
        GraphVersionStore restarted = new GraphVersionStore(dataDir);
        assertEquals(dataDir.toAbsolutePath(), restarted.getStorageDirectory().toAbsolutePath());

        // 重启后仍能读到那条 commit 与它带来的节点
        GraphCheckout view = restarted.checkout(commit.getId());
        assertNotNull(view, "重启后按 commit id 读不到视图");
        assertEquals(1L, view.getNodeCount(), "重启后节点丢了 ⇒ 数据没落盘或没恢复");
        assertTrue(restarted.listCommits().size() >= 2,
                "重启后 commit 链不完整：" + restarted.listCommits().size());
    }

    /** 反向钉：没给目录时**不得**把数据写进调用方指定的目录（它落进程临时目录）。 */
    @Test
    void withoutExplicitDirectoryNothingLandsInTheGivenDirectory() throws Exception {
        GraphVersionStore ephemeral = new GraphVersionStore();
        GraphWriteTransaction tx = ephemeral.beginWrite("main");
        tx.addNode("Person", Colls.mapOf("name", "Ephemeral Bob"));
        tx.commit("bob", "ephemeral write");

        assertFalse(Files.isDirectory(dataDir.resolve("store")),
                "没给目录却往调用方目录里写了东西");
        // 临时目录确实存在（引擎是磁盘追加式的，临时目录 ≠ 内存）
        assertTrue(Files.isDirectory(ephemeral.getStorageDirectory()),
                "无参构造应当落在某个临时目录上：" + ephemeral.getStorageDirectory());
    }
}
