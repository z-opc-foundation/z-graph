package com.zifang.z.graph.core;

import com.zifang.z.graph.api.GraphCommit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 运维面：版本链 <b>GC 回收</b> + <b>快照导出</b>。
 *
 * <p>引擎本体（{@link GraphVersionStore}）只提供两个裸 API：{@code garbageCollect(String...)}
 * 与 {@code exportSnapshot(String, Path)}。本类把它们包成<b>可观测</b>的运维动作：
 * 每次动作都留下计数、时间戳与最后一错，失败一律抛出去并记 ERROR 日志 ——
 * 静默吞掉的 GC/快照失败在生产里表现为"磁盘一直涨、备份一直没生成"，最难查。</p>
 *
 * <p><b>可达性口径以 {@link GraphVersionStore#garbageCollect(String...)} 的实现为准</b>，
 * 配套文档见 {@code _doc/002_deploy/01-gc-reachability.md}。本类**不重算**可达集，
 * 只如实报告引擎给出的回收数与前后计数。</p>
 */
public final class GraphMaintenanceService {

    private static final Logger log = LogManager.getLogger(GraphMaintenanceService.class);

    private final GraphVersionStore store;

    private final AtomicLong gcRuns = new AtomicLong();
    private final AtomicLong gcCollectedCommits = new AtomicLong();
    private final AtomicLong gcFailures = new AtomicLong();

    private final AtomicLong snapshotAttempts = new AtomicLong();
    private final AtomicLong snapshotExports = new AtomicLong();
    private final AtomicLong snapshotFailures = new AtomicLong();

    private volatile long lastGcAtMillis = -1L;
    private volatile long lastSnapshotAtMillis = -1L;
    private volatile String lastError;

    public GraphMaintenanceService(GraphVersionStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    // ==================== GC ====================

    /**
     * 跑一轮 GC。
     *
     * @param keepBranches 要保留的分支名；<b>为空 ⇒ 保留当前全部分支</b>（引擎默认口径）。
     *                     非空 ⇒ <b>只有这些分支</b>受保护：未列出的分支会被剪掉、
     *                     它们的历史 commit 会被回收。误删风险最高的参数就是它。
     * @return 本轮回收结果（前后计数、剪掉的分支、耗时）
     */
    public GcOutcome collectGarbage(String... keepBranches) {
        gcRuns.incrementAndGet();
        long start = System.currentTimeMillis();
        int commitsBefore = store.listCommits().size();
        List<String> branchesBefore = new ArrayList<>(store.listBranches());
        List<String> keep = keepBranches == null || keepBranches.length == 0
                ? Collections.<String>emptyList()
                : new ArrayList<>(java.util.Arrays.asList(keepBranches));
        try {
            int collected = store.garbageCollect(keepBranches);
            gcCollectedCommits.addAndGet(collected);
            lastGcAtMillis = System.currentTimeMillis();
            GcOutcome outcome = new GcOutcome(collected, commitsBefore, store.listCommits().size(),
                    branchesBefore, new ArrayList<>(store.listBranches()), keep,
                    System.currentTimeMillis() - start);
            log.info("z-graph GC 完成: 回收 commit={}，commit 数 {} -> {}，分支 {} -> {}，耗时 {}ms",
                    collected, commitsBefore, outcome.getCommitsAfter(),
                    branchesBefore, outcome.getBranchesAfter(), outcome.getDurationMs());
            return outcome;
        } catch (RuntimeException e) {
            gcFailures.incrementAndGet();
            lastError = "gc failed: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("z-graph GC 失败（不静默）: keep={}，异常={}", keep, e.getMessage(), e);
            throw e;
        }
    }

    // ==================== 快照 ====================

    /**
     * 把指定分支的 head 视图导出为独立快照文件。
     *
     * @param branch    源分支（默认调用方给 "main"）
     * @param targetDir 落盘目录（不存在会创建）
     * @return 快照产物描述（路径、commitId、字节数、sha256）
     */
    public SnapshotArtifact exportSnapshot(String branch, Path targetDir) throws IOException {
        snapshotAttempts.incrementAndGet();
        try {
            GraphCommit head = store.getBranchHead(branch);
            Path dir = targetDir == null ? store.getStorageDirectory().resolve("snapshots") : targetDir;
            Files.createDirectories(dir);
            String stamp = java.time.format.DateTimeFormatter
                    .ofPattern("yyyyMMdd-HHmmss")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(java.time.Instant.ofEpochMilli(System.currentTimeMillis()));
            Path target = dir.resolve("zgraph-" + sanitize(branch) + "-" + stamp + ".snapshot");
            store.exportSnapshot(head.getId(), target);
            long size = Files.size(target);
            String digest = sha256(target);
            snapshotExports.incrementAndGet();
            lastSnapshotAtMillis = System.currentTimeMillis();
            SnapshotArtifact artifact = new SnapshotArtifact(branch, head.getId(), target,
                    size, digest, lastSnapshotAtMillis);
            log.info("z-graph 快照导出完成: branch={}，commit={}，文件={}，字节={}，sha256={}",
                    branch, head.getId(), target, size, digest);
            return artifact;
        } catch (IOException | RuntimeException e) {
            snapshotFailures.incrementAndGet();
            lastError = "snapshot failed: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("z-graph 快照导出失败（不静默）: branch={}，异常={}", branch, e.getMessage(), e);
            throw e;
        }
    }

    // ==================== 观测 ====================

    /** 运维计数（给 /meta/metrics 与 z-observer 留的接口）。 */
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("gcRuns", gcRuns.get());
        stats.put("gcCollectedCommits", gcCollectedCommits.get());
        stats.put("gcFailures", gcFailures.get());
        stats.put("snapshotAttempts", snapshotAttempts.get());
        stats.put("snapshotExports", snapshotExports.get());
        stats.put("snapshotFailures", snapshotFailures.get());
        stats.put("lastGcAtMillis", lastGcAtMillis);
        stats.put("lastSnapshotAtMillis", lastSnapshotAtMillis);
        stats.put("lastError", lastError);
        return stats;
    }

    private static String sanitize(String branch) {
        return branch == null ? "main" : branch.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** 文件 sha256（十六进制小写）——回读校验用得上：字节一致 ⇒ 摘要必然一致。 */
    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ==================== 值对象 ====================

    /** 一轮 GC 的结果。 */
    public static final class GcOutcome {

        private final int collected;
        private final int commitsBefore;
        private final int commitsAfter;
        private final List<String> branchesBefore;
        private final List<String> branchesAfter;
        private final List<String> keepBranches;
        private final long durationMs;

        GcOutcome(int collected, int commitsBefore, int commitsAfter,
                  List<String> branchesBefore, List<String> branchesAfter,
                  List<String> keepBranches, long durationMs) {
            this.collected = collected;
            this.commitsBefore = commitsBefore;
            this.commitsAfter = commitsAfter;
            this.branchesBefore = Collections.unmodifiableList(branchesBefore);
            this.branchesAfter = Collections.unmodifiableList(branchesAfter);
            this.keepBranches = Collections.unmodifiableList(keepBranches);
            this.durationMs = durationMs;
        }

        /** 被回收的 commit 数（引擎给的权威值）。 */
        public int getCollected() {
            return collected;
        }

        public int getCommitsBefore() {
            return commitsBefore;
        }

        public int getCommitsAfter() {
            return commitsAfter;
        }

        public List<String> getBranchesBefore() {
            return branchesBefore;
        }

        /** GC 后仍然存在的分支（被剪掉的分支不在这里）。 */
        public List<String> getBranchesAfter() {
            return branchesAfter;
        }

        /** 本轮显式指定的保留分支；空 ⇒ 保留全部分支。 */
        public List<String> getKeepBranches() {
            return keepBranches;
        }

        public long getDurationMs() {
            return durationMs;
        }
    }

    /** 一次快照导出的产物描述。 */
    public static final class SnapshotArtifact {

        private final String branch;
        private final String commitId;
        private final Path path;
        private final long bytes;
        private final String sha256;
        private final long exportedAtMillis;

        SnapshotArtifact(String branch, String commitId, Path path, long bytes,
                         String sha256, long exportedAtMillis) {
            this.branch = branch;
            this.commitId = commitId;
            this.path = path;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.exportedAtMillis = exportedAtMillis;
        }

        public String getBranch() {
            return branch;
        }

        public String getCommitId() {
            return commitId;
        }

        public Path getPath() {
            return path;
        }

        public long getBytes() {
            return bytes;
        }

        public String getSha256() {
            return sha256;
        }

        public long getExportedAtMillis() {
            return exportedAtMillis;
        }
    }
}
