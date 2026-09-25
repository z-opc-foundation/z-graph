package com.zifang.z.graph.bench;

import com.zifang.z.graph.api.GraphCommit;
import com.zifang.z.graph.api.Node;
import com.zifang.z.graph.core.GraphVersionStore;
import com.zifang.z.graph.core.GraphWriteTransaction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

/**
 * z-graph MVCC 压力／性能测试台。
 *
 * <p>不是"跑出数字给人看"的报告生成器：每条场景都会落一个可判红的 VERDICT，
 * 任一 verdict 失败进程以非 0 退出，因此可以被 CI 直接当门禁使用。</p>
 *
 * <p>旧方案（每个 commit 存一份整图副本）不会"删了就测不到"：
 * {@code checkpointInterval=1 + eagerCheckpoints} 让当前实现走出"每次提交物化一份
 * 全量视图"的成本曲线，等价于旧方案的写放大，所有 A/B 对比都跑在真实代码路径上。</p>
 *
 * <pre>
 * java -Xms1g -Xmx8g -cp target/classes:target/test-classes \
 *   com.zifang.z.graph.bench.MvccStressHarness --profile=full \
 *   --workdir=/tmp/zgraph-stress --json=/tmp/zgraph-stress/results.jsonl
 * </pre>
 */
public final class MvccStressHarness {

    /** smoke 用于本机快速自检，full 用于压测机。 */
    static class Config {
        int bulkNodes = 100_000;
        int bulkBatch = 500;
        int[] commitCurveSizes = {1_000, 10_000, 100_000, 200_000};
        int commitCurveProbes = 25;
        int travelGraphNodes = 20_000;
        int travelCommits = 2_000;
        int[] travelCheckpoints = {1, 8, 32, 128, 512};
        int[] travelDepths = {1, 10, 100, 1_000, 2_000};
        int memoryCommits = 500;
        int memoryGraphNodes = 50_000;
        int concurrentThreads = 8;
        int concurrentOps = 50;
        int persistCommits = 500;
        int persistGraphNodes = 20_000;
    }

    private final Config config;
    private final Path workdir;
    private final List<String> jsonLines = new ArrayList<>();
    private final Map<String, String> verdicts = new LinkedHashMap<>();
    private final long startedAt = System.nanoTime();
    private long travelMismatches;

    MvccStressHarness(Config config, Path workdir) {
        this.config = config;
        this.workdir = workdir;
    }

    public static void main(String[] args) throws Exception {
        Config config = new Config();
        boolean smoke = Arrays.asList(args).contains("--profile=smoke");
        if (smoke) {
            config.bulkNodes = 10_000;
            config.bulkBatch = 200;
            config.commitCurveSizes = new int[]{1_000, 5_000, 20_000, 50_000};
            config.commitCurveProbes = 15;
            config.travelGraphNodes = 2_000;
            config.travelCommits = 200;
            config.travelCheckpoints = new int[]{1, 16, 100};
            config.travelDepths = new int[]{1, 10, 100, 200};
            config.memoryCommits = 120;
            config.memoryGraphNodes = 10_000;
            config.concurrentThreads = 4;
            config.concurrentOps = 25;
            config.persistCommits = 120;
            config.persistGraphNodes = 2_000;
        }
        Path workdir = argValue(args, "--workdir", Path.of(System.getProperty("java.io.tmpdir"), "zgraph-stress"));
        Files.createDirectories(workdir);

        MvccStressHarness harness = new MvccStressHarness(config, workdir);
        int code = harness.runAll(smoke);
        Path json = argValue(args, "--json", null);
        if (json != null) {
            Files.write(json.toAbsolutePath(), harness.jsonLines);
            System.out.println("wrote " + harness.jsonLines.size() + " measurements -> " + json);
        } else {
            System.out.println(harness.jsonLines.size() + " measurements（未给 --json，仅 stdout）");
        }
        System.exit(code);
    }

    private static Path argValue(String[] args, String flag, Path fallback) {
        for (String arg : args) {
            if (arg.startsWith(flag + "=")) {
                return Path.of(arg.substring(flag.length() + 1));
            }
        }
        return fallback;
    }

    int runAll(boolean smoke) throws Exception {
        System.out.println("=== z-graph MVCC stress harness ===");
        System.out.printf("profile=%s java=%s cpus=%d maxHeap=%s%n",
                smoke ? "smoke" : "full",
                System.getProperty("java.version"),
                Runtime.getRuntime().availableProcessors(),
                human(Runtime.getRuntime().maxMemory()));
        System.out.println("workdir=" + workdir);

        long t0 = System.nanoTime();
        bulkLoad();
        commitLatencyCurve();
        timeTravelLatency();
        memoryFootprint();
        concurrency();
        persistence();
        versionGc();
        System.out.printf("%n全部场景耗时 %.1fs%n", (System.nanoTime() - t0) / 1e9);

        System.out.println("\n=== VERDICTS ===");
        int failed = 0;
        for (Map.Entry<String, String> entry : verdicts.entrySet()) {
            boolean pass = entry.getValue().startsWith("PASS");
            if (!pass) failed++;
            System.out.printf("[%s] %-30s %s%n", pass ? "ok" : "FAIL", entry.getKey(), entry.getValue());
        }
        System.out.printf("verdicts: %d/%d passed%n", verdicts.size() - failed, verdicts.size());
        return failed == 0 ? 0 : 1;
    }

    // ==================== S1 批量导入吞吐 ====================

    /**
     * 索引维护从"每次写全量重建"改成增量维护后，带索引的批量导入不能再退化成平方级；
     * 用同规模无索引吞吐作分母钉住它。
     */
    private void bulkLoad() throws Exception {
        System.out.println("\n--- S1 批量导入吞吐 (每节点 1 条入边) ---");
        long plain = bulkLoadOnce(false);
        long indexed = bulkLoadOnce(true);
        double ratio = indexed * 1.0 / plain;
        System.out.printf("  无索引 %,d ent/s | 带索引 %,d ent/s | 保留比 %.0f%%%n", plain, indexed, ratio * 100);
        record("s1_bulk", "unindexed", "entitiesPerSecond", plain);
        record("s1_bulk", "indexed", "entitiesPerSecond", indexed);
        // 旧实现是每写一次全量重建索引：10 万规模下带索引会慢一到两个数量级。
        verdict("s1_index_not_quadratic", ratio >= 0.5,
                String.format("indexed/unindexed = %.2f (需 >= 0.5)", ratio));
    }

    private long bulkLoadOnce(boolean withIndex) {
        GraphVersionStore repository = new GraphVersionStore();
        int nodes = config.bulkNodes;
        int batch = config.bulkBatch;
        long started = System.nanoTime();
        long entities = 0;
        GraphWriteTransaction write = repository.beginWrite("main");
        if (withIndex) {
            write.createPropertyIndex("Person", "name");
        }
        long previous = -1;
        for (int i = 0; i < nodes; i++) {
            Node node = write.addNode("Person", Map.of("name", "user-" + i, "age", 18 + (i % 60)));
            if (previous >= 0) {
                write.addEdge("KNOWS", previous, node.getId(), Map.of("since", 2000 + (i % 25)));
            }
            previous = node.getId();
            entities += 2;
            if ((i + 1) % batch == 0) {
                write.commit("loader", "batch " + (i / batch));
                write = repository.beginWrite("main");
            }
        }
        write.commit("loader", "tail");
        long micros = Math.max(1, (System.nanoTime() - started) / 1_000);
        long perSecond = entities * 1_000_000L / micros;
        System.out.printf("  %-9s nodes=%,d commits=%,d 用时 %.3fs -> %,d ent/s (heap %s)%n",
                withIndex ? "indexed" : "plain", nodes, nodes / batch + 1,
                micros / 1e6, perSecond, human(usedHeap()));
        if (repository.checkoutBranch("main").getNodeCount() != nodes) {
            verdict("s1_load_integrity", false, "导入后节点数与预期不符");
        }
        verdict("s1_load_integrity", true, "导入后节点数 == " + nodes);
        return perSecond;
    }

    // ==================== S2 提交成本 vs 图规模 ====================

    /**
     * 核心命题：一次提交的存储/写放大不能随图规模增长。同一台机器跑两条曲线，
     * delta 模式（默认检查点）对比 snapshot 模式（每个 commit 物化全量视图 = 旧方案成本）。
     * 事务整体（beginWrite+写+commit）单独计量，因为它包含视图摊平。
     */
    private void commitLatencyCurve() throws Exception {
        System.out.println("\n--- S2 提交成本 vs 图规模 ---");
        System.out.printf("  %10s %14s %14s %14s %14s%n",
                "nodes", "tx p50(us)", "tx p99(us)", "commit p50", "snapshot p50");
        long[] txP50 = new long[config.commitCurveSizes.length];
        long[] commitP50 = new long[config.commitCurveSizes.length];
        long[] snapshotP50 = new long[config.commitCurveSizes.length];
        for (int s = 0; s < config.commitCurveSizes.length; s++) {
            int size = config.commitCurveSizes[s];
            long[][] stats = commitLatencyAt(size);
            txP50[s] = stats[0][0];
            commitP50[s] = stats[1][0];
            snapshotP50[s] = stats[2][0];
            System.out.printf("  %10s %14s %14s %14s %14s%n", format(size),
                    format(stats[0][0]), format(stats[0][1]), format(stats[1][0]), format(stats[2][0]));
            record("s2_commit_latency", "delta_tx", "nodes", size);
            record("s2_commit_latency", "delta_tx", "p50Micros", stats[0][0]);
            record("s2_commit_latency", "delta_tx", "p99Micros", stats[0][1]);
            record("s2_commit_latency", "delta_commit", "p50Micros", stats[1][0]);
            record("s2_commit_latency", "delta_commit", "p99Micros", stats[1][1]);
            record("s2_commit_latency", "snapshot_tx", "p50Micros", stats[2][0]);
            record("s2_commit_latency", "snapshot_tx", "p99Micros", stats[2][1]);
        }
        int last = config.commitCurveSizes.length - 1;
        double txGrowth = txP50[last] * 1.0 / Math.max(1, txP50[0]);
        double commitGrowth = commitP50[last] * 1.0 / Math.max(1, commitP50[0]);
        double snapshotGrowth = snapshotP50[last] * 1.0 / Math.max(1, snapshotP50[0]);
        double costRatio = snapshotP50[last] * 1.0 / Math.max(1, txP50[last]);
        System.out.printf("  增长比(最小->最大规模): tx=%.1fx commit=%.1fx snapshot=%.1fx；"
                + "同规模 snapshot/tx=%.1fx%n", txGrowth, commitGrowth, snapshotGrowth, costRatio);
        verdict("s2_commit_cost_flat", commitGrowth <= 3.0,
                String.format("commit 调用成本随规模增长 %.1fx (需 <= 3x)", commitGrowth));
        verdict("s2_snapshot_more_expensive", costRatio >= 1.5,
                String.format("snapshot 模式 p50 应为 delta 的 >= 1.5x，实测 %.1fx", costRatio));
        verdict("s2_write_txn_flat", txGrowth <= 3.0,
                String.format("整事务(beginWrite+commit)随规模增长 %.1fx (需 <= 3x)；"
                        + "超出说明视图摊平仍在按图规模复制", txGrowth));
        verdict("s2_snapshot_growth_steeper", snapshotGrowth >= commitGrowth,
                String.format("snapshot 增长 %.1fx 应不陡于 delta 才可疑", snapshotGrowth));
    }

    /** 返回 {tx p50/p99, commitOnly p50/p99, snapshotTx p50/p99}，单位微秒。 */
    private long[][] commitLatencyAt(int graphNodes) {
        GraphVersionStore repository = new GraphVersionStore();
        loadGraph(repository, graphNodes, 500);
        long[] txSamples = new long[config.commitCurveProbes];
        long[] commitSamples = new long[config.commitCurveProbes];
        for (int i = 0; i < config.commitCurveProbes; i++) {
            long target = i % graphNodes;
            long started = System.nanoTime();
            GraphWriteTransaction write = repository.beginWrite("main");
            write.updateNode(target, Map.of("age", 30 + i));
            long beforeCommit = System.nanoTime();
            write.commit("probe", "probe " + i);
            long ended = System.nanoTime();
            txSamples[i] = (ended - started) / 1_000;
            commitSamples[i] = (ended - beforeCommit) / 1_000;
        }
        GraphVersionStore snapshot = oldDesignMode(graphNodes, 4);
        loadGraph(snapshot, graphNodes, 500);
        long[] snapshotSamples = new long[config.commitCurveProbes];
        for (int i = 0; i < config.commitCurveProbes; i++) {
            long target = i % graphNodes;
            long started = System.nanoTime();
            GraphWriteTransaction write = snapshot.beginWrite("main");
            write.updateNode(target, Map.of("age", 30 + i));
            write.commit("probe", "probe " + i);
            snapshotSamples[i] = (System.nanoTime() - started) / 1_000;
        }
        return new long[][]{percentile(txSamples), percentile(commitSamples), percentile(snapshotSamples)};
    }

    // ==================== S3 时间旅行读延迟 ====================

    private void timeTravelLatency() throws Exception {
        System.out.println("\n--- S3 历史 commit 视图读延迟 vs 检查点间隔 (p50/p99 微秒) ---");
        System.out.printf("  %8s", "interval");
        for (int depth : config.travelDepths) {
            System.out.printf("%16s", "d=" + depth);
        }
        System.out.printf("%10s %10s%n", "views", "replayBad");
        long mismatchesBefore = travelMismatches;
        for (int interval : config.travelCheckpoints) {
            GraphVersionStore repository = new GraphVersionStore()
                    .withCheckpointInterval(interval)
                    .withMaxRetainedViews(1)
                    .withEagerCheckpoints(false);
            loadGraph(repository, config.travelGraphNodes, 500);
            List<String> chain = new ArrayList<>();
            for (int i = 0; i < config.travelCommits; i++) {
                GraphWriteTransaction write = repository.beginWrite("main");
                write.addNode("Audit", Map.of("name", "none", "seq", i, "blob", "x" + (i % 97)));
                chain.add(write.commit("torture", "step " + i).getId());
            }
            System.out.printf("  %8d", interval);
            for (int depth : config.travelDepths) {
                int index = chain.size() - depth;
                if (index < 0) {
                    System.out.printf("%16s", "n/a");
                    continue;
                }
                String commitId = chain.get(index);
                long expectedNodes = config.travelGraphNodes + index + 1L;
                long[] samples = new long[9];
                for (int s = 0; s < samples.length; s++) {
                    long started = System.nanoTime();
                    long[] probed = openAndCount(repository, commitId);
                    samples[s] = (System.nanoTime() - started) / 1_000;
                    if (probed[0] != expectedNodes) {
                        travelMismatches++;
                    }
                }
                long[] stats = percentile(samples);
                System.out.printf("%16s", stats[0] + "/" + stats[1]);
                record("s3_travel", "interval" + interval, "depth" + depth + "p99Micros", stats[1]);
            }
            long views = stat(repository, "materializedViews");
            System.out.printf("%10d %10d%n", views, travelMismatches - mismatchesBefore);
            record("s3_travel", "interval" + interval, "materializedViews", views);
        }
        verdict("s3_replay_correct", travelMismatches == 0,
                String.format("采样 %d 个深度，回放视图节点数不符 %d 次",
                        config.travelCheckpoints.length * config.travelDepths.length, travelMismatches));
    }

    /** 打开历史视图并真的读取内容，返回 {节点数, 属性校验和}。 */
    private long[] openAndCount(GraphVersionStore repository, String commitId) {
        var checkout = repository.checkout(commitId);
        long count = 0;
        long checksum = 0;
        for (Node node : checkout.getStore().getAllNodes()) {
            count++;
            Object seq = node.get("seq");
            checksum += seq instanceof Number ? ((Number) seq).longValue() : node.get("name").hashCode();
        }
        return new long[]{count, checksum};
    }

    /**
     * 旧方案等价配置（时间维度）：每个 commit 仍要摊平出一份整图视图，所以每次都付一次
     * O(图规模) 复制；但驻留量按 {@code residentCopies} 份整图封顶，否则加载阶段就会
     * 按提交数线性复制整图而 OOM，量不到提交延迟。内存维度由 S4 用无上限的同一配置去量。
     */
    private static GraphVersionStore oldDesignMode(int graphNodes, int residentCopies) {
        return new GraphVersionStore()
                .withCheckpointInterval(1)
                .withViewLayerLimit(1)
                .withMaxRetainedViews(Integer.MAX_VALUE)
                .withMaxRetainedEntities((long) graphNodes * Math.max(1, residentCopies));
    }

    // ==================== S4 内存占用 ====================

    /**
     * 容量主张的量化：同样 N 次"改一个节点"的提交，delta 模式新增堆 vs
     * 每个 commit 驻留一份全量视图（旧方案等价）的新增堆。旧模式先 OOM 是更强的结论，
     * 必须记下来而不是让进程崩掉。
     */
    private void memoryFootprint() throws Exception {
        System.out.println("\n--- S4 提交数 vs 内存 ---");
        GraphVersionStore delta = new GraphVersionStore();
        loadGraph(delta, config.memoryGraphNodes, 500);
        long afterLoad = usedHeap();
        for (int i = 0; i < config.memoryCommits; i++) {
            GraphWriteTransaction write = delta.beginWrite("main");
            write.updateNode(i % config.memoryGraphNodes, Map.of("age", 40 + i));
            write.commit("mem", "commit " + i);
        }
        long deltaBytes = Math.max(0, usedHeap() - afterLoad);
        long perCommitDelta = Math.max(1, deltaBytes / config.memoryCommits);
        System.out.printf("  delta 模式: 图 %,d 节点占 %s，%,d 次提交再占 %s (%s/commit)%n",
                config.memoryGraphNodes, human(afterLoad), config.memoryCommits,
                human(deltaBytes), human(perCommitDelta));
        record("s4_memory", "delta", "bytesPerCommit", perCommitDelta);
        record("s4_memory", "delta", "retainedDeltaEntities", stat(delta, "retainedDeltaEntities"));
        record("s4_memory", "delta", "retainedViewEntities", stat(delta, "retainedViewEntities"));
        // 视图缓存必须按实体数收口：只按数量封顶时，50k 节点图驻留 64 份视图就是几十 GB。
        long billedViews = stat(delta, "retainedViewEntities");
        long viewBudget = stat(delta, "maxRetainedEntities");
        record("s4_memory", "delta", "maxViewLayers", stat(delta, "maxViewLayers"));
        verdict("s4_view_budget_binds", billedViews <= viewBudget,
                String.format("驻留视图计费 %,d 实体，预算 %,d，最深 %d 层",
                        billedViews, viewBudget, stat(delta, "maxViewLayers")));

        GraphVersionStore snapshot = oldDesignMode(config.memoryGraphNodes, 4);
        loadGraph(snapshot, config.memoryGraphNodes, 500);
        // 内存维度要按旧方案"每 commit 一份整图全都留着"来量：这里放开驻留上限，
        // OOM 本身就是结论，必须被抓下来记账而不是让进程死掉。
        snapshot.withMaxRetainedEntities(Long.MAX_VALUE);
        long snapshotBase = usedHeap();
        int retained = 0;
        long snapshotBytes;
        String note;
        try {
            for (int i = 0; i < config.memoryCommits; i++) {
                GraphWriteTransaction write = snapshot.beginWrite("main");
                write.updateNode(i % config.memoryGraphNodes, Map.of("age", 40 + i));
                String id = write.commit("mem", "commit " + i).getId();
                retained++;
                // 强制该 commit 的视图物化并驻留，等价于旧方案"每 commit 一份整图"。
                if (snapshot.checkout(id).getNodeCount() < 0) {
                    throw new IllegalStateException();
                }
            }
            snapshotBytes = Math.max(0, usedHeap() - snapshotBase);
            note = "驻留 " + retained + " 份全量视图";
        } catch (OutOfMemoryError error) {
            snapshotBytes = Math.max(0, usedHeap() - snapshotBase);
            note = "OOM at commit " + retained + "（未完成 " + config.memoryCommits + " 次）";
        }
        long perCommitOld = Math.max(1, snapshotBytes / Math.max(1, retained));
        double reduction = perCommitOld * 1.0 / perCommitDelta;
        System.out.printf("  snapshot 模式(旧方案等价): %s -> %s (%s/commit)%n",
                note, human(snapshotBytes), human(perCommitOld));
        System.out.printf("  每提交内存降低 %.0fx%n", reduction);
        record("s4_memory", "snapshot", "bytesPerCommit", perCommitOld);
        record("s4_memory", "snapshot", "retainedCommits", retained);
        verdict("s4_memory_per_commit", reduction >= 10,
                String.format("delta 每提交 %s vs snapshot %s，降低 %.1fx (需 >=10x)；%s",
                        human(perCommitDelta), human(perCommitOld), reduction, note));
    }

    // ==================== S5 并发 ====================

    private void concurrency() throws Exception {
        System.out.println("\n--- S5 并发写／读 ---");
        int threads = config.concurrentThreads;
        int ops = config.concurrentOps;

        // (a) 同分支争抢：乐观并发必须拒绝后到者，且不能丢已提交的变更。
        GraphVersionStore contested = new GraphVersionStore();
        loadGraph(contested, 5_000, 500);
        Counters shared = new Counters();
        ConcurrentLinkedQueue<String> committed = new ConcurrentLinkedQueue<>();
        runThreads(threads, slot -> {
            for (int i = 0; i < ops; i++) {
                GraphWriteTransaction write = contested.beginWrite("main");
                write.addNode("Contend", Map.of("t", slot, "i", i));
                try {
                    committed.add(write.commit("contend", "c").getId());
                    shared.success.incrementAndGet();
                } catch (GraphVersionStore.StaleHeadException stale) {
                    shared.conflict.incrementAndGet();
                }
            }
        });
        int expected = shared.success.get();
        long headNodes = contested.checkoutBranch("main").getNodeCount();
        System.out.printf("  同分支: 成功 %,d 冲突 %,d (冲突率 %.0f%%) head 节点 %,d (应为 %,d)%n",
                expected, shared.conflict.get(),
                100.0 * shared.conflict.get() / Math.max(1, expected + shared.conflict.get()),
                headNodes, 5_000L + expected);
        record("s5_contend", "main", "success", expected);
        record("s5_contend", "main", "conflicts", shared.conflict.get());
        verdict("s5_no_lost_updates", headNodes == 5_000L + expected
                        && committed.size() == expected && contested.listCommits().size() >= expected + 2,
                String.format("head 节点 %,d / 应为 %,d；成功 %,d", headNodes, 5_000L + expected, expected));

        // (b) 多分支并行：一分支一线程，不该有任何冲突。
        GraphVersionStore branched = new GraphVersionStore();
        loadGraph(branched, 5_000, 500);
        String headOfMain = branched.getBranchHead("main").getId();
        for (int t = 0; t < threads; t++) {
            branched.createBranch("b" + t, headOfMain);
        }
        Counters branchCounters = new Counters();
        long startedB = System.nanoTime();
        runThreads(threads, slot -> {
            String branch = "b" + slot;
            for (int i = 0; i < ops; i++) {
                try {
                    GraphWriteTransaction write = branched.beginWrite(branch);
                    write.addNode("Own", Map.of("name", "x", "i", i));
                    write.commit(branch, "c" + i);
                    branchCounters.success.incrementAndGet();
                } catch (RuntimeException error) {
                    branchCounters.conflict.incrementAndGet();
                    branchCounters.firstError.compareAndSet(null, branch + ": " + error);
                }
            }
        });
        long microsB = Math.max(1, (System.nanoTime() - startedB) / 1_000);
        long commitsPerSecond = branchCounters.success.get() * 1_000_000L / microsB;
        System.out.printf("  多分支: 成功 %,d 失败 %,d -> %,d commits/s (线程 %d)%n",
                branchCounters.success.get(), branchCounters.conflict.get(), commitsPerSecond, threads);
        if (branchCounters.firstError.get() != null) {
            System.out.println("  首个失败: " + branchCounters.firstError.get());
        }
        record("s5_branch", "all", "commitsPerSecond", commitsPerSecond);
        verdict("s5_branch_no_conflict", branchCounters.conflict.get() == 0
                        && branchCounters.success.get() == threads * ops,
                String.format("成功 %d/%d、冲突 %d", branchCounters.success.get(), threads * ops,
                        branchCounters.conflict.get()));

        // (c) 读写混合：读者在提交推进的同时读随机历史 commit，必须始终读到该 commit 的快照。
        GraphVersionStore mixed = new GraphVersionStore();
        loadGraph(mixed, 10_000, 500);
        ConcurrentLinkedQueue<GraphCommit> history = new ConcurrentLinkedQueue<>();
        history.addAll(mixed.log("main"));
        Counters counters = new Counters();
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch gate = new CountDownLatch(1);
        pool.submit(() -> {
            await(gate);
            for (int i = 0; i < ops * 2; i++) {
                GraphWriteTransaction write = mixed.beginWrite("main");
                write.addNode("Writer", Map.of("name", "w", "i", i));
                try {
                    history.add(write.commit("writer", "w" + i));
                    counters.success.incrementAndGet();
                } catch (GraphVersionStore.StaleHeadException stale) {
                    counters.conflict.incrementAndGet();
                }
            }
        });
        List<java.util.concurrent.Future<?>> readers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int seed = t;
            readers.add(pool.submit(() -> {
                await(gate);
                Random random = new Random(seed);
                for (int i = 0; i < ops * 4; i++) {
                    List<GraphCommit> snapshot = new ArrayList<>(history);
                    GraphCommit pick = snapshot.get(random.nextInt(snapshot.size()));
                    try {
                        long observed = mixed.checkout(pick.getId()).getNodeCount();
                        if (observed != pick.getNodeCount()) {
                            counters.mismatch.incrementAndGet();
                        } else {
                            counters.success.incrementAndGet();
                        }
                    } catch (RuntimeException error) {
                        counters.firstError.compareAndSet(null, String.valueOf(error));
                    }
                }
            }));
        }
        gate.countDown();
        pool.shutdown();
        require(pool.awaitTermination(5, TimeUnit.MINUTES), "并发场景超时");
        for (java.util.concurrent.Future<?> future : readers) {
            future.get();
        }
        System.out.printf("  读写混合: 读成功 %,d 写成功 %,d 快照不一致 %,d%n",
                counters.success.get() - counters.conflict.get(), counters.success.get(),
                counters.mismatch.get());
        record("s5_isolation", "mixed", "snapshotMismatch", counters.mismatch.get());
        verdict("s5_snapshot_isolation", counters.mismatch.get() == 0 && counters.firstError.get() == null,
                String.format("不一致读 %d 次，错误 %s", counters.mismatch.get(), counters.firstError.get()));
    }

    private static final class Counters {
        final AtomicInteger success = new AtomicInteger();
        final AtomicInteger conflict = new AtomicInteger();
        final AtomicInteger mismatch = new AtomicInteger();
        final AtomicReference<String> firstError = new AtomicReference<>();
    }

    private void runThreads(int threads, IntConsumer body) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int slot = t;
            futures.add(pool.submit(() -> {
                await(gate);
                body.accept(slot);
            }));
        }
        gate.countDown();
        pool.shutdown();
        require(pool.awaitTermination(5, TimeUnit.MINUTES), "并发场景超时");
        for (java.util.concurrent.Future<?> future : futures) {
            future.get();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== S6 持久化 ====================

    private void persistence() throws Exception {
        System.out.println("\n--- S6 落盘与重启 ---");
        Path directory = workdir.resolve("repo-" + System.nanoTime());
        Files.createDirectories(directory);
        GraphVersionStore repository = new GraphVersionStore(directory);
        loadGraph(repository, config.persistGraphNodes, 500);
        long baselineBytes = sizeOf(directory);
        List<String> chain = new ArrayList<>();
        long started = System.nanoTime();
        for (int i = 0; i < config.persistCommits; i++) {
            GraphWriteTransaction write = repository.beginWrite("main");
            write.updateNode(i % config.persistGraphNodes, Map.of("age", 50 + i));
            chain.add(write.commit("disk", "c" + i).getId());
        }
        long micros = Math.max(1, (System.nanoTime() - started) / 1_000);
        long appended = Math.max(1, sizeOf(directory) - baselineBytes);
        long bytesPerCommit = appended / config.persistCommits;
        System.out.printf("  %,d 次提交 %.3fs -> %.2f ms/commit；目录 %s (每提交追加 %s，文件 %,d 个)%n",
                config.persistCommits, micros / 1e6, micros / 1e3 / config.persistCommits,
                human(baselineBytes + appended), human(bytesPerCommit), countFiles(directory));

        // 旧方案每提交重写整仓库；用 exportSnapshot 量出"一份全量"的真实字节数。
        Path probe = workdir.resolve("full-snapshot-probe.bin");
        repository.exportSnapshot(chain.get(chain.size() - 1), probe);
        long fullSnapshot = Files.size(probe);
        Files.deleteIfExists(probe);
        long oldDesignBytes = fullSnapshot * config.persistCommits;
        System.out.printf("  单 commit 全量副本 = %s -> 旧方案需重写约 %s，实际追加 %s (%.0fx)%n",
                human(fullSnapshot), human(oldDesignBytes), human(appended),
                oldDesignBytes * 1.0 / appended);
        record("s6_persist", "disk", "msPerCommit", micros / 1e3 / (double) config.persistCommits);
        record("s6_persist", "disk", "bytesPerCommit", bytesPerCommit);
        record("s6_persist", "disk", "fullSnapshotBytes", fullSnapshot);

        long reloadStart = System.nanoTime();
        GraphVersionStore reopened = new GraphVersionStore(directory);
        long reloadMillis = (System.nanoTime() - reloadStart) / 1_000_000;
        System.out.printf("  重启加载 %d ms，commits=%,d%n", reloadMillis, reopened.listCommits().size());
        record("s6_persist", "disk", "reloadMillis", reloadMillis);

        int checked = 0;
        int mismatched = 0;
        long expectedNodes = config.persistGraphNodes;
        for (int i = 0; i < chain.size(); i += Math.max(1, chain.size() / 25)) {
            var view = reopened.checkout(chain.get(i));
            checked++;
            if (view.getNodeCount() != expectedNodes) {
                mismatched++;
                continue;
            }
            Object age = view.getStore().getNode(i % config.persistGraphNodes).get("age");
            if (!(age instanceof Number) || ((Number) age).longValue() != 50L + i) {
                mismatched++;
            }
        }
        System.out.printf("  重启后抽检 %d 个历史 commit，不符 %d%n", checked, mismatched);
        verdict("s6_reload_consistent", mismatched == 0,
                String.format("抽检 %d 个历史视图，不符 %d", checked, mismatched));
        verdict("s6_append_only", bytesPerCommit * 4 < fullSnapshot,
                String.format("每提交追加 %s 应远小于全量副本 %s", human(bytesPerCommit), human(fullSnapshot)));
    }

    // ==================== S7 版本回收 ====================

    private void versionGc() throws Exception {
        System.out.println("\n--- S7 版本回收 ---");
        GraphVersionStore repository = new GraphVersionStore();
        loadGraph(repository, config.persistGraphNodes, 500);
        long headNodes = repository.checkoutBranch("main").getNodeCount();
        int branches = 6;
        for (int b = 0; b < branches; b++) {
            repository.createBranch("trash" + b, repository.getBranchHead("main").getId());
            for (int i = 0; i < 40; i++) {
                GraphWriteTransaction write = repository.beginWrite("trash" + b);
                write.addNode("Junk", Map.of("name", "j", "b", b, "i", i));
                write.commit("junk", "j");
            }
        }
        long beforeVersions = stat(repository, "nodeVersionRecords");
        int beforeCommits = repository.listCommits().size();
        int collected = repository.garbageCollect("main");
        long afterVersions = stat(repository, "nodeVersionRecords");
        long released = beforeVersions - afterVersions;
        System.out.printf("  commits %,d -> 回收 %d，版本记录 %,d -> %,d (释放 %,d)%n",
                beforeCommits, collected, beforeVersions, afterVersions, released);
        System.out.printf("  回收后保留分支视图 %,d 节点（回收前 %,d），分支数 %d -> %d，堆 %s%n",
                repository.checkoutBranch("main").getNodeCount(), headNodes,
                repository.listBranches().size(), branches + 1, human(usedHeap()));
        record("s7_gc", "main", "collectedCommits", collected);
        record("s7_gc", "main", "releasedVersionRecords", released);
        verdict("s7_gc_reclaims", collected == branches * 40 && released > 0
                        && repository.checkoutBranch("main").getNodeCount() == headNodes
                        && repository.listBranches().size() == 1,
                String.format("回收 %d/%d commit，释放 %d 条版本记录，保留分支视图与分支指针正确",
                        collected, branches * 40, released));
    }

    // ==================== 公共工具 ====================

    private void loadGraph(GraphVersionStore repository, int nodes, int batch) {
        GraphWriteTransaction write = repository.beginWrite("main");
        long previous = -1;
        for (int i = 0; i < nodes; i++) {
            Node node = write.addNode("Person", Map.of("name", "user-" + i, "age", 18 + (i % 60)));
            if (previous >= 0 && i % 2 == 0) {
                write.addEdge("KNOWS", previous, node.getId(), Map.of());
            }
            previous = node.getId();
            if ((i + 1) % batch == 0) {
                write.commit("loader", "load " + (i / batch));
                write = repository.beginWrite("main");
            }
        }
        write.commit("loader", "load tail");
    }

    /** 返回 {p50, p99}，单位微秒。 */
    private static long[] percentile(long[] samplesMicros) {
        long[] sorted = samplesMicros.clone();
        Arrays.sort(sorted);
        int p50 = Math.min(sorted.length - 1, (int) Math.floor(sorted.length * 0.50));
        int p99 = Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * 0.99) - 1);
        return new long[]{Math.max(1, sorted[p50]), Math.max(1, sorted[p99])};
    }

    private static long stat(GraphVersionStore repository, String key) {
        Object value = repository.versionStats().get(key);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        long previous = Long.MAX_VALUE;
        for (int i = 0; i < 6; i++) {
            System.gc();
            try {
                Thread.sleep(60);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
            long current = runtime.totalMemory() - runtime.freeMemory();
            if (Math.abs(current - previous) < 1_048_576) {
                return current;
            }
            previous = current;
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long sizeOf(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException error) {
                            return 0L;
                        }
                    })
                    .sum();
        }
    }

    private static long countFiles(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private void record(String scenario, String mode, String metric, double value) {
        jsonLines.add(String.format(
                "{\"scenario\":\"%s\",\"mode\":\"%s\",\"metric\":\"%s\",\"value\":%.4f,\"elapsedMs\":%d}",
                scenario, mode, metric, value, (System.nanoTime() - startedAt) / 1_000_000));
    }

    private void record(String scenario, String mode, String metric, long value) {
        record(scenario, mode, metric, (double) value);
    }

    /** 结论一旦判红就不再被后续 PASS 洗白。 */
    private void verdict(String name, boolean pass, String detail) {
        String existing = verdicts.get(name);
        if (existing != null && (existing.startsWith("FAIL") || pass)) {
            return;
        }
        verdicts.put(name, (pass ? "PASS " : "FAIL ") + detail);
        if (!pass) {
            System.out.println("  !! " + name + ": " + detail);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1fMiB", bytes / 1024.0 / 1024);
        return String.format("%.2fGiB", bytes / 1024.0 / 1024 / 1024);
    }

    private static String format(long value) {
        return String.format("%,d", value);
    }
}
