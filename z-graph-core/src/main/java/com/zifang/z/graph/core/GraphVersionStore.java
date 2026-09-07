package com.zifang.z.graph.core;

import com.zifang.z.graph.api.Edge;
import com.zifang.z.graph.api.EdgeTypeSchema;
import com.zifang.z.graph.api.GraphCommit;
import com.zifang.z.graph.api.GraphMergeResult;
import com.zifang.z.graph.api.Node;
import com.zifang.z.graph.api.TagSchema;

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
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Git 风格的本地版本化图存储。
 *
 * <p>设计对应 NebulaGraph 的职责分层：{@link GraphWriteTransaction} 是写入入口，
 * {@link GraphCheckout} 是 Query 视图，提交元数据充当轻量 Meta 层。当前实现使用
 * 内存快照保证语义正确性，后续可以把快照替换为 WAL/LSM Storage 层，而不改变分支、
 * commit、merge API。</p>
 *
 * <p>每个 commit 都是不可变快照；分支只保存 head 指针。写事务从 head 深拷贝，提交时
 * 校验 head 没有被其他事务推进，避免静默覆盖并发写入。</p>
 */
public final class GraphVersionStore {

    private static final int STORAGE_MAGIC = 0x5A475246;
    private static final int STORAGE_VERSION = 3;

    private final Map<String, GraphCommit> commits = new LinkedHashMap<>();
    private final Map<String, InMemoryGraphStore> snapshots = new LinkedHashMap<>();
    private final Map<String, String> branches = new LinkedHashMap<>();
    private final Path storageDirectory;
    private long sequence;
    private long nextNodeId;
    private long nextEdgeId;

    public GraphVersionStore() {
        this(null);
    }

    /**
     * 打开一个可选的文件仓库。非 null 时，每个 commit、branch 和 merge head 都会原子写入磁盘，
     * 进程重启后可以恢复提交图和全部快照。
     */
    public GraphVersionStore(Path storageDirectory) {
        this.storageDirectory = storageDirectory;
        if (storageDirectory != null && loadState()) {
            return;
        }
        GraphCommit root = createCommit(List.of(), "main", "system", "Initial graph", new InMemoryGraphStore());
        branches.put("main", root.getId());
        persistState();
    }

    public Path getStorageDirectory() {
        return storageDirectory;
    }

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
        persistState();
        return base;
    }

    public synchronized GraphWriteTransaction beginWrite(String branch) {
        String headId = branches.get(requireBranch(branch));
        GraphCommit head = requireCommit(headId, "branch=" + branch);
        return new GraphWriteTransaction(this, branch, head.getId(), snapshots.get(head.getId()).copy());
    }

    public synchronized GraphCheckout checkout(String commitId) {
        GraphCommit commit = requireCommit(commitId, "commit=" + commitId);
        return new GraphCheckout(commit, snapshots.get(commit.getId()).copy());
    }

    public synchronized GraphCheckout checkoutBranch(String branch) {
        return checkout(getBranchHead(branch).getId());
    }

    /** 把指定 commit 的快照导出为独立文件，便于备份/迁移。 */
    public synchronized Path exportSnapshot(String commitId, Path target) throws IOException {
        InMemoryGraphStore snapshot = snapshots.get(commitId);
        if (snapshot == null) {
            throw new IllegalArgumentException("Unknown commit: " + commitId);
        }
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (OutputStream output = Files.newOutputStream(temp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             DataOutputStream out = new DataOutputStream(output)) {
            out.writeInt(STORAGE_MAGIC);
            out.writeInt(STORAGE_VERSION);
            writeSnapshot(out, snapshot);
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /**
     * 从 snapshot 文件导入：在当前 head 上新建一个 commit，把快照内容写进去。
     * 返回新产生的 commit，方便调用方继续推进分支。
     */
    public synchronized GraphCommit importSnapshot(Path source,
                                                   String branch,
                                                   String author,
                                                   String message) throws IOException {
        requireBranch(branch);
        try (InputStream input = Files.newInputStream(source);
             DataInputStream in = new DataInputStream(input)) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != STORAGE_MAGIC) {
                throw new IllegalArgumentException("Snapshot magic mismatch: " + source);
            }
            if (version < 2 || version > STORAGE_VERSION) {
                throw new IllegalArgumentException("Snapshot version unsupported: " + version);
            }
            InMemoryGraphStore snapshot = readSnapshot(in, version);
            GraphCommit commit = createCommit(List.of(branches.get(branch)), branch, author, message, snapshot);
            branches.put(branch, commit.getId());
            persistState();
            return commit;
        }
    }

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
            persistState();
            return new GraphMergeResult(true, baseId, commits.get(sourceHeadId), List.of());
        }

        InMemoryGraphStore base = snapshots.get(baseId);
        InMemoryGraphStore ours = snapshots.get(targetHeadId);
        InMemoryGraphStore theirs = snapshots.get(sourceHeadId);
        MergeState merged = mergeSnapshots(base, ours, theirs);
        if (!merged.conflicts.isEmpty()) {
            return new GraphMergeResult(false, baseId, null, merged.conflicts);
        }

        GraphCommit mergeCommit = createCommit(
                List.of(targetHeadId, sourceHeadId), targetBranch, author, message, merged.store);
        branches.put(targetBranch, mergeCommit.getId());
        persistState();
        return new GraphMergeResult(true, baseId, mergeCommit, List.of());
    }

    synchronized GraphCommit commit(String branch,
                                    String baseCommitId,
                                    InMemoryGraphStore workingStore,
                                    String author,
                                    String message) {
        requireBranch(branch);
        String currentHeadId = branches.get(branch);
        if (!Objects.equals(currentHeadId, baseCommitId)) {
            throw new StaleHeadException(
                    "Branch advanced since transaction started: " + branch
                            + " (expected " + baseCommitId + ", actual " + currentHeadId + ")");
        }
        GraphCommit commit = createCommit(List.of(baseCommitId), branch, author, message, workingStore);
        branches.put(branch, commit.getId());
        persistState();
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

    private boolean loadState() {
        Path stateFile = storageDirectory.resolve("repository.bin");
        if (!Files.exists(stateFile)) {
            return false;
        }
        try (InputStream input = Files.newInputStream(stateFile);
             DataInputStream in = new DataInputStream(input)) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != STORAGE_MAGIC) {
                throw new IllegalStateException("Unsupported z-graph repository format: " + stateFile);
            }
            if (version < 2 || version > STORAGE_VERSION) {
                throw new IllegalStateException("Unsupported z-graph repository version: " + version);
            }
            sequence = in.readLong();
            nextNodeId = in.readLong();
            nextEdgeId = in.readLong();

            int commitCount = in.readInt();
            for (int i = 0; i < commitCount; i++) {
                GraphCommit commit = readCommit(in);
                commits.put(commit.getId(), commit);
            }

            int branchCount = in.readInt();
            for (int i = 0; i < branchCount; i++) {
                branches.put(readString(in), readString(in));
            }

            int snapshotCount = in.readInt();
            for (int i = 0; i < snapshotCount; i++) {
                snapshots.put(readString(in), readSnapshot(in, version));
            }
            return !commits.isEmpty() && !branches.isEmpty();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load z-graph repository: " + stateFile, e);
        }
    }

    private void persistState() {
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
                out.writeInt(STORAGE_MAGIC);
                out.writeInt(STORAGE_VERSION);
                out.writeLong(sequence);
                out.writeLong(nextNodeId);
                out.writeLong(nextEdgeId);

                out.writeInt(commits.size());
                for (GraphCommit commit : commits.values()) writeCommit(out, commit);

                out.writeInt(branches.size());
                for (Map.Entry<String, String> branch : branches.entrySet()) {
                    writeString(out, branch.getKey());
                    writeString(out, branch.getValue());
                }

                out.writeInt(snapshots.size());
                for (Map.Entry<String, InMemoryGraphStore> snapshot : snapshots.entrySet()) {
                    writeString(out, snapshot.getKey());
                    writeSnapshot(out, snapshot.getValue());
                }
            }
            try {
                Files.move(tempFile, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempFile, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot persist z-graph repository: " + stateFile, e);
        }
    }

    private static void writeCommit(DataOutputStream out, GraphCommit commit) throws IOException {
        writeString(out, commit.getId());
        out.writeInt(commit.getParents().size());
        for (String parent : commit.getParents()) writeString(out, parent);
        writeString(out, commit.getBranch());
        writeString(out, commit.getAuthor());
        writeString(out, commit.getMessage());
        out.writeLong(commit.getTimestampEpochMillis());
        out.writeLong(commit.getNodeCount());
        out.writeLong(commit.getEdgeCount());
    }

    private static GraphCommit readCommit(DataInputStream in) throws IOException {
        String id = readString(in);
        int parentCount = in.readInt();
        List<String> parents = new ArrayList<>(parentCount);
        for (int i = 0; i < parentCount; i++) parents.add(readString(in));
        return new GraphCommit(id, parents, readString(in), readString(in), readString(in),
                in.readLong(), in.readLong(), in.readLong());
    }

    private static void writeSnapshot(DataOutputStream out, InMemoryGraphStore graph) throws IOException {
        List<Node> nodes = graph.getAllNodes();
        out.writeInt(nodes.size());
        for (Node node : nodes) {
            out.writeLong(node.getId());
            out.writeInt(node.getLabels().size());
            for (String label : node.getLabels()) writeString(out, label);
            writeValue(out, node.getProperties());
        }

        List<Edge> edges = graph.getAllEdges();
        out.writeInt(edges.size());
        for (Edge edge : edges) {
            out.writeLong(edge.getId());
            writeString(out, edge.getType());
            out.writeLong(edge.getStartNodeId());
            out.writeLong(edge.getEndNodeId());
            writeValue(out, edge.getProperties());
        }
        List<List<String>> indexes = graph.getPropertyIndexes();
        out.writeInt(indexes.size());
        for (List<String> index : indexes) {
            writeString(out, index.get(0));
            writeString(out, index.get(1));
        }

        // V3+: Tag / EdgeType schemas
        List<String> tags = graph.listTags();
        out.writeInt(tags.size());
        for (String tag : tags) {
            TagSchema schema = graph.getTagSchema(tag);
            writeString(out, schema.getName());
            out.writeInt(schema.getFields().size());
            for (TagSchema.Field field : schema.getFields()) {
                writeString(out, field.getName());
                writeString(out, field.getType().name());
                out.writeBoolean(field.isNullable());
            }
        }
        List<String> edgeTypes = graph.listEdgeTypes();
        out.writeInt(edgeTypes.size());
        for (String edgeType : edgeTypes) {
            EdgeTypeSchema schema = graph.getEdgeTypeSchema(edgeType);
            writeString(out, schema.getName());
            out.writeInt(schema.getFields().size());
            for (TagSchema.Field field : schema.getFields()) {
                writeString(out, field.getName());
                writeString(out, field.getType().name());
                out.writeBoolean(field.isNullable());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static InMemoryGraphStore readSnapshot(DataInputStream in, int version) throws IOException {
        InMemoryGraphStore graph = new InMemoryGraphStore();
        int nodeCount = in.readInt();
        for (int i = 0; i < nodeCount; i++) {
            long id = in.readLong();
            int labelCount = in.readInt();
            List<String> labels = new ArrayList<>(labelCount);
            for (int j = 0; j < labelCount; j++) labels.add(readString(in));
            graph.addNode(id, labels, (Map<String, Object>) readValue(in));
        }
        int edgeCount = in.readInt();
        for (int i = 0; i < edgeCount; i++) {
            graph.addEdge(in.readLong(), readString(in), in.readLong(), in.readLong(),
                    (Map<String, Object>) readValue(in));
        }
        int indexCount = in.readInt();
        for (int i = 0; i < indexCount; i++) {
            graph.createPropertyIndex(readString(in), readString(in));
        }
        if (version >= 3) {
            int tagCount = in.readInt();
            for (int i = 0; i < tagCount; i++) {
                String tagName = readString(in);
                int fieldCount = in.readInt();
                List<TagSchema.Field> fields = new ArrayList<>(fieldCount);
                for (int j = 0; j < fieldCount; j++) {
                    String fieldName = readString(in);
                    TagSchema.DataType type = TagSchema.DataType.valueOf(readString(in));
                    boolean nullable = in.readBoolean();
                    fields.add(new TagSchema.Field(fieldName, type, nullable));
                }
                graph.createTag(new TagSchema(tagName, fields));
            }
            int edgeTypeCount = in.readInt();
            for (int i = 0; i < edgeTypeCount; i++) {
                String edgeTypeName = readString(in);
                int fieldCount = in.readInt();
                List<TagSchema.Field> fields = new ArrayList<>(fieldCount);
                for (int j = 0; j < fieldCount; j++) {
                    String fieldName = readString(in);
                    TagSchema.DataType type = TagSchema.DataType.valueOf(readString(in));
                    boolean nullable = in.readBoolean();
                    fields.add(new TagSchema.Field(fieldName, type, nullable));
                }
                graph.createEdgeType(new EdgeTypeSchema(edgeTypeName, fields));
            }
        }
        return graph;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) out.writeUTF(value);
    }

    private static String readString(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }

    private static void writeValue(DataOutputStream out, Object value) throws IOException {
        if (value == null) {
            out.writeByte(0);
        } else if (value instanceof String) {
            out.writeByte(1);
            out.writeUTF((String) value);
        } else if (value instanceof Boolean) {
            out.writeByte(2);
            out.writeBoolean((Boolean) value);
        } else if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            out.writeByte(3);
            out.writeInt(((Number) value).intValue());
        } else if (value instanceof Long) {
            out.writeByte(4);
            out.writeLong((Long) value);
        } else if (value instanceof Float || value instanceof Double) {
            out.writeByte(5);
            out.writeDouble(((Number) value).doubleValue());
        } else if (value instanceof Map<?, ?> map) {
            out.writeByte(6);
            out.writeInt(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.writeUTF(String.valueOf(entry.getKey()));
                writeValue(out, entry.getValue());
            }
        } else if (value instanceof Collection<?> collection) {
            out.writeByte(7);
            out.writeInt(collection.size());
            for (Object item : collection) writeValue(out, item);
        } else if (value instanceof byte[] bytes) {
            out.writeByte(8);
            out.writeInt(bytes.length);
            out.write(bytes);
        } else {
            out.writeByte(9);
            out.writeUTF(value.toString());
        }
    }

    private static Object readValue(DataInputStream in) throws IOException {
        return switch (in.readByte()) {
            case 0 -> null;
            case 1 -> in.readUTF();
            case 2 -> in.readBoolean();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readDouble();
            case 6 -> {
                int size = in.readInt();
                Map<String, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < size; i++) map.put(in.readUTF(), readValue(in));
                yield map;
            }
            case 7 -> {
                int size = in.readInt();
                List<Object> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) list.add(readValue(in));
                yield list;
            }
            case 8 -> {
                byte[] bytes = new byte[in.readInt()];
                in.readFully(bytes);
                yield bytes;
            }
            case 9 -> in.readUTF();
            default -> throw new IOException("Unknown graph property type");
        };
    }
    private GraphCommit createCommit(List<String> parents,
                                     String branch,
                                     String author,
                                     String message,
                                     InMemoryGraphStore graph) {
        InMemoryGraphStore snapshot = graph.copy();
        long commitSequence = ++sequence;
        long timestamp = System.currentTimeMillis();
        String id = createCommitId(parents, branch, author, message, commitSequence, snapshot);
        GraphCommit commit = new GraphCommit(id, parents, branch, author, message, timestamp,
                snapshot.getNodeCount(), snapshot.getEdgeCount());
        commits.put(id, commit);
        snapshots.put(id, snapshot);
        persistState();
        return commit;
    }

    private String createCommitId(List<String> parents,
                                  String branch,
                                  String author,
                                  String message,
                                  long commitSequence,
                                  InMemoryGraphStore graph) {
        String payload = String.join("\n", parents) + "\n" + String.join("\n",
                branch == null ? "" : branch,
                author == null ? "" : author,
                message == null ? "" : message,
                Long.toString(commitSequence),
                Long.toString(graph.getNodeCount()),
                Long.toString(graph.getEdgeCount()));
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
        }
        return new MergeState(result, conflicts);
    }

    private Node mergeNode(long id,
                           Node base,
                           Node ours,
                           Node theirs,
                           List<String> conflicts) {
        if (sameNode(ours, theirs)) return ours;
        if (sameNode(ours, base)) return theirs;
        if (sameNode(theirs, base)) return ours;
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
        if (sameEdge(ours, theirs)) return ours;
        if (sameEdge(ours, base)) return theirs;
        if (sameEdge(theirs, base)) return ours;
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

    private static boolean sameNode(Node left, Node right) {
        if (left == right) return true;
        return left != null && right != null
                && left.getId() == right.getId()
                && Objects.equals(left.getLabels(), right.getLabels())
                && Objects.equals(left.getProperties(), right.getProperties());
    }

    private static boolean sameEdge(Edge left, Edge right) {
        if (left == right) return true;
        return left != null && right != null
                && left.getId() == right.getId()
                && Objects.equals(left.getType(), right.getType())
                && left.getStartNodeId() == right.getStartNodeId()
                && left.getEndNodeId() == right.getEndNodeId()
                && Objects.equals(left.getProperties(), right.getProperties());
    }

    private static Map<Long, Node> indexNodes(Collection<Node> nodes) {
        Map<Long, Node> result = new LinkedHashMap<>();
        for (Node node : nodes) result.put(node.getId(), node);
        return result;
    }

    private static Map<Long, Edge> indexEdges(Collection<Edge> edges) {
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

    private String requireBranch(String branch) {
        validateBranchName(branch);
        if (!branches.containsKey(branch)) {
            throw new IllegalArgumentException("Unknown branch: " + branch);
        }
        return branch;
    }

    private GraphCommit requireCommit(String id, String description) {
        GraphCommit commit = commits.get(id);
        if (commit == null) {
            throw new IllegalArgumentException("Unknown " + description);
        }
        return commit;
    }

    private static void validateBranchName(String branch) {
        if (branch == null || branch.isBlank() || !branch.matches("[A-Za-z0-9._/-]+")) {
            throw new IllegalArgumentException("Invalid branch name: " + branch);
        }
    }

    private static final class MergeState {
        private final InMemoryGraphStore store;
        private final List<String> conflicts;

        private MergeState(InMemoryGraphStore store, List<String> conflicts) {
            this.store = store;
            this.conflicts = conflicts;
        }
    }
}
