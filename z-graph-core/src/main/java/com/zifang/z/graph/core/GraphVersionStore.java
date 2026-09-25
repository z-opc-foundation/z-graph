package com.zifang.z.graph.core;

import com.zifang.z.graph.api.Edge;
import com.zifang.z.graph.api.GraphCommit;
import com.zifang.z.graph.api.GraphMergeResult;
import com.zifang.z.graph.api.GraphStore;
import com.zifang.z.graph.api.Node;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
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

/**
 * MVCC 版本化图存储。
 *
 * <p>与"每个 commit 存一份整图副本"的做法不同，这里每个 commit 只登记相对第一父提交的
 * 增量 {@link GraphDelta}，同时把被触碰过的节点/边追加进各自的版本链
 * （{@link GraphEntityVersion}）。因此：</p>
 *
 * <ul>
 *   <li>写入成本是 O(本次变更量)，与图的规模无关；</li>
 *   <li>{@link #checkout(String)} 仍然给出任意历史 commit 上的完整视图，
 *       视图由最近的固定检查点向前回放增量物化得到，并按 LRU 缓存复用；</li>
 *   <li>{@link #nodeVersions(long)} 暴露单个节点的多版本历史，
 *       {@link #nodeVersionAt(long, String)} 给出该节点在指定 commit 上的版本。</li>
 * </ul>
 *
 * <p>物化视图一旦发布就不再改写：后续提交只会产生新视图，所以已经交出去的
 * checkout 不会被追溯修改。提交后直接把写事务的覆盖层认领为该 commit 的视图，
 * 读要穿透每一层，所以套叠到 {@code viewLayerLimit} 层或到达检查点深度时才摊平一次。
 * 缓存视图受 {@code maxRetainedViews}（数量）和 {@code maxRetainedEntities}（实体数）
 * 双重上限约束，分支 head 的视图不参与淘汰。</p>
 */
public final class GraphVersionStore {

    /** 每隔多少个第一父深度保留一个全量物化检查点。 */
    public static final int DEFAULT_CHECKPOINT_INTERVAL = 32;
    /** LRU 里最多驻留多少个物化视图（分支 head 不计入该上限）。 */
    public static final int DEFAULT_MAX_RETAINED_VIEWS = 64;
    /**
     * 缓存视图合计最多承载多少个实体（平铺视图按整图规模计费，套叠的覆盖层只按本层增量计费）。
     * 视图数量对内存没有意义：一张 20 万节点的图驻留 64 份视图是几十 GB，一张 64 个节点的图
     * 才是小事，所以数量上限之外还要按实体数封顶。
     */
    public static final long DEFAULT_MAX_RETAINED_ENTITIES = 200_000L;
    /**
     * head 视图最多套叠多少层覆盖层。提交后直接把写事务的覆盖层当作新 commit 的视图，
     * 省掉一次整图复制；但读要穿透每一层，超过该层数就摊平一次。
     */
    public static final int DEFAULT_VIEW_LAYER_LIMIT = 8;

    private static final InMemoryGraphStore EMPTY_VIEW = new InMemoryGraphStore();

    private final Map<String, GraphCommit> commits = new LinkedHashMap<>();
    private final Map<String, GraphDelta> deltas = new LinkedHashMap<>();
    private final Map<String, Integer> firstParentDepth = new LinkedHashMap<>();
    private final Map<String, Long> commitSequenceById = new LinkedHashMap<>();
    private final Map<String, String> branches = new LinkedHashMap<>();
    private final LinkedHashMap<String, GraphStore> views = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Long, List<GraphEntityVersion>> nodeVersions = new LinkedHashMap<>();
    private final Map<Long, List<GraphEntityVersion>> edgeVersions = new LinkedHashMap<>();

    private final Path storageDirectory;
    private long sequence;
    private long nextNodeId;
    private long nextEdgeId;

    private int checkpointInterval = DEFAULT_CHECKPOINT_INTERVAL;
    private int maxRetainedViews = DEFAULT_MAX_RETAINED_VIEWS;
    private long maxRetainedEntities = DEFAULT_MAX_RETAINED_ENTITIES;
    private int viewLayerLimit = DEFAULT_VIEW_LAYER_LIMIT;
    private boolean eagerCheckpoints = true;

    public GraphVersionStore() {
        this(null);
    }

    /**
     * 打开一个可选的文件仓库。非 null 时，每个 commit 的元数据和增量各自落盘一次
     * （{@code objects/<commit>.bin}，写完不再改写），分支指针和序号写在
     * {@code repository.bin}，进程重启后可以恢复提交图、版本链和全部历史视图。
     */
    public GraphVersionStore(Path storageDirectory) {
        this.storageDirectory = storageDirectory;
        if (storageDirectory != null && loadState()) {
            return;
        }
        GraphCommit root = createCommit(List.of(), "main", "system", "Initial graph",
                new GraphDelta().freeze(), 0, 0);
        branches.put("main", root.getId());
        persistHeader();
    }

    // ==================== 调参 ====================

    public Path getStorageDirectory() {
        return storageDirectory;
    }

    /** 设置检查点间隔；越小则回放越短、驻留内存越多。必须小于 1 时使用默认值。 */
    public synchronized GraphVersionStore withCheckpointInterval(int interval) {
        this.checkpointInterval = Math.max(1, interval);
        return this;
    }

    /** 设置 LRU 里可驻留的非固定视图数。 */
    public synchronized GraphVersionStore withMaxRetainedViews(int maxViews) {
        this.maxRetainedViews = Math.max(1, maxViews);
        return this;
    }

    /** 设置缓存视图合计可承载的实体数上限；越小越省内存、历史读回放越长。 */
    public synchronized GraphVersionStore withMaxRetainedEntities(long maxEntities) {
        this.maxRetainedEntities = Math.max(1, maxEntities);
        return this;
    }

    /** head 视图套叠多少层覆盖层后强制摊平一次；1 等于每次都摊平（旧行为）。 */
    public synchronized GraphVersionStore withViewLayerLimit(int layers) {
        this.viewLayerLimit = Math.max(1, layers);
        return this;
    }

    /** 关闭后提交不再主动物化检查点，读取历史时再按需回放（更省内存、读更慢）。 */
    public synchronized GraphVersionStore withEagerCheckpoints(boolean enabled) {
        this.eagerCheckpoints = enabled;
        return this;
    }

    public synchronized int getCheckpointInterval() {
        return checkpointInterval;
    }

    public synchronized int getMaxRetainedViews() {
        return maxRetainedViews;
    }

    // ==================== 提交图 ====================

    /** 返回当前分支 head；仓库默认创建 main 分支。 */
    public synchronized GraphCommit getBranchHead(String branch) {
        return requireCommit(branches.get(requireBranch(branch)), "branch=" + branch);
    }

    public synchronized GraphCommit getCommit(String commitId) {
        return requireCommit(commitId, "commit=" + commitId);
    }

    public synchronized List<GraphCommit> listCommits() {
        return List.copyOf(commits.values());
    }

    public synchronized List<String> listBranches() {
        return List.copyOf(branches.keySet());
    }

    /** 返回 ref 的第一父链日志，ref 可以是 branch 名或 commit ID。 */
    public synchronized List<GraphCommit> log(String ref) {
        String headId = branches.containsKey(ref) ? branches.get(ref) : requireCommit(ref, "commit=" + ref).getId();
        List<GraphCommit> history = new ArrayList<>();
        String current = headId;
        while (current != null) {
            GraphCommit commit = requireCommit(current, "commit=" + current);
            history.add(commit);
            current = commit.getParents().isEmpty() ? null : commit.getParents().get(0);
        }
        return List.copyOf(history);
    }

    public synchronized GraphCommit createBranch(String branch, String fromCommitId) {
        validateBranchName(branch);
        if (branches.containsKey(branch)) {
            throw new IllegalArgumentException("Branch already exists: " + branch);
        }
        GraphCommit base = requireCommit(fromCommitId, "commit=" + fromCommitId);
        branches.put(branch, base.getId());
        persistHeader();
        return base;
    }

    // ==================== 写事务与视图 ====================

    public synchronized GraphWriteTransaction beginWrite(String branch) {
        String headId = branches.get(requireBranch(branch));
        requireCommit(headId, "branch=" + branch);
        // 覆盖层只从 base 读、写入都落在自己的暂存区，所以 head 视图已在缓存时直接共享，
        // 哪怕它本身还套叠着前几次提交的覆盖层；未命中才回放物化一次。
        GraphStore base = cachedView(headId);
        VersionOverlayStore overlay = new VersionOverlayStore(base, new VersionOverlayStore.IdAllocator() {
            @Override
            public long nextNodeId() {
                return allocateNodeId();
            }

            @Override
            public long nextEdgeId() {
                return allocateEdgeId();
            }
        });
        return new GraphWriteTransaction(this, branch, headId, overlay);
    }

    /** 打开某个 commit 上的视图；返回的 checkout 绑定在该 commit，不随分支继续变化。 */
    public synchronized GraphCheckout checkout(String commitId) {
        GraphCommit commit = requireCommit(commitId, "commit=" + commitId);
        return new GraphCheckout(commit, materializeFlat(commitId));
    }

    public synchronized GraphCheckout checkoutBranch(String branch) {
        return checkout(getBranchHead(branch).getId());
    }

    /**
     * 物化指定 commit 的整图视图。相同 commit 复用缓存，因此反复打开同一历史视图
     * 不会重复回放增量。返回的实例是共享的不可变视图，调用方不得改写。
     */
    synchronized InMemoryGraphStore materializeView(String commitId) {
        requireCommit(commitId, "commit=" + commitId);
        return materializeFlat(commitId);
    }

    private GraphStore cachedView(String commitId) {
        GraphStore cached = views.get(commitId);
        return cached == null ? materializeFlat(commitId) : cached;
    }

    /**
     * 回放出一份平铺视图：沿第一父链走到最近的已缓存平铺视图，复制它再按顺序套用沿途增量。
     * 途中遇到的套叠覆盖层会被这份平铺视图替换掉（两者内容相同，平铺版读得更快）。
     */
    private InMemoryGraphStore materializeFlat(String commitId) {
        if (views.get(commitId) instanceof InMemoryGraphStore cached) {
            return cached;
        }
        List<String> chain = new ArrayList<>();
        String cursor = commitId;
        while (cursor != null && !(views.get(cursor) instanceof InMemoryGraphStore)) {
            chain.add(cursor);
            GraphCommit commit = commits.get(cursor);
            cursor = commit == null || commit.getParents().isEmpty() ? null : commit.getParents().get(0);
        }
        InMemoryGraphStore view = cursor == null
                ? EMPTY_VIEW.copy()
                : ((InMemoryGraphStore) views.get(cursor)).copy();
        for (int i = chain.size() - 1; i >= 0; i--) {
            view.applyDelta(deltas.get(chain.get(i)));
        }
        registerView(commitId, view);
        return view;
    }

    /**
     * 提交后给新 commit 登记视图。写事务的覆盖层本身已经合并好了 base 和本次增量，
     * 直接拿它当视图就能让提交成本留在 O(变更量)；只有到检查点、或套叠层数触顶
     * （读要穿透每一层）时才摊平一次，把整图复制摊到多次提交上。
     */
    private void registerCommittedView(String commitId, VersionOverlayStore committed) {
        if (!isCheckpoint(commitId) && committed.viewDepth() <= viewLayerLimit) {
            registerView(commitId, committed);
            return;
        }
        materializeFlat(commitId);
    }

    private void registerView(String commitId, GraphStore view) {
        views.put(commitId, view);
        evictViews();
    }

    private void evictViews() {
        // 分支 head 的视图不参与淘汰：淘汰它会让下一次 beginWrite 退化回整图回放。
        // 被换出的平铺父视图仍可能被某个在世的套叠子视图引用着而留在堆上，
        // 因此实际驻留约为「预算 + 每个分支一份整图」。
        List<String> candidates = new ArrayList<>();
        long retained = 0;
        for (Map.Entry<String, GraphStore> entry : views.entrySet()) {
            retained += entityWidth(entry.getValue());
            if (!branches.containsValue(entry.getKey())) candidates.add(entry.getKey());
        }
        // views 是 accessOrder 链表，遍历顺序即"最久未用在前"。
        int excessByCount = candidates.size() - maxRetainedViews;
        long excessByEntities = retained - maxRetainedEntities;
        int index = 0;
        while (index < candidates.size() && (index < excessByCount || excessByEntities > 0)) {
            String victim = candidates.get(index++);
            excessByEntities -= entityWidth(views.get(victim));
            views.remove(victim);
        }
    }

    private static long entityWidth(GraphStore view) {
        return VersionOverlayStore.billedEntities(view);
    }

    private boolean isCheckpoint(String commitId) {
        Integer depth = firstParentDepth.get(commitId);
        return depth != null && depth % checkpointInterval == 0;
    }

    // ==================== 节点多版本 ====================

    /** 节点的全部版本，按提交先后排列；节点从未出现在仓库里时为空。 */
    public synchronized List<GraphEntityVersion> nodeVersions(long nodeId) {
        return List.copyOf(nodeVersions.getOrDefault(nodeId, List.of()));
    }

    /** 边的全部版本，按提交先后排列。 */
    public synchronized List<GraphEntityVersion> edgeVersions(long edgeId) {
        return List.copyOf(edgeVersions.getOrDefault(edgeId, List.of()));
    }

    /**
     * 节点在指定 ref（分支名或 commit ID）上的版本：沿祖先链找到最近一次真正改动过
     * 该节点的提交。返回空表示该 ref 的历史里从未登记过这个节点的任何版本。
     */
    public synchronized java.util.Optional<GraphEntityVersion> nodeVersionAt(long nodeId, String ref) {
        return versionAt(nodeVersions.get(nodeId), ref, "ref=" + ref);
    }

    public synchronized java.util.Optional<GraphEntityVersion> edgeVersionAt(long edgeId, String ref) {
        return versionAt(edgeVersions.get(edgeId), ref, "ref=" + ref);
    }

    /** 节点在 ref 可见的版本链，按时间顺序。 */
    public synchronized List<GraphEntityVersion> nodeHistory(long nodeId, String ref) {
        Set<String> ancestry = ancestorsOfRef(ref);
        List<GraphEntityVersion> visible = new ArrayList<>();
        for (GraphEntityVersion version : nodeVersions.getOrDefault(nodeId, List.of())) {
            if (ancestry.contains(version.getCommitId())) visible.add(version);
        }
        return List.copyOf(visible);
    }

    private java.util.Optional<GraphEntityVersion> versionAt(List<GraphEntityVersion> chain,
                                                             String ref,
                                                             String description) {
        if (chain == null || chain.isEmpty()) {
            return java.util.Optional.empty();
        }
        Set<String> ancestry = ancestorsOfRef(ref);
        GraphEntityVersion best = null;
        for (GraphEntityVersion version : chain) {
            if (!ancestry.contains(version.getCommitId())) continue;
            if (best == null || version.getCommitSequence() > best.getCommitSequence()) {
                best = version;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("Ref has no version of this entity: " + description);
        }
        return java.util.Optional.of(best);
    }

    private Set<String> ancestorsOfRef(String ref) {
        String head = branches.containsKey(ref) ? branches.get(ref) : requireCommit(ref, "commit=" + ref).getId();
        Map<String, Integer> distances = ancestorDistances(head);
        return distances.keySet();
    }

    /** 仓库里登记过的实体版本总数，用于容量观测。所有计数一律以 long 输出，便于压测脚本直接取数。 */
    public synchronized Map<String, Object> versionStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        long deltaEntities = 0;
        long deltaDeletes = 0;
        for (GraphDelta delta : deltas.values()) {
            deltaEntities += delta.nodeUpserts().size() + delta.edgeUpserts().size();
            deltaDeletes += delta.nodeDeletes().size() + delta.edgeDeletes().size();
        }
        stats.put("commitCount", (long) commits.size());
        stats.put("branchCount", (long) branches.size());
        stats.put("versionedNodeCount", (long) nodeVersions.size());
        stats.put("versionedEdgeCount", (long) edgeVersions.size());
        stats.put("nodeVersionRecords", sumVersions(nodeVersions));
        stats.put("edgeVersionRecords", sumVersions(edgeVersions));
        stats.put("retainedDeltaEntities", deltaEntities);
        stats.put("retainedDeltaDeletes", deltaDeletes);
        stats.put("materializedViews", (long) views.size());
        long retainedViewEntities = 0;
        int maxViewLayers = 0;
        for (GraphStore view : views.values()) {
            retainedViewEntities += entityWidth(view);
            if (view instanceof VersionOverlayStore layered) {
                maxViewLayers = Math.max(maxViewLayers, layered.viewDepth());
            }
        }
        stats.put("retainedViewEntities", retainedViewEntities);
        stats.put("maxViewLayers", (long) maxViewLayers);
        stats.put("checkpointInterval", (long) checkpointInterval);
        stats.put("maxRetainedViews", (long) maxRetainedViews);
        stats.put("maxRetainedEntities", maxRetainedEntities);
        stats.put("viewLayerLimit", (long) viewLayerLimit);
        return stats;
    }

    private static long sumVersions(Map<Long, List<GraphEntityVersion>> chains) {
        long total = 0;
        for (List<GraphEntityVersion> chain : chains.values()) total += chain.size();
        return total;
    }

    // ==================== 快照导入导出 ====================

    /** 把指定 commit 的视图导出为独立文件，便于备份/迁移。 */
    public synchronized Path exportSnapshot(String commitId, Path target) throws IOException {
        requireCommit(commitId, "commit=" + commitId);
        GraphDelta full = GraphDelta.between(EMPTY_VIEW, materializeFlat(commitId));
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (OutputStream output = Files.newOutputStream(temp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
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
        String headId = branches.get(branch);
        InMemoryGraphStore target = EMPTY_VIEW.copy();
        target.applyDelta(full);
        GraphDelta replacement = GraphDelta.between(materializeFlat(headId), target);
        GraphCommit commit = createCommit(List.of(headId), branch, author, message, replacement.freeze(),
                target.getNodeCount(), target.getEdgeCount());
        branches.put(branch, commit.getId());
        persistHeader();
        return commit;
    }

    // ==================== 合并 ====================

    /**
     * 将 source branch 合并到 target branch。只自动合并三方模型中一侧发生变化的实体；
     * 同一节点/边两侧都发生不一致修改时返回冲突，且不会移动 target head。
     */
    public synchronized GraphMergeResult merge(String targetBranch,
                                               String sourceBranch,
                                               String author,
                                               String message) {
        requireBranch(targetBranch);
        requireBranch(sourceBranch);
        String targetHeadId = branches.get(targetBranch);
        String sourceHeadId = branches.get(sourceBranch);
        if (Objects.equals(targetHeadId, sourceHeadId)) {
            return new GraphMergeResult(false, targetHeadId, commits.get(targetHeadId), List.of());
        }

        Map<String, Integer> targetAncestors = ancestorDistances(targetHeadId);
        String baseId = closestCommonAncestor(sourceHeadId, targetAncestors);
        if (baseId == null) {
            throw new IllegalStateException("Branches do not have a common ancestor");
        }
        if (Objects.equals(baseId, sourceHeadId)) {
            // source 已经包含 target 的全部历史，执行 fast-forward。
            branches.put(targetBranch, sourceHeadId);
            persistHeader();
            return new GraphMergeResult(true, baseId, commits.get(sourceHeadId), List.of());
        }

        InMemoryGraphStore base = materializeFlat(baseId);
        InMemoryGraphStore ours = materializeFlat(targetHeadId);
        InMemoryGraphStore theirs = materializeFlat(sourceHeadId);
        MergeState merged = mergeSnapshots(base, ours, theirs);
        if (!merged.conflicts.isEmpty()) {
            return new GraphMergeResult(false, baseId, null, merged.conflicts);
        }

        GraphDelta mergeDelta = GraphDelta.between(ours, merged.store).freeze();
        GraphCommit mergeCommit = createCommit(List.of(targetHeadId, sourceHeadId), targetBranch, author, message,
                mergeDelta, merged.store.getNodeCount(), merged.store.getEdgeCount());
        branches.put(targetBranch, mergeCommit.getId());
        persistHeader();
        return new GraphMergeResult(true, baseId, mergeCommit, List.of());
    }

    /**
     * 写事务的提交入口：登记增量和版本链，并把事务自己的覆盖层认领成新 commit 的视图，
     * 因此提交成本是 O(本次变更量)，与图的规模无关。
     */
    synchronized GraphCommit commit(String branch,
                                    String baseCommitId,
                                    GraphDelta delta,
                                    long nodeCount,
                                    long edgeCount,
                                    String author,
                                    String message,
                                    VersionOverlayStore committedView) {
        requireBranch(branch);
        String currentHeadId = branches.get(branch);
        if (!Objects.equals(currentHeadId, baseCommitId)) {
            throw new StaleHeadException(
                    "Branch advanced since transaction started: " + branch
                            + " (expected " + baseCommitId + ", actual " + currentHeadId + ")");
        }
        GraphCommit commit = createCommit(List.of(baseCommitId), branch, author, message, delta, nodeCount, edgeCount);
        branches.put(branch, commit.getId());
        // 先移动 head 再登记视图：淘汰规则要按"是否还是某个分支的 head"决定谁能被换出。
        registerCommittedView(commit.getId(), committedView);
        persistHeader();
        return commit;
    }

    /**
     * 当并发事务提交时，branch head 已被推进，提示调用方放弃或重试。
     */
    public static class StaleHeadException extends IllegalStateException {
        public StaleHeadException(String message) { super(message); }
    }

    synchronized long allocateNodeId() {
        return nextNodeId++;
    }

    synchronized long allocateEdgeId() {
        return nextEdgeId++;
    }

    synchronized void reserveNodeId(long id) {
        nextNodeId = Math.max(nextNodeId, id + 1);
    }

    synchronized void reserveEdgeId(long id) {
        nextEdgeId = Math.max(nextEdgeId, id + 1);
    }

    // ==================== 回收 ====================

    /**
     * 回收不再被任何保留分支引用的 commit：删掉它们的增量、版本记录和物化视图。
     * 保留分支的 head 以及它们的全部祖先都会保留，因此回收后这些分支上的历史视图
     * 仍然可以按 commit 打开。
     *
     * @return 被回收的 commit 数量
     */
    public synchronized int garbageCollect(String... keepBranches) {
        Set<String> keep = keepBranches == null || keepBranches.length == 0
                ? new LinkedHashSet<>(branches.keySet())
                : new LinkedHashSet<>(List.of(keepBranches));
        Set<String> reachable = new HashSet<>();
        for (String branch : keep) {
            String head = branches.get(requireBranch(branch));
            reachable.addAll(ancestorDistances(head).keySet());
        }
        List<String> collected = new ArrayList<>();
        for (String id : commits.keySet()) {
            if (!reachable.contains(id)) collected.add(id);
        }
        for (String id : collected) {
            commits.remove(id);
            deltas.remove(id);
            firstParentDepth.remove(id);
            commitSequenceById.remove(id);
            views.remove(id);
            dropVersionRecords(id);
            deleteCommitObject(id);
        }
        // 指针必须和 commit 一起消失，否则 repository.bin 会引用已删除的对象文件。
        branches.entrySet().removeIf(entry -> !reachable.contains(entry.getValue()));
        if (!collected.isEmpty()) {
            persistHeader();
        }
        return collected.size();
    }

    private void dropVersionRecords(String commitId) {
        pruneChain(nodeVersions, commitId);
        pruneChain(edgeVersions, commitId);
    }

    private static void pruneChain(Map<Long, List<GraphEntityVersion>> chains, String commitId) {
        chains.entrySet().removeIf(entry -> {
            entry.getValue().removeIf(version -> version.getCommitId().equals(commitId));
            return entry.getValue().isEmpty();
        });
    }

    /** 当前仍可从分支 head 到达的 commit 数。 */
    public synchronized int reachableCommitCount() {
        Set<String> reachable = new HashSet<>();
        for (String head : branches.values()) {
            reachable.addAll(ancestorDistances(head).keySet());
        }
        return reachable.size();
    }

    // ==================== 内部：提交与版本链 ====================

    private GraphCommit createCommit(List<String> parents,
                                     String branch,
                                     String author,
                                     String message,
                                     GraphDelta delta,
                                     long nodeCount,
                                     long edgeCount) {
        long commitSequence = ++sequence;
        String id = createCommitId(parents, branch, author, message, commitSequence, nodeCount, edgeCount);
        GraphCommit commit = new GraphCommit(id, parents, branch, author, message,
                System.currentTimeMillis(), nodeCount, edgeCount);
        commits.put(id, commit);
        deltas.put(id, delta.isFrozen() ? delta : delta.freeze());
        firstParentDepth.put(id, parents.isEmpty() ? 0 : depthOf(parents.get(0)) + 1);
        commitSequenceById.put(id, commitSequence);
        recordVersions(commit, id, commitSequence, delta);
        persistCommitObject(commit, delta);
        if (eagerCheckpoints && isCheckpoint(id)) {
            materializeFlat(id);
        }
        return commit;
    }

    private int depthOf(String commitId) {
        Integer depth = firstParentDepth.get(commitId);
        return depth == null ? 0 : depth;
    }

    private void recordVersions(GraphCommit commit, String id, long commitSequence, GraphDelta delta) {
        for (Node node : delta.nodeUpserts()) {
            nodeVersions.computeIfAbsent(node.getId(), ignored -> new ArrayList<>())
                    .add(new GraphEntityVersion(node.getId(), GraphEntityVersion.Kind.UPSERT, id,
                            commitSequence, commit.getTimestampEpochMillis(), node, null));
        }
        for (long nodeId : delta.nodeDeletes()) {
            nodeVersions.computeIfAbsent(nodeId, ignored -> new ArrayList<>())
                    .add(new GraphEntityVersion(nodeId, GraphEntityVersion.Kind.DELETE, id,
                            commitSequence, commit.getTimestampEpochMillis(), null, null));
        }
        for (Edge edge : delta.edgeUpserts()) {
            edgeVersions.computeIfAbsent(edge.getId(), ignored -> new ArrayList<>())
                    .add(new GraphEntityVersion(edge.getId(), GraphEntityVersion.Kind.UPSERT, id,
                            commitSequence, commit.getTimestampEpochMillis(), null, edge));
        }
        for (long edgeId : delta.edgeDeletes()) {
            edgeVersions.computeIfAbsent(edgeId, ignored -> new ArrayList<>())
                    .add(new GraphEntityVersion(edgeId, GraphEntityVersion.Kind.DELETE, id,
                            commitSequence, commit.getTimestampEpochMillis(), null, null));
        }
    }

    private String createCommitId(List<String> parents,
                                  String branch,
                                  String author,
                                  String message,
                                  long commitSequence,
                                  long nodeCount,
                                  long edgeCount) {
        String payload = String.join("\n", parents) + "\n" + String.join("\n",
                branch == null ? "" : branch,
                author == null ? "" : author,
                message == null ? "" : message,
                Long.toString(commitSequence),
                Long.toString(nodeCount),
                Long.toString(edgeCount));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                result.append(String.format("%02x", item));
            }
            return result.substring(0, 40);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK does not provide SHA-256", e);
        }
    }

    // ==================== 内部：三方合并 ====================

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

    private Map<String, Integer> ancestorDistances(String startId) {
        Map<String, Integer> distances = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        Deque<Integer> depth = new ArrayDeque<>();
        queue.add(startId);
        depth.add(0);
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            int currentDepth = depth.removeFirst();
            Integer knownDepth = distances.putIfAbsent(id, currentDepth);
            if (knownDepth != null && knownDepth <= currentDepth) continue;
            GraphCommit commit = commits.get(id);
            if (commit == null) continue;
            for (String parent : commit.getParents()) {
                queue.addLast(parent);
                depth.addLast(currentDepth + 1);
            }
        }
        return distances;
    }

    private String closestCommonAncestor(String sourceId, Map<String, Integer> targetAncestors) {
        Map<String, Integer> sourceAncestors = ancestorDistances(sourceId);
        return sourceAncestors.entrySet().stream()
                .filter(entry -> targetAncestors.containsKey(entry.getKey()))
                .min(Comparator.comparingInt(entry -> entry.getValue() + targetAncestors.get(entry.getKey())))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    // ==================== 内部：落盘 ====================

    private Path objectsDirectory() {
        return storageDirectory.resolve("objects");
    }

    private Path commitObjectPath(String commitId) {
        return objectsDirectory().resolve(commitId + ".bin");
    }

    private void persistCommitObject(GraphCommit commit, GraphDelta delta) {
        if (storageDirectory == null) {
            return;
        }
        Path temp = commitObjectPath(commit.getId() + ".tmp");
        try {
            Files.createDirectories(objectsDirectory());
            try (OutputStream output = Files.newOutputStream(temp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                 DataOutputStream out = new DataOutputStream(output)) {
                out.writeInt(GraphCodec.STORAGE_MAGIC);
                out.writeInt(GraphCodec.STORAGE_VERSION);
                GraphCodec.writeString(out, commit.getId());
                out.writeInt(commit.getParents().size());
                for (String parent : commit.getParents()) GraphCodec.writeString(out, parent);
                GraphCodec.writeString(out, commit.getBranch());
                GraphCodec.writeString(out, commit.getAuthor());
                GraphCodec.writeString(out, commit.getMessage());
                out.writeLong(commit.getTimestampEpochMillis());
                out.writeLong(commit.getNodeCount());
                out.writeLong(commit.getEdgeCount());
                out.writeLong(commitSequenceById.getOrDefault(commit.getId(), 0L));
                delta.writeTo(out);
            }
            moveIntoPlace(temp, commitObjectPath(commit.getId()));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot persist z-graph commit: " + commit.getId(), e);
        }
    }

    private void deleteCommitObject(String commitId) {
        if (storageDirectory == null) {
            return;
        }
        try {
            Files.deleteIfExists(commitObjectPath(commitId));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot delete z-graph commit object: " + commitId, e);
        }
    }

    private void persistHeader() {
        if (storageDirectory == null) {
            return;
        }
        Path stateFile = storageDirectory.resolve("repository.bin");
        Path tempFile = storageDirectory.resolve("repository.bin.tmp");
        try {
            Files.createDirectories(storageDirectory);
            try (OutputStream output = Files.newOutputStream(tempFile,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                 DataOutputStream out = new DataOutputStream(output)) {
                out.writeInt(GraphCodec.STORAGE_MAGIC);
                out.writeInt(GraphCodec.STORAGE_VERSION);
                out.writeLong(sequence);
                out.writeLong(nextNodeId);
                out.writeLong(nextEdgeId);
                out.writeInt(branches.size());
                for (Map.Entry<String, String> branch : branches.entrySet()) {
                    GraphCodec.writeString(out, branch.getKey());
                    GraphCodec.writeString(out, branch.getValue());
                }
                out.writeInt(commits.size());
                for (String commitId : commits.keySet()) GraphCodec.writeString(out, commitId);
            }
            moveIntoPlace(tempFile, stateFile);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot persist z-graph repository: " + stateFile, e);
        }
    }

    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean loadState() {
        Path stateFile = storageDirectory.resolve("repository.bin");
        if (!Files.exists(stateFile)) {
            return false;
        }
        List<String> commitIds = new ArrayList<>();
        try (InputStream input = Files.newInputStream(stateFile);
             DataInputStream in = new DataInputStream(input)) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != GraphCodec.STORAGE_MAGIC) {
                throw new IllegalStateException("Unsupported z-graph repository format: " + stateFile);
            }
            if (version < GraphCodec.LEGACY_STORAGE_VERSION || version > GraphCodec.STORAGE_VERSION) {
                throw new IllegalStateException("Unsupported z-graph repository version: " + version);
            }
            if (version < GraphCodec.STORAGE_VERSION) {
                return loadLegacyRepository(in, version);
            }
            sequence = in.readLong();
            nextNodeId = in.readLong();
            nextEdgeId = in.readLong();

            int branchCount = in.readInt();
            for (int i = 0; i < branchCount; i++) {
                branches.put(GraphCodec.readString(in), GraphCodec.readString(in));
            }
            int commitCount = in.readInt();
            for (int i = 0; i < commitCount; i++) commitIds.add(GraphCodec.readString(in));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load z-graph repository: " + stateFile, e);
        }

        for (String commitId : commitIds) {
            Path object = commitObjectPath(commitId);
            if (!Files.exists(object)) {
                throw new IllegalStateException("Missing z-graph commit object: " + commitId);
            }
            try (InputStream input = Files.newInputStream(object);
                 DataInputStream in = new DataInputStream(input)) {
                readCommitObject(in);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot load z-graph commit object: " + object, e);
            }
        }
        rebuildDependentState();
        return !commits.isEmpty() && !branches.isEmpty();
    }

    private void readCommitObject(DataInputStream in) throws IOException {
        int magic = in.readInt();
        int version = in.readInt();
        if (magic != GraphCodec.STORAGE_MAGIC || version != GraphCodec.STORAGE_VERSION) {
            throw new IOException("Unexpected z-graph commit object header");
        }
        String id = GraphCodec.readString(in);
        int parentCount = in.readInt();
        List<String> parents = new ArrayList<>(parentCount);
        for (int i = 0; i < parentCount; i++) parents.add(GraphCodec.readString(in));
        String branch = GraphCodec.readString(in);
        String author = GraphCodec.readString(in);
        String message = GraphCodec.readString(in);
        long timestamp = in.readLong();
        long nodeCount = in.readLong();
        long edgeCount = in.readLong();
        long commitSequence = in.readLong();
        GraphDelta delta = GraphDelta.readFrom(in);

        GraphCommit commit = new GraphCommit(id, parents, branch, author, message, timestamp, nodeCount, edgeCount);
        commits.put(id, commit);
        deltas.put(id, delta);
        commitSequenceById.put(id, commitSequence);
    }

    /**
     * 加载路径补齐第一父深度和实体版本链。commit 对象里的序号是创建顺序，
     * 按它升序重放即可保证父提交先于子提交、版本链按时间排列。
     */
    private void rebuildDependentState() {
        List<GraphCommit> ordered = new ArrayList<>(commits.values());
        ordered.sort(Comparator.comparingLong(commit -> commitSequenceById.getOrDefault(commit.getId(), 0L)));
        for (GraphCommit commit : ordered) {
            List<String> parents = commit.getParents();
            firstParentDepth.put(commit.getId(), parents.isEmpty() ? 0 : depthOf(parents.get(0)) + 1);
            GraphDelta delta = deltas.get(commit.getId());
            if (delta != null) {
                recordVersions(commit, commit.getId(),
                        commitSequenceById.getOrDefault(commit.getId(), 0L), delta);
            }
        }
    }

    // ==================== 内部：旧版整图仓库 ====================

    private boolean loadLegacyRepository(DataInputStream in, int version) throws IOException {
        sequence = in.readLong();
        nextNodeId = in.readLong();
        nextEdgeId = in.readLong();

        int commitCount = in.readInt();
        List<GraphCommit> legacyCommits = new ArrayList<>(commitCount);
        for (int i = 0; i < commitCount; i++) {
            GraphCommit commit = readLegacyCommit(in);
            legacyCommits.add(commit);
            commits.put(commit.getId(), commit);
        }
        int branchCount = in.readInt();
        for (int i = 0; i < branchCount; i++) {
            branches.put(GraphCodec.readString(in), GraphCodec.readString(in));
        }
        Map<String, InMemoryGraphStore> snapshots = new LinkedHashMap<>();
        int snapshotCount = in.readInt();
        for (int i = 0; i < snapshotCount; i++) {
            snapshots.put(GraphCodec.readString(in), readLegacySnapshot(in, version));
        }

        // 旧格式按创建顺序内联保存，因此第 i 个提交的序号就是 i+1。
        for (int i = 0; i < legacyCommits.size(); i++) {
            GraphCommit commit = legacyCommits.get(i);
            InMemoryGraphStore snapshot = snapshots.get(commit.getId());
            if (snapshot == null) {
                throw new IllegalStateException("Legacy repository is missing snapshot " + commit.getId());
            }
            GraphCommit parent = commit.getParents().isEmpty() ? null : commits.get(commit.getParents().get(0));
            InMemoryGraphStore parentSnapshot = parent == null ? EMPTY_VIEW : snapshots.get(parent.getId());
            if (parentSnapshot == null) {
                throw new IllegalStateException("Legacy repository is missing snapshot "
                        + commit.getParents().get(0));
            }
            commitSequenceById.put(commit.getId(), i + 1L);
            deltas.put(commit.getId(), GraphDelta.between(parentSnapshot, snapshot).freeze());
        }
        rebuildDependentState();
        persistHeader();
        for (Map.Entry<String, GraphDelta> entry : deltas.entrySet()) {
            persistCommitObject(commits.get(entry.getKey()), entry.getValue());
        }
        return !commits.isEmpty() && !branches.isEmpty();
    }

    private static GraphCommit readLegacyCommit(DataInputStream in) throws IOException {
        String id = GraphCodec.readString(in);
        int parentCount = in.readInt();
        List<String> parents = new ArrayList<>(parentCount);
        for (int i = 0; i < parentCount; i++) parents.add(GraphCodec.readString(in));
        return new GraphCommit(id, parents, GraphCodec.readString(in), GraphCodec.readString(in),
                GraphCodec.readString(in), in.readLong(), in.readLong(), in.readLong());
    }

    /**
     * 读取旧格式（v2/v3）的内联整图快照。v3 起尾部带索引之外的 tag/edgeType schema。
     * 兼容路径只在迁移和读旧导出文件时走一次，因此允许保留逐条 addNode 的慢实现。
     */
    private static InMemoryGraphStore readLegacySnapshot(DataInputStream in, int version) throws IOException {
        InMemoryGraphStore graph = new InMemoryGraphStore();
        int nodeCount = in.readInt();
        for (int i = 0; i < nodeCount; i++) {
            long id = in.readLong();
            List<String> labels = GraphDelta.readStringList(in);
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
                graph.putTagSchema(GraphDelta.readTagSchema(in));
            }
            int edgeTypeCount = in.readInt();
            for (int i = 0; i < edgeTypeCount; i++) {
                graph.putEdgeTypeSchema(GraphDelta.readEdgeTypeSchema(in));
            }
        }
        return graph;
    }

    private static final class MergeState {
        private final InMemoryGraphStore store;
        private final List<String> conflicts;

        private MergeState(InMemoryGraphStore store, List<String> conflicts) {
            this.store = store;
            this.conflicts = conflicts;
        }
    }

    private GraphCommit requireCommit(String id, String description) {
        GraphCommit commit = commits.get(id);
        if (commit == null) {
            throw new IllegalArgumentException("Unknown " + description);
        }
        return commit;
    }

    private String requireBranch(String branch) {
        validateBranchName(branch);
        if (!branches.containsKey(branch)) {
            throw new IllegalArgumentException("Unknown branch: " + branch);
        }
        return branch;
    }

    private static void validateBranchName(String branch) {
        if (branch == null || branch.isBlank() || !branch.matches("[A-Za-z0-9._/-]+")) {
            throw new IllegalArgumentException("Invalid branch name: " + branch);
        }
    }
}
