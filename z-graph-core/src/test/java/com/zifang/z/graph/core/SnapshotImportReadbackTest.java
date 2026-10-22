package com.zifang.z.graph.core;

import com.zifang.z.graph.api.GraphCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-20261012-027 演练第二步：<b>回读校验</b>。
 *
 * <p>输入是「z-oss 备份链里滚动保留后取回的那一份快照」（由 z-oss 的
 * {@code BackupChainService} 归档 + 回读产出），不是原始导出文件 ——
 * 只有这样才证明<b>备份链</b>真的可用，而不只是导出可用。</p>
 *
 * <p>校验口径：把回读快照 {@code importSnapshot} 进一个<b>全新的</b>仓库，
 * 再把导入后的视图与导出时写下的内容清单（{@code expected-content.txt}）逐行对比。
 * 「文件在不在」不算校验通过。</p>
 */
class SnapshotImportReadbackTest {

    @Test
    void importRestoredSnapshotYieldsIdenticalContent(@TempDir Path dataDir) throws IOException {
        Path dir = GraphMaintenanceRehearsalTest.rehearsalDir();
        Path restored = Paths.get(System.getProperty(
                "zgraph.rehearsal.restored", dir.resolve("restored.snapshot").toString()));
        Path manifest = dir.resolve("expected-content.txt");

        assertTrue(Files.exists(restored),
                "z-oss 备份链回读出来的快照必须存在: " + restored);
        assertTrue(Files.exists(manifest),
                "导出时写下的内容清单必须存在: " + manifest);

        GraphVersionStore fresh = new GraphVersionStore(dataDir);
        GraphCommit imported = fresh.importSnapshot(restored, "main", "restore",
                "restore from z-oss backup");
        assertNotNull(imported, "importSnapshot 必须产出一个新 commit");

        String expected = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
        String actual = GraphMaintenanceRehearsalTest.contentManifest(fresh.checkoutBranch("main"));
        assertEquals(expected, actual, "回读后的内容必须与导出时完全一致");

        // 导入后的视图可以再导出一次 ⇒ 产物可回放（备份链闭环）
        GraphMaintenanceService maintenance = new GraphMaintenanceService(fresh);
        GraphMaintenanceService.SnapshotArtifact replay =
                maintenance.exportSnapshot("main", dir);
        assertTrue(Files.exists(replay.getPath()));
        assertTrue(replay.getBytes() > 0);
    }
}
