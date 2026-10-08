package com.zifang.z.graph.core.storage;

import com.zifang.z.graph.api.TagSchema;
import com.zifang.z.graph.core.GraphDelta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 存储引擎门面：持有 {@code store/} 下全部文件，负责「commit = 指令集」的落盘
 * （{@link #applyDelta}）与解析读所需的全部索引。
 *
 * <p>唯一图数据基底 = versions.idx/versions.bin 的版本链 + 四族版本化 postings
 * （labels / adj-out / adj-in / edge-types）。没有「当前态镜像」文件——任何
 * ref（含 main head）都由 {@link RefViewGraphStore} 按 Visibility 即时解析。</p>
 *
 * <p>线程模型：写路径单写者（上层 GraphVersionStore synchronized），读路径 mmap
 * 并发安全；force 由上层在 commit 点调用。</p>
 */
public final class StorageEngine {

    static final String INDEX_NAME_SEPARATOR = "\u0000";

    private final Path dir;
    private StoreHeader header;
    private NameDictionary labelDict;
    private NameDictionary typeDict;
    private NameDictionary indexNameDict;
    private VersionStore versions;
    private PostingStore labelPosts;
    private PostingStore adjOutPosts;
    private PostingStore adjInPosts;
    private PostingStore etypePosts;

    private StorageEngine(Path dir) {
        this.dir = dir;
    }

    public static StorageEngine create(Path dir) throws IOException {
        Files.createDirectories(dir);
        StorageEngine engine = new StorageEngine(dir);
        engine.header = StoreHeader.fresh();
        engine.openFiles();
        engine.header.write(dir.resolve("header.bin"));
        return engine;
    }

    public static StorageEngine open(Path dir) throws IOException {
        StorageEngine engine = new StorageEngine(dir);
        engine.header = StoreHeader.read(dir.resolve("header.bin"));
        engine.openFiles();
        return engine;
    }

    private void openFiles() throws IOException {
        labelDict = new NameDictionary(dir.resolve("labels.dict"));
        typeDict = new NameDictionary(dir.resolve("types.dict"));
        indexNameDict = new NameDictionary(dir.resolve("names.dict"));
        versions = new VersionStore(dir, header);
        labelPosts = new PostingStore(dir.resolve("labels.post"), dir.resolve("label-post-heads.tbl"));
        adjOutPosts = new PostingStore(dir.resolve("adj-out.post"), dir.resolve("adjout-post-heads.tbl"));
        adjInPosts = new PostingStore(dir.resolve("adj-in.post"), dir.resolve("adjin-post-heads.tbl"));
        etypePosts = new PostingStore(dir.resolve("edge-types.post"), dir.resolve("etype-post-heads.tbl"));

        labelDict.open();
        typeDict.open();
        indexNameDict.open();
        versions.open();
        labelPosts.open();
        adjOutPosts.open();
        adjInPosts.open();
        etypePosts.open();
    }

    // ==================== id 分配 ====================

    public synchronized long allocateNodeId() {
        return header.allocateNodeId();
    }

    public synchronized long allocateEdgeId() {
        return header.allocateEdgeId();
    }

    public synchronized void reserveNodeId(long id) {
        while (header.nextNodeId() <= id) {
            header.allocateNodeId();
        }
    }

    public synchronized void reserveEdgeId(long id) {
        while (header.nextEdgeId() <= id) {
            header.allocateEdgeId();
        }
    }

    public synchronized long nextCommitSeq() {
        return header.nextCommitSeq();
    }

    public synchronized long allocateCommitSeq() {
        return header.allocateCommitSeq();
    }

    // ==================== 写：指令集落盘 ====================

    /**
     * 把一个 commit 的指令集（GraphDelta）落成版本链 + postings。
     * 前提：delta 里的实体 id 已经过分配器（新增实体）或已存在（更新/删除）。
     */
    public synchronized void applyDelta(long commitSeq, byte[] commitHash16, GraphDelta delta) throws IOException {
        for (long nodeId : delta.nodeDeletes()) {
            deleteNodePostings(nodeId, commitSeq);
            versions.append(VersionStore.KIND_DELETE_NODE, nodeId, commitSeq, commitHash16, null);
        }
        for (com.zifang.z.graph.api.Node node : delta.nodeUpserts()) {
            upsertNode(node, commitSeq, commitHash16);
        }
        for (long edgeId : delta.edgeDeletes()) {
            deleteEdgePostings(edgeId, commitSeq);
            versions.append(VersionStore.KIND_DELETE_EDGE, edgeId, commitSeq, commitHash16, null);
        }
        for (com.zifang.z.graph.api.Edge edge : delta.edgeUpserts()) {
            upsertEdge(edge, commitSeq, commitHash16);
        }
        for (TagSchema schema : delta.tagUpserts()) {
            int id = labelDict.intern(schema.getName());
            versions.append(VersionStore.KIND_UPSERT_TAG, id, commitSeq, commitHash16,
                    PayloadCodec.encodeSchemaFields(schema.getFields()));
        }
        for (String name : delta.tagDeletes()) {
            int id = labelDict.idOf(name);
            if (id >= 0) {
                versions.append(VersionStore.KIND_DELETE_TAG, id, commitSeq, commitHash16, null);
            }
        }
        for (com.zifang.z.graph.api.EdgeTypeSchema schema : delta.edgeTypeUpserts()) {
            int id = typeDict.intern(schema.getName());
            versions.append(VersionStore.KIND_UPSERT_EDGE_TYPE, id, commitSeq, commitHash16,
                    PayloadCodec.encodeSchemaFields(schema.getFields()));
        }
        for (String name : delta.edgeTypeDeletes()) {
            int id = typeDict.idOf(name);
            if (id >= 0) {
                versions.append(VersionStore.KIND_DELETE_EDGE_TYPE, id, commitSeq, commitHash16, null);
            }
        }
        for (GraphDelta.IndexKey key : delta.indexUpserts()) {
            int id = indexNameDict.intern(indexName(key));
            versions.append(VersionStore.KIND_UPSERT_INDEX, id, commitSeq, commitHash16, null);
        }
        for (GraphDelta.IndexKey key : delta.indexDeletes()) {
            int id = indexNameDict.idOf(indexName(key));
            if (id >= 0) {
                versions.append(VersionStore.KIND_DELETE_INDEX, id, commitSeq, commitHash16, null);
            }
        }
    }

    private static String indexName(GraphDelta.IndexKey key) {
        return key.label() + INDEX_NAME_SEPARATOR + key.propertyKey();
    }

    static String indexLabel(String compositeName) {
        int cut = compositeName.indexOf(INDEX_NAME_SEPARATOR);
        return cut < 0 ? compositeName : compositeName.substring(0, cut);
    }

    static String indexPropertyKey(String compositeName) {
        int cut = compositeName.indexOf(INDEX_NAME_SEPARATOR);
        return cut < 0 ? "" : compositeName.substring(cut + 1);
    }

    private void upsertNode(com.zifang.z.graph.api.Node node, long commitSeq, byte[] commitHash16) throws IOException {
        reserveNodeId(node.getId());
        List<String> newLabels = new ArrayList<String>();
        for (String label : node.getLabels()) {
            newLabels.add(label);
        }
        VersionStore.VersionRecord previous = versions.latestVisible(
                VersionStore.KIND_UPSERT_NODE, node.getId(), ALWAYS_VISIBLE);
        if (previous != null && !previous.isDelete()) {
            List<String> oldLabels = PayloadCodec.decodeNode(versions.payload(previous), labelDict).labels;
            for (String oldLabel : oldLabels) {
                if (!newLabels.contains(oldLabel)) {
                    Integer dictId = labelDict.idOf(oldLabel);
                    if (dictId != null) {
                        labelPosts.add(dictId, node.getId(), commitSeq, PostingStore.KIND_TOMBSTONE);
                    }
                }
            }
        }
        byte[] payload = PayloadCodec.encodeNode(node.getLabels(), node.getProperties(), labelDict);
        versions.append(VersionStore.KIND_UPSERT_NODE, node.getId(), commitSeq, commitHash16, payload);
        for (String label : newLabels) {
            labelPosts.add(labelDict.intern(label), node.getId(), commitSeq, PostingStore.KIND_ADD);
        }
    }

    private void deleteNodePostings(long nodeId, long commitSeq) throws IOException {
        VersionStore.VersionRecord head = versions.latestVisible(
                VersionStore.KIND_UPSERT_NODE, nodeId, ALWAYS_VISIBLE);
        if (head == null || head.isDelete()) {
            return;
        }
        List<String> labels = PayloadCodec.decodeNode(versions.payload(head), labelDict).labels;
        for (String label : labels) {
            Integer dictId = labelDict.idOf(label);
            if (dictId != null) {
                labelPosts.add(dictId, nodeId, commitSeq, PostingStore.KIND_TOMBSTONE);
            }
        }
    }

    private void upsertEdge(com.zifang.z.graph.api.Edge edge, long commitSeq, byte[] commitHash16) throws IOException {
        reserveEdgeId(edge.getId());
        VersionStore.VersionRecord previous = versions.latestVisible(
                VersionStore.KIND_UPSERT_EDGE, edge.getId(), ALWAYS_VISIBLE);
        PayloadCodec.EdgePayload old = previous != null && !previous.isDelete()
                ? PayloadCodec.decodeEdge(versions.payload(previous), typeDict)
                : null;
        if (old != null) {
            if (old.startNodeId != edge.getStartNodeId()) {
                adjOutPosts.add(old.startNodeId, edge.getId(), commitSeq, PostingStore.KIND_TOMBSTONE);
            }
            if (old.endNodeId != edge.getEndNodeId()) {
                adjInPosts.add(old.endNodeId, edge.getId(), commitSeq, PostingStore.KIND_TOMBSTONE);
            }
            if (!old.type.equals(edge.getType())) {
                Integer oldType = typeDict.idOf(old.type);
                if (oldType != null) {
                    etypePosts.add(oldType, edge.getId(), commitSeq, PostingStore.KIND_TOMBSTONE);
                }
            }
        }
        byte[] payload = PayloadCodec.encodeEdge(edge.getType(), edge.getStartNodeId(), edge.getEndNodeId(),
                edge.getProperties(), typeDict);
        versions.append(VersionStore.KIND_UPSERT_EDGE, edge.getId(), commitSeq, commitHash16, payload);
        adjOutPosts.add(edge.getStartNodeId(), edge.getId(), commitSeq, PostingStore.KIND_ADD);
        adjInPosts.add(edge.getEndNodeId(), edge.getId(), commitSeq, PostingStore.KIND_ADD);
        etypePosts.add(typeDict.intern(edge.getType()), edge.getId(), commitSeq, PostingStore.KIND_ADD);
    }

    private void deleteEdgePostings(long edgeId, long commitSeq) throws IOException {
        VersionStore.VersionRecord head = versions.latestVisible(
                VersionStore.KIND_UPSERT_EDGE, edgeId, ALWAYS_VISIBLE);
        if (head == null || head.isDelete()) {
            return;
        }
        PayloadCodec.EdgePayload old = PayloadCodec.decodeEdge(versions.payload(head), typeDict);
        adjOutPosts.add(old.startNodeId, edgeId, commitSeq, PostingStore.KIND_TOMBSTONE);
        adjInPosts.add(old.endNodeId, edgeId, commitSeq, PostingStore.KIND_TOMBSTONE);
        Integer typeId = typeDict.idOf(old.type);
        if (typeId != null) {
            etypePosts.add(typeId, edgeId, commitSeq, PostingStore.KIND_TOMBSTONE);
        }
    }

    // ==================== 读：交给 RefViewGraphStore ====================

    public VersionStore versionStore() {
        return versions;
    }

    public PostingStore labelPostings() {
        return labelPosts;
    }

    public PostingStore adjOutPostings() {
        return adjOutPosts;
    }

    public PostingStore adjInPostings() {
        return adjInPosts;
    }

    public PostingStore etypePostings() {
        return etypePosts;
    }

    public NameDictionary labelDictionary() {
        return labelDict;
    }

    public NameDictionary typeDictionary() {
        return typeDict;
    }

    public NameDictionary indexNameDictionary() {
        return indexNameDict;
    }

    public long nextNodeId() {
        return header.nextNodeId();
    }

    public long nextEdgeId() {
        return header.nextEdgeId();
    }

    // ==================== 持久化收尾 ====================

    /** commit 点：header 原子重写 + 全部数据文件 force。之后上层才移动 ref。 */
    public synchronized void persistAndForce() throws IOException {
        header.write(dir.resolve("header.bin"));
        labelDict.force();
        typeDict.force();
        indexNameDict.force();
        versions.force();
        labelPosts.force();
        adjOutPosts.force();
        adjInPosts.force();
        etypePosts.force();
    }

    /** versions.bin 的追加区字节数。 */
    public synchronized long payloadBytes() {
        return versions.payloadBytes();
    }

    public synchronized java.util.Map<String, Object> stats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("versionRecordCount", versions.versionRecordCount());
        stats.put("nextNodeId", header.nextNodeId());
        stats.put("nextEdgeId", header.nextEdgeId());
        stats.put("nextCommitSeq", header.nextCommitSeq());
        stats.put("labelDictSize", (long) labelDict.size());
        stats.put("typeDictSize", (long) typeDict.size());
        stats.put("indexNameDictSize", (long) indexNameDict.size());
        return stats;
    }

    public synchronized void close() throws IOException {
        persistAndForce();
        labelDict.close();
        typeDict.close();
        indexNameDict.close();
        versions.close();
        labelPosts.close();
        adjOutPosts.close();
        adjInPosts.close();
        etypePosts.close();
    }

    /** applyDelta 内部读「当前链头」用：与任何 ref 无关，只看最新已落盘状态。 */
    public static final Visibility ALWAYS_VISIBLE = new Visibility() {
        @Override
        public boolean isVisible(long commitSeq) {
            return true;
        }
    };
}
