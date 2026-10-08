package com.zifang.z.graph.core;

import com.zifang.z.graph.api.Edge;
import com.zifang.z.graph.api.EdgeTypeSchema;
import com.zifang.z.graph.api.GraphCommit;
import com.zifang.z.graph.api.GraphMergeResult;
import com.zifang.z.graph.api.GraphStore;
import com.zifang.z.graph.api.Node;
import com.zifang.z.graph.api.TagSchema;
import com.zifang.z.graph.core.storage.AncestryIndex;
import com.zifang.z.graph.core.storage.CommitObjectStore;
import com.zifang.z.graph.core.storage.PayloadCodec;
import com.zifang.z.graph.core.storage.RefStore;
import com.zifang.z.graph.core.storage.RefViewGraphStore;
import com.zifang.z.graph.core.storage.StorageEngine;
import com.zifang.z.graph.core.storage.VersionStore;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import com.zifang.z.graph.api.Colls;

/**
 * 版本化图仓库：引擎原生 MVCC。
 *
 * <p>唯一图数据基底是磁盘上的追加式版本链（{@link VersionStore}）+ 版本化
 * postings 索引，由 {@link StorageEngine} 持有。任何 ref —— main head、分支
 * head、任意历史 commit —— 都由 {@link RefViewGraphStore} 按「commitSeq ∈ 该
 * ref 的祖先闭包」即时解析，<b>仓库不物化任何图状态</b>：没有视图缓存、没有
 * 检查点、没有写事务覆盖层充当视图。</p>
 *
 * <p>commit object 对齐 Git：id = 整个对象规范化字节的 SHA-256（含 parents /
 * branch / author / message / timestamp / delta 清单），同对象必同 id，落盘即
 * 不可变（{@link CommitObjectStore}）。分支是 {@link RefStore} 里的纯指针文件，
 * ref 移动是 commit 的提交点：数据先行 force，ref 原子替换，崩溃后孤儿对象由
 * GC 回收。</p>
 *
 * <p>写事务（{@link TxBuffer}）只是读改写缓冲：读穿透 beginWrite 时的 head
 * 视图，写登记进暂存指令集；提交 = 校验 head 未被推进（{@link StaleHeadException}
 * 乐观并发）→ 版本链追加 → commit object 落盘 → ref 移动。</p>
 */
public final class GraphVersionStore {

    private static final InMemoryGraphStore EMPTY_VIEW = new InMemoryGraphStore();
    private static final String DEFAULT_BRANCH = "main";

    private final Path dataDir;
    private final StorageEngine engine;
    private final CommitObjectStore commitStore;
    private final RefStore refStore;
    private final AncestryIndex ancestry;
    private final Map<String, CommitObjectStore.CommitMeta> commits = new LinkedHashMap<>();
    private final Map<Long, String> commitIdBySeq = new LinkedHashMap<>();
    private final Map<String, GraphCommit> commitHandles = new LinkedHashMap<>();

    /**
     * 纯内存口径的仓库：实际落到进程临时目录（引擎是磁盘追加式的），进程退出
     * 即弃，与旧「不传目录 = 不落盘」的语义等价。
     */
    public GraphVersionStore() {
        this(null);
    }

    public GraphVersionStore(Path storageDirectory) {
        boolean ephemeral = storageDirectory == null;
        Path dir;
        if (ephemeral) {
            try {
                dir = Files.createTempDirectory("z-graph-ephemeral-");
            } catch (IOException e) {
                throw new IllegalStateException("Cannot create ephemeral store directory", e);
            }
        } else {
            dir = storageDirectory;
        }
        this.dataDir = dir;
        Path storeDir = dir.resolve("store");
        Path commitsDir = dir.resolve("commits");
        try {
            boolean existing = Files.exists(storeDir.resolve("header.bin"));
            if (existing) {
                this.engine = StorageEngine.open(storeDir);
            } else {
                this.engine = StorageEngine.create(storeDir);
            }
            Files.createDirectories(commitsDir);
            this.commitStore = new CommitObjectStore(commitsDir);
            this.refStore = new RefStore(dir);
            this.ancestry = new AncestryIndex(new CommitGraphView());
            if (existing) {
                loadCommits();
                requireBranch(DEFAULT_BRANCH);
            } else {
                refStore.init(DEFAULT_BRANCH);
                createCommitAndAdvance(DEFAULT_BRANCH, Colls.listOf(), "system", "Initial graph",
                        new GraphDelta().freeze(), 0, 0, null, null);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot open z-graph repository at " + dir, e);
        }
    }

    private final class CommitGraphView implements AncestryIndex.CommitGraph {
        @Override
        public List<String> parentsOf(String commitId) {
            CommitObjectStore.CommitMeta meta = commits.get(commitId);
            return meta == null ? Colls.listOf() : meta.parents;
        }

        @Override
        public long commitSeqOf(String commitId) {
            CommitObjectStore.CommitMeta meta = commits.get(commitId);
            if (meta == null) {
                throw new IllegalArgumentException("Unknown commit: " + commitId);
            }
            return meta.commitSeq;
        }
    }

    private void loadCommits() throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(dataDir.resolve("commits"))) {
            List<Path> objects = new ArrayList<>();
            files.filter(path -> path.getFileName().toString().endsWith(".bin")).forEach(objects::add);
            objects.sort(Comparator.comparing(path -> path.getFileName().toString()));
            for (Path object : objects) {
                String id = object.getFileName().toString().replace(".bin", "");
                registerCommit(commitStore.read(id));
            }
        }
    }

    private void registerCommit(CommitObjectStore.CommitMeta meta) {
        commits.put(meta.id, meta);
        commitIdBySeq.put(meta.commitSeq, meta.id);
    }

    // ==================== 调参 ====================

    public Path getStorageDirectory() {
        return dataDir;
    }

    // ==================== 提交图 ====================

    /** 返回当前分支 head；仓库默认创建 main 分支。 */
    public synchronized GraphCommit getBranchHead(String branch) {
        return commitHandle(resolveRefId(requireBranch(branch)));
    }

    public synchronized GraphCommit getCommit(String commitId) {
        return commitHandle(requireCommitId(commitId, "commit=" + commitId));
    }

    public synchronized List<GraphCommit> listCommits() {
        List<CommitObjectStore.CommitMeta> ordered = new ArrayList<>(commits.values());
        ordered.sort(Comparator.comparingLong(meta -> meta.commitSeq));
        List<GraphCommit> result = new ArrayList<>(ordered.size());
        for (CommitObjectStore.CommitMeta meta : ordered) {
            result.add(commitHandle(meta.id));
        }
        return result;
    }

    public synchronized List<String> listBranches() {
        try {
            return refStore.branches();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list branches", e);
        }
    }

    /** 返回 ref 的第一父链日志，ref 可以是 branch 名或 commit ID。 */
    public synchronized List<GraphCommit> log(String ref) {
        String headId = resolveRefId(ref);
        List<GraphCommit> history = new ArrayList<>();
        for (String id : AncestryIndex.lineageToRoot(new CommitGraphView(), headId)) {
            history.add(commitHandle(id));
        }
        return history;
    }

    public synchronized GraphCommit createBranch(String branch, String fromCommitId) {
        validateBranchName(branch);
        try {
            if (refStore.get(branch) != null) {
                throw new IllegalArgumentException("Branch already exists: " + branch);
            }
            requireCommit(fromCommitId, "commit=" + fromCommitId);
            refStore.put(branch, fromCommitId);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create branch " + branch, e);
        }
        return commitHandle(fromCommitId);
    }

    // ==================== 写事务与视图 ====================

    public synchronized GraphWriteTransaction beginWrite(String branch) {
        String headId = resolveRefId(requireBranch(branch));
        requireCommit(headId, "branch=" + branch);
        TxBuffer buffer = new TxBuffer(viewAt(headId), new TxBuffer.IdAllocator() {
            @Override
            public long nextNodeId() {
                return allocateNodeId();
            }

            @Override
            public long nextEdgeId() {
                return allocateEdgeId();
            }
        });
        return new GraphWriteTransaction(this, branch, headId, buffer);
    }

    /** 打开某个 commit 上的视图；返回的 checkout 绑定该 commit，不随分支继续变化。 */
    public synchronized GraphCheckout checkout(String commitId) {
        CommitObjectStore.CommitMeta meta = requireCommit(commitId, "commit=" + commitId);
        return new GraphCheckout(commitHandle(meta.id), viewAt(meta.id), this);
    }

    public synchronized GraphCheckout checkoutBranch(String branch) {
        return checkout(resolveRefId(requireBranch(branch)));
    }

    /** 绑定指定 commit 的引擎解析视图（无任何物化）；计数用 commit 声明值播种。 */
    RefViewGraphStore viewAt(String commitId) {
        CommitObjectStore.CommitMeta meta = requireCommit(commitId, "commit=" + commitId);
        return new RefViewGraphStore(engine, ancestry.visibilityOf(commitId), meta.nodeCount, meta.edgeCount);
    }

    // ==================== commit 落地 ====================

    /**
     * 写事务的提交入口：校验 head 未被推进（乐观并发），随后把指令集落成版本链
     * + postings，commit object 内容寻址落盘，最后移动分支 ref（提交点）。
     */
    synchronized GraphCommit commit(String branch,
                                    String baseCommitId,
                                    GraphDelta delta,
                                    long nodeCount,
                                    long edgeCount,
                                    String author,
                                    String message) {
        requireBranch(branch);
        return createCommitAndAdvance(branch, Colls.listOf(baseCommitId), author, message,
                delta, nodeCount, edgeCount, null, baseCommitId);
    }

    private synchronized GraphCommit createCommitAndAdvance(String branch,
                                                            List<String> parents,
                                                            String author,
                                                            String message,
                                                            GraphDelta delta,
                                                            long nodeCount,
                                                            long edgeCount,
                                                            String legacyId,
                                                            String expectedBaseHeadId) {
        try {
            if (expectedBaseHeadId != null) {
                String currentHeadId = refStore.get(branch);
                if (!Objects.equals(currentHeadId, expectedBaseHeadId)) {
                    throw new StaleHeadException(
                            "Branch advanced since transaction started: " + branch
                                    + " (expected " + expectedBaseHeadId + ", actual " + currentHeadId + ")");
                }
            }
            long commitSeq = engine.allocateCommitSeq();
            byte[] canonical = CommitObjectStore.canonicalBytes(parents, branch, author, message,
                    System.currentTimeMillis(), nodeCount, edgeCount, commitSeq, legacyId, delta);
            String id = CommitObjectStore.hashOf(canonical);
            byte[] digest16 = Arrays.copyOf(CommitObjectStore.digestOf(canonical), 16);
            engine.applyDelta(commitSeq, digest16, delta);
            String writtenId = commitStore.writeCanonical(canonical);
            if (!writtenId.equals(id)) {
                throw new IllegalStateException("Commit id mismatch: " + writtenId + " != " + id);
            }
            // 提交点 = ref 移动：在此之前数据必须全部 force 完成，崩溃后 ref 仍指旧 commit。
            engine.persistAndForce();
            refStore.put(branch, id);
            registerCommit(commitStore.read(id));
            return commitHandle(id);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot persist commit on branch " + branch, e);
        }
    }

    /**
     * 当并发事务提交时，branch head 已被推进，提示调用方放弃或重试。
     */
    public static class StaleHeadException extends IllegalStateException {
        public StaleHeadException(String message) { super(message); }
    }

    /**
     * 引用（commit id 或分支名）在仓库里不存在。它与参数写错一样都是 {@link IllegalArgumentException}
     * 的子类，好让既有 catch 继续生效；控制面据此把"资源不存在"和"服务端故障"分开（404 vs 500）。
     */
    public static class UnknownReferenceException extends IllegalArgumentException {
        public UnknownReferenceException(String message) { super(message); }
    }

    synchronized long allocateNodeId() {
        return engine.allocateNodeId();
    }

    synchronized long allocateEdgeId() {
        return engine.allocateEdgeId();
    }

    synchronized void reserveNodeId(long id) {
        engine.reserveNodeId(id);
    }

    synchronized void reserveEdgeId(long id) {
        engine.reserveEdgeId(id);
    }

    // ==================== 单实体版本链 ====================

    /** 节点的全部已登记版本，按提交先后排列；节点从未出现时为空。 */
    public synchronized List<GraphEntityVersion> nodeVersions(long nodeId) {
        return entityVersions(VersionStore.KIND_UPSERT_NODE, nodeId);
    }

    /** 边的全部已登记版本，按提交先后排列。 */
    public synchronized List<GraphEntityVersion> edgeVersions(long edgeId) {
        return entityVersions(VersionStore.KIND_UPSERT_EDGE, edgeId);
    }

    private List<GraphEntityVersion> entityVersions(int upsertKind, long entityId) {
        List<VersionStore.VersionRecord> chain;
        try {
            chain = engine.versionStore().historyVisible(upsertKind, entityId, StorageEngine.ALWAYS_VISIBLE);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read version chain for entity " + entityId, e);
        }
        List<GraphEntityVersion> result = new ArrayList<>(chain.size());
        for (int i = chain.size() - 1; i >= 0; i--) {
            GraphEntityVersion version = toEntityVersion(chain.get(i), upsertKind);
            if (version != null) {
                result.add(version);
            }
        }
        return result;
    }

    private GraphEntityVersion toEntityVersion(VersionStore.VersionRecord record, int upsertKind) {
        String commitId = commitIdBySeq.get(record.commitSeq);
        if (commitId == null) {
            // 版本所属 commit 已被 GC（Phase 3 会压实这些孤儿版本）；此刻跳过。
            return null;
        }
        CommitObjectStore.CommitMeta meta = commits.get(commitId);
        GraphEntityVersion.Kind kind = record.isDelete()
                ? GraphEntityVersion.Kind.DELETE
                : GraphEntityVersion.Kind.UPSERT;
        Node node = null;
        Edge edge = null;
        if (!record.isDelete()) {
            try {
                byte[] payload = engine.versionStore().payload(record);
                if (upsertKind == VersionStore.KIND_UPSERT_NODE) {
                    PayloadCodec.NodePayload decoded = PayloadCodec.decodeNode(payload, engine.labelDictionary());
                    node = new Node(record.entityId, decoded.labels, decoded.properties);
                } else {
                    PayloadCodec.EdgePayload decoded = PayloadCodec.decodeEdge(payload, engine.typeDictionary());
                    edge = new Edge(record.entityId, decoded.type, decoded.startNodeId,
                            decoded.endNodeId, decoded.properties);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Cannot decode version payload " + record.versionId, e);
            }
        }
        return new GraphEntityVersion(record.entityId, kind, commitId, record.commitSeq,
                meta.timestampEpochMillis, node, edge);
    }

    /**
     * 节点在指定 ref（分支名或 commit ID）上的版本：沿祖先链找到最近一次真正改动过
     * 该节点的提交。返回空表示该 ref 的历史里从未登记过这个节点的任何版本。
     */
    public synchronized java.util.Optional<GraphEntityVersion> nodeVersionAt(long nodeId, String ref) {
        return versionAt(nodeVersions(nodeId), ref, "ref=" + ref);
    }

    public synchronized java.util.Optional<GraphEntityVersion> edgeVersionAt(long edgeId, String ref) {
        return versionAt(edgeVersions(edgeId), ref, "ref=" + ref);
    }

    /** 节点在 ref 可见的版本链，按时间顺序。 */
    public synchronized List<GraphEntityVersion> nodeHistory(long nodeId, String ref) {
        Set<Long> closure = ancestry.closureOf(resolveRefId(ref));
        List<GraphEntityVersion> visible = new ArrayList<>();
        for (GraphEntityVersion version : nodeVersions(nodeId)) {
            if (closure.contains(version.getCommitSequence())) {
                visible.add(version);
            }
        }
        return visible;
    }

    private java.util.Optional<GraphEntityVersion> versionAt(List<GraphEntityVersion> chain,
                                                             String ref,
                                                             String description) {
        if (chain.isEmpty()) {
            return java.util.Optional.empty();
        }
        Set<Long> closure = ancestry.closureOf(resolveRefId(ref));
        GraphEntityVersion best = null;
        for (GraphEntityVersion version : chain) {
            if (!closure.contains(version.getCommitSequence())) continue;
            if (best == null || version.getCommitSequence() > best.getCommitSequence()) {
                best = version;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("Ref has no version of this entity: " + description);
        }
        return java.util.Optional.of(best);
    }

    // ==================== 观测 ====================

    /** 引擎观测面。所有计数一律以 long 输出，便于压测脚本直接取数。 */
    public synchronized Map<String, Object> versionStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("commitCount", (long) commits.size());
        try {
            stats.put("branchCount", (long) refStore.branches().size());
        } catch (IOException e) {
            stats.put("branchCount", -1L);
        }
        stats.putAll(engine.stats());
        stats.put("versionPayloadBytes", engine.payloadBytes());
        stats.put("ancestryCachedClosures", (long) ancestry.cachedClosureCount());
        return stats;
    }

    // ==================== 快照导入导出 ====================

    /** 把指定 commit 的视图导出为独立文件，便于备份/迁移。 */
    public synchronized Path exportSnapshot(String commitId, Path target) throws IOException {
        requireCommit(commitId, "commit=" + commitId);
        GraphDelta full = GraphDelta.between(EMPTY_VIEW, resolveToInMemory(commitId));
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (OutputStream output = Files.newOutputStream(temp,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
             DataOutputStream out = new DataOutputStream(output)) {
            out.writeInt(GraphCodec.STORAGE_MAGIC);
            out.writeInt(GraphCodec.STORAGE_VERSION);
            full.writeTo(out);
        }
        moveIntoPlace(temp, target);
        return target;
    }

    /**
     * 从 snapshot 文件导入：在当前 head 上新建一个 commit，让该 commit 的视图等于导入内容。
     * 返回新产生的 commit，方便调用方继续推进分支。
     */
    public synchronized GraphCommit importSnapshot(Path source,
                                                   String branch,
                                                   String author,
                                                   String message) throws IOException {
        requireBranch(branch);
        GraphDelta full;
        try (InputStream input = Files.newInputStream(source);
             DataInputStream in = new DataInputStream(input)) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != GraphCodec.STORAGE_MAGIC) {
                throw new IllegalArgumentException("Snapshot magic mismatch: " + source);
            }
            if (version < GraphCodec.LEGACY_STORAGE_VERSION || version > GraphCodec.STORAGE_VERSION) {
                throw new IllegalArgumentException("Snapshot version unsupported: " + version);
            }
            full = version >= GraphCodec.STORAGE_VERSION
                    ? GraphDelta.readFrom(in)
                    : GraphDelta.between(EMPTY_VIEW, readLegacySnapshot(in, version));
        }
        String headId = resolveRefId(branch);
        InMemoryGraphStore target = EMPTY_VIEW.copy();
        target.applyDelta(full);
        GraphDelta replacement = GraphDelta.between(resolveToInMemory(headId), target).freeze();
        return createCommitAndAdvance(branch, Colls.listOf(headId), author, message, replacement,
                target.getNodeCount(), target.getEdgeCount(), null, headId);
    }

    /** 解析指定 commit 的完整视图进一份内存副本（仅用于 export/import/merge 等冷路径）。 */
    private InMemoryGraphStore resolveToInMemory(String commitId) {
        GraphStore view = viewAt(commitId);
        InMemoryGraphStore snapshot = new InMemoryGraphStore();
        for (Node node : view.getAllNodes()) {
            snapshot.addNode(node.getId(), node.getLabels(), node.getProperties());
        }
        for (Edge edge : view.getAllEdges()) {
            snapshot.addEdge(edge.getId(), edge.getType(), edge.getStartNodeId(), edge.getEndNodeId(),
                    edge.getProperties());
        }
        for (String tag : view.listTags()) {
            snapshot.putTagSchema(view.getTagSchema(tag));
        }
        for (String edgeType : view.listEdgeTypes()) {
            snapshot.putEdgeTypeSchema(view.getEdgeTypeSchema(edgeType));
        }
        for (List<String> index : view.getPropertyIndexes()) {
            snapshot.createPropertyIndex(index.get(0), index.get(1));
        }
        return snapshot;
    }

    // ==================== 合并 ====================

    /**
     * 将 source branch 合并到 target branch。只自动合并三方模型中一侧发生变化的实体；
     * 同一节点/边两侧都发生不一致修改时返回冲突，且不会移动 target head。
     *
     * <p>过渡实现：三方快照经引擎解析视图装配后走既有字段级合并；Phase 3 换成
     * commit 级 replay（只比较两侧相对 merge-base 触碰过的实体）。</p>
     */
    public synchronized GraphMergeResult merge(String targetBranch,
                                               String sourceBranch,
                                               String author,
                                               String message) {
        requireBranch(targetBranch);
        requireBranch(sourceBranch);
        String targetHeadId = resolveRefId(targetBranch);
        String sourceHeadId = resolveRefId(sourceBranch);
        if (Objects.equals(targetHeadId, sourceHeadId)) {
            return new GraphMergeResult(false, targetHeadId, commitHandle(targetHeadId), Colls.listOf());
        }

        String baseId = newestCommonAncestor(sourceHeadId, targetHeadId);
        if (baseId == null) {
            throw new IllegalStateException("Branches do not have a common ancestor");
        }
        if (Objects.equals(baseId, sourceHeadId)) {
            // source 已经包含 target 的全部历史，执行 fast-forward。
            try {
                refStore.put(targetBranch, sourceHeadId);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot fast-forward " + targetBranch, e);
            }
            return new GraphMergeResult(true, baseId, commitHandle(sourceHeadId), Colls.listOf());
        }

        InMemoryGraphStore base = resolveToInMemory(baseId);
        InMemoryGraphStore ours = resolveToInMemory(targetHeadId);
        InMemoryGraphStore theirs = resolveToInMemory(sourceHeadId);
        MergeState merged = mergeSnapshots(base, ours, theirs);
        if (!merged.conflicts.isEmpty()) {
            return new GraphMergeResult(false, baseId, null, merged.conflicts);
        }

        GraphDelta mergeDelta = GraphDelta.between(ours, merged.store).freeze();
        GraphCommit mergeCommit = createCommitAndAdvance(targetBranch, Colls.listOf(targetHeadId, sourceHeadId),
                author, message, mergeDelta, merged.store.getNodeCount(), merged.store.getEdgeCount(), null, null);
        return new GraphMergeResult(true, baseId, mergeCommit, Colls.listOf());
    }

    /** 两侧闭包交集里 commitSeq 最新的公共祖先。 */
    private String newestCommonAncestor(String a, String b) {
        Set<Long> closureB = ancestry.closureOf(b);
        String best = null;
        long bestSeq = Long.MIN_VALUE;
        for (String candidate : AncestryIndex.lineageToRoot(new CommitGraphView(), a)) {
            CommitObjectStore.CommitMeta meta = commits.get(candidate);
            if (meta != null && closureB.contains(meta.commitSeq) && meta.commitSeq > bestSeq) {
                best = candidate;
                bestSeq = meta.commitSeq;
            }
        }
        return best;
    }

    // ==================== 回收 ====================

    /**
     * 回收不再被任何保留分支引用的 commit：删掉它们的 commit object 与索引登记。
     * 保留分支的 head 以及它们的全部祖先都会保留，因此回收后这些分支上的历史视图
     * 仍然可以按 commit 打开。
     *
     * @return 被回收的 commit 数量
     */
    public synchronized int garbageCollect(String... keepBranches) {
        Set<String> keep = keepBranches == null || keepBranches.length == 0
                ? new LinkedHashSet<>(listBranches())
                : new LinkedHashSet<>(Colls.listOf(keepBranches));
        Set<String> reachable = new HashSet<>();
        for (String branch : keep) {
            String headId = resolveRefId(requireBranch(branch));
            for (String id : AncestryIndex.lineageToRoot(new CommitGraphView(), headId)) {
                reachable.add(id);
            }
            // merge 双父：闭包沿全部 parents 递归补齐
            for (Long seq : ancestry.closureOf(headId)) {
                String id = commitIdBySeq.get(seq);
                if (id != null) reachable.add(id);
            }
        }
        List<String> collected = new ArrayList<>();
        for (String id : commits.keySet()) {
            if (!reachable.contains(id)) collected.add(id);
        }
        for (String id : collected) {
            CommitObjectStore.CommitMeta meta = commits.remove(id);
            commitIdBySeq.remove(meta.commitSeq);
            commitHandles.remove(id);
            try {
                commitStore.delete(id);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot delete commit object " + id, e);
            }
        }
        try {
            for (Map.Entry<String, String> ref : refStore.allRefs().entrySet()) {
                if (!reachable.contains(ref.getValue())) {
                    refStore.remove(ref.getKey());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot prune branch refs", e);
        }
        if (!collected.isEmpty()) {
            ancestry.invalidateAll();
            try {
                engine.persistAndForce();
            } catch (IOException e) {
                throw new IllegalStateException("Cannot persist after garbage collect", e);
            }
        }
        return collected.size();
    }

    /** 当前仍可从分支 head 到达的 commit 数。 */
    public synchronized int reachableCommitCount() {
        Set<String> reachable = new HashSet<>();
        try {
            for (String headId : refStore.allRefs().values()) {
                for (Long seq : ancestry.closureOf(headId)) {
                    String id = commitIdBySeq.get(seq);
                    if (id != null) reachable.add(id);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot compute reachable commits", e);
        }
        return reachable.size();
    }

    // ==================== 内部：引用解析 ====================

    private String resolveRefId(String ref) {
        try {
            String headId = refStore.get(ref);
            if (headId != null) {
                return headId;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read ref " + ref, e);
        }
        return requireCommitId(ref, "commit=" + ref);
    }

    private String requireBranch(String branch) {
        validateBranchName(branch);
        try {
            if (refStore.get(branch) == null) {
                throw new UnknownReferenceException("Unknown branch: " + branch);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read branch " + branch, e);
        }
        return branch;
    }

    private CommitObjectStore.CommitMeta requireCommit(String id, String description) {
        CommitObjectStore.CommitMeta meta = commits.get(id);
        if (meta == null) {
            throw new UnknownReferenceException("Unknown " + description);
        }
        return meta;
    }

    private String requireCommitId(String id, String description) {
        requireCommit(id, description);
        return id;
    }

    private GraphCommit commitHandle(String id) {
        GraphCommit cached = commitHandles.get(id);
        if (cached != null) {
            return cached;
        }
        CommitObjectStore.CommitMeta meta = requireCommit(id, "commit=" + id);
        GraphCommit handle = new GraphCommit(meta.id, meta.parents, meta.branch, meta.author, meta.message,
                meta.timestampEpochMillis, meta.nodeCount, meta.edgeCount);
        commitHandles.put(id, handle);
        return handle;
    }

    private static void validateBranchName(String branch) {
        if (branch == null || branch.trim().isEmpty() || !branch.matches("[A-Za-z0-9._/-]+")) {
            throw new IllegalArgumentException("Invalid branch name: " + branch);
        }
    }

    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ==================== 内部：三方合并（过渡实现） ====================

    private MergeState mergeSnapshots(InMemoryGraphStore base,
                                      InMemoryGraphStore ours,
                                      InMemoryGraphStore theirs) {
        Map<Long, Node> baseNodes = indexNodes(base.getAllNodes());
        Map<Long, Node> ourNodes = indexNodes(ours.getAllNodes());
        Map<Long, Node> theirNodes = indexNodes(theirs.getAllNodes());
        Map<Long, Edge> baseEdges = indexEdges(base.getAllEdges());
        Map<Long, Edge> ourEdges = indexEdges(ours.getAllEdges());
        Map<Long, Edge> theirEdges = indexEdges(theirs.getAllEdges());

        List<String> conflicts = new ArrayList<>();
        Map<Long, Node> mergedNodes = new LinkedHashMap<>();
        for (Long id : unionKeys(baseNodes, ourNodes, theirNodes)) {
            Node selected = mergeNode(id, baseNodes.get(id), ourNodes.get(id), theirNodes.get(id), conflicts);
            if (selected != null) {
                mergedNodes.put(id, selected);
            }
        }

        Map<Long, Edge> mergedEdges = new LinkedHashMap<>();
        for (Long id : unionKeys(baseEdges, ourEdges, theirEdges)) {
            Edge selected = mergeEdge(id, baseEdges.get(id), ourEdges.get(id), theirEdges.get(id), conflicts);
            if (selected != null) {
                mergedEdges.put(id, selected);
            }
        }

        InMemoryGraphStore result = new InMemoryGraphStore();
        if (conflicts.isEmpty()) {
            mergedNodes.values().stream()
                    .sorted(Comparator.comparingLong(Node::getId))
                    .forEach(node -> result.addNode(node.getId(), node.getLabels(), node.getProperties()));
            mergedEdges.values().stream()
                    .sorted(Comparator.comparingLong(Edge::getId))
                    .forEach(edge -> {
                        if (result.getNode(edge.getStartNodeId()) == null || result.getNode(edge.getEndNodeId()) == null) {
                            conflicts.add("edge:" + edge.getId() + " references a deleted node");
                        } else {
                            result.addEdge(edge.getId(), edge.getType(), edge.getStartNodeId(), edge.getEndNodeId(),
                                    edge.getProperties());
                        }
                    });
            copyMergeMetadata(ours, theirs, result);
        }
        return new MergeState(result, conflicts);
    }

    /**
     * 合并提交还要带走两侧的 schema 和索引定义：这两类元数据不参与三方冲突判定，
     * 但只要有一侧声明过，合并后的视图就必须仍然认得它。
     */
    private static void copyMergeMetadata(InMemoryGraphStore ours,
                                          InMemoryGraphStore theirs,
                                          InMemoryGraphStore result) {
        theirs.listTags().stream()
                .filter(name -> ours.getTagSchema(name) == null)
                .forEach(name -> result.putTagSchema(theirs.getTagSchema(name)));
        ours.listTags().forEach(name -> result.putTagSchema(ours.getTagSchema(name)));
        theirs.listEdgeTypes().stream()
                .filter(name -> ours.getEdgeTypeSchema(name) == null)
                .forEach(name -> result.putEdgeTypeSchema(theirs.getEdgeTypeSchema(name)));
        ours.listEdgeTypes().forEach(name -> result.putEdgeTypeSchema(ours.getEdgeTypeSchema(name)));
        Set<GraphDelta.IndexKey> keys = new LinkedHashSet<>();
        ours.getPropertyIndexes().forEach(item -> keys.add(new GraphDelta.IndexKey(item.get(0), item.get(1))));
        theirs.getPropertyIndexes().forEach(item -> keys.add(new GraphDelta.IndexKey(item.get(0), item.get(1))));
        for (GraphDelta.IndexKey key : keys) {
            result.registerIndexDefinition(key.label(), key.propertyKey());
        }
    }

    private Node mergeNode(long id,
                           Node base,
                           Node ours,
                           Node theirs,
                           List<String> conflicts) {
        if (sameEntity(ours, theirs)) return ours;
        if (sameEntity(ours, base)) return theirs;
        if (sameEntity(theirs, base)) return ours;
        if (base == null || ours == null || theirs == null) {
            conflicts.add("node:" + id);
            return null;
        }

        Set<String> labels = mergeField("node:" + id + ":labels", base.getLabels(), ours.getLabels(),
                theirs.getLabels(), conflicts);
        Map<String, Object> properties = mergeProperties("node:" + id + ":property", base.getProperties(),
                ours.getProperties(), theirs.getProperties(), conflicts);
        if (labels == null || properties == null) return null;
        return new Node(id, labels, properties);
    }

    private Edge mergeEdge(long id,
                           Edge base,
                           Edge ours,
                           Edge theirs,
                           List<String> conflicts) {
        if (sameEntity(ours, theirs)) return ours;
        if (sameEntity(ours, base)) return theirs;
        if (sameEntity(theirs, base)) return ours;
        if (base == null || ours == null || theirs == null) {
            conflicts.add("edge:" + id);
            return null;
        }

        String type = mergeField("edge:" + id + ":type", base.getType(), ours.getType(), theirs.getType(), conflicts);
        Long start = mergeField("edge:" + id + ":start", base.getStartNodeId(), ours.getStartNodeId(),
                theirs.getStartNodeId(), conflicts);
        Long end = mergeField("edge:" + id + ":end", base.getEndNodeId(), ours.getEndNodeId(),
                theirs.getEndNodeId(), conflicts);
        Map<String, Object> properties = mergeProperties("edge:" + id + ":property", base.getProperties(),
                ours.getProperties(), theirs.getProperties(), conflicts);
        if (type == null || start == null || end == null || properties == null) return null;
        return new Edge(id, type, start, end, properties);
    }

    private static <T> T mergeField(String conflictPath,
                                    T base,
                                    T ours,
                                    T theirs,
                                    List<String> conflicts) {
        if (Objects.equals(ours, theirs)) return ours;
        if (Objects.equals(ours, base)) return theirs;
        if (Objects.equals(theirs, base)) return ours;
        conflicts.add(conflictPath);
        return null;
    }

    private static Map<String, Object> mergeProperties(String conflictPrefix,
                                                       Map<String, Object> base,
                                                       Map<String, Object> ours,
                                                       Map<String, Object> theirs,
                                                       List<String> conflicts) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(base.keySet());
        keys.addAll(ours.keySet());
        keys.addAll(theirs.keySet());
        Map<String, Object> merged = new LinkedHashMap<>();
        Object missing = new Object();
        for (String key : keys) {
            Object baseValue = base.containsKey(key) ? base.get(key) : missing;
            Object ourValue = ours.containsKey(key) ? ours.get(key) : missing;
            Object theirValue = theirs.containsKey(key) ? theirs.get(key) : missing;
            Object selected = mergeField(conflictPrefix + ":" + key, baseValue, ourValue, theirValue, conflicts);
            if (selected != missing) merged.put(key, selected);
        }
        return conflicts.stream().anyMatch(item -> item.startsWith(conflictPrefix + ":")) ? null : merged;
    }

    private static boolean sameEntity(Node left, Node right) {
        if (left == right) return true;
        return left != null && right != null
                && left.getId() == right.getId()
                && Objects.equals(left.getLabels(), right.getLabels())
                && Objects.equals(left.getProperties(), right.getProperties());
    }

    private static boolean sameEntity(Edge left, Edge right) {
        if (left == right) return true;
        return left != null && right != null
                && left.getId() == right.getId()
                && Objects.equals(left.getType(), right.getType())
                && left.getStartNodeId() == right.getStartNodeId()
                && left.getEndNodeId() == right.getEndNodeId()
                && Objects.equals(left.getProperties(), right.getProperties());
    }

    private static Map<Long, Node> indexNodes(List<Node> nodes) {
        Map<Long, Node> result = new LinkedHashMap<>();
        for (Node node : nodes) result.put(node.getId(), node);
        return result;
    }

    private static Map<Long, Edge> indexEdges(List<Edge> edges) {
        Map<Long, Edge> result = new LinkedHashMap<>();
        for (Edge edge : edges) result.put(edge.getId(), edge);
        return result;
    }

    private static <T> Set<Long> unionKeys(Map<Long, T> first, Map<Long, T> second, Map<Long, T> third) {
        Set<Long> result = new LinkedHashSet<>();
        result.addAll(first.keySet());
        result.addAll(second.keySet());
        result.addAll(third.keySet());
        return result;
    }

    private static final class MergeState {
        private final InMemoryGraphStore store;
        private final List<String> conflicts;

        private MergeState(InMemoryGraphStore store, List<String> conflicts) {
            this.store = store;
            this.conflicts = conflicts;
        }
    }

    // ==================== 内部：旧版整图快照读取 ====================

    /**
     * 读取旧格式（v2/v3）的内联整图快照。v3 起尾部带索引之外的 tag/edgeType schema。
     * 兼容路径只在迁移和读旧导出文件时走一次，因此允许保留逐条 addNode 的慢实现。
     */
    private static InMemoryGraphStore readLegacySnapshot(DataInputStream in, int version) throws IOException {
        InMemoryGraphStore graph = new InMemoryGraphStore();
        int nodeCount = in.readInt();
        for (int i = 0; i < nodeCount; i++) {
            long id = in.readLong();
            List<String> labels = readStringList(in);
            graph.addNode(id, labels, GraphCodec.readMap(in));
        }
        int edgeCount = in.readInt();
        for (int i = 0; i < edgeCount; i++) {
            long id = in.readLong();
            String type = GraphCodec.readString(in);
            long start = in.readLong();
            long end = in.readLong();
            graph.addEdge(id, type, start, end, GraphCodec.readMap(in));
        }
        int indexCount = in.readInt();
        for (int i = 0; i < indexCount; i++) {
            graph.createPropertyIndex(GraphCodec.readString(in), GraphCodec.readString(in));
        }
        if (version >= 3) {
            int tagCount = in.readInt();
            for (int i = 0; i < tagCount; i++) {
                graph.putTagSchema(readLegacyTagSchema(in));
            }
            int edgeTypeCount = in.readInt();
            for (int i = 0; i < edgeTypeCount; i++) {
                graph.putEdgeTypeSchema(readLegacyEdgeTypeSchema(in));
            }
        }
        return graph;
    }

    private static List<String> readStringList(DataInputStream in) throws IOException {
        int count = in.readInt();
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(GraphCodec.readString(in));
        return values;
    }

    private static TagSchema readLegacyTagSchema(DataInputStream in) throws IOException {
        String name = GraphCodec.readString(in);
        int fieldCount = in.readInt();
        List<TagSchema.Field> fields = new ArrayList<>(fieldCount);
        for (int i = 0; i < fieldCount; i++) {
            fields.add(new TagSchema.Field(GraphCodec.readString(in),
                    TagSchema.DataType.valueOf(GraphCodec.readString(in)), in.readBoolean()));
        }
        return new TagSchema(name, fields);
    }

    private static EdgeTypeSchema readLegacyEdgeTypeSchema(DataInputStream in) throws IOException {
        String name = GraphCodec.readString(in);
        int fieldCount = in.readInt();
        List<TagSchema.Field> fields = new ArrayList<>(fieldCount);
        for (int i = 0; i < fieldCount; i++) {
            fields.add(new TagSchema.Field(GraphCodec.readString(in),
                    TagSchema.DataType.valueOf(GraphCodec.readString(in)), in.readBoolean()));
        }
        return new EdgeTypeSchema(name, fields);
    }
}
