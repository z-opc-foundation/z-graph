package com.zifang.z.graph.core.storage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 版本链存储：{@code versions.idx}（64B 定长 header）+ {@code versions.bin}
 * （变长 payload 追加区）+ 三族实体链头槽表（节点 / 边 / schema）。
 *
 * <p>每条版本 = 一条指令记录（upsert/delete），payload 是该实体的完整编码；
 * {@code prevVersionId} 把同一实体的版本串成链，链头槽表存最新 versionId。
 * 读路径沿链回溯，取第一条对给定 {@link Visibility} 可见的版本 —— 这就是
 * undo log 式的引擎原生 MVCC 解析，任何 ref（含 main head）走同一条路。</p>
 */
final class VersionStore {

    static final int KIND_UPSERT_NODE = 0;
    static final int KIND_UPSERT_EDGE = 1;
    static final int KIND_DELETE_NODE = 2;
    static final int KIND_DELETE_EDGE = 3;
    static final int KIND_UPSERT_TAG = 4;
    static final int KIND_DELETE_TAG = 5;
    static final int KIND_UPSERT_EDGE_TYPE = 6;
    static final int KIND_DELETE_EDGE_TYPE = 7;
    static final int KIND_UPSERT_INDEX = 8;
    static final int KIND_DELETE_INDEX = 9;

    static final int RECORD_BYTES = 64;

    static final class VersionRecord {
        final long versionId;
        final int kind;
        final long entityId;
        final long commitSeq;
        final long prevVersionId;
        final long payloadOffset;
        final int payloadLen;

        VersionRecord(long versionId, int kind, long entityId, long commitSeq,
                      long prevVersionId, long payloadOffset, int payloadLen) {
            this.versionId = versionId;
            this.kind = kind;
            this.entityId = entityId;
            this.commitSeq = commitSeq;
            this.prevVersionId = prevVersionId;
            this.payloadOffset = payloadOffset;
            this.payloadLen = payloadLen;
        }

        boolean isDelete() {
            return kind == KIND_DELETE_NODE || kind == KIND_DELETE_EDGE
                    || kind == KIND_DELETE_TAG || kind == KIND_DELETE_EDGE_TYPE
                    || kind == KIND_DELETE_INDEX;
        }
    }

    private final FixedRecordFile indexFile;
    private final AppendSegment payloadFile;
    private final FixedRecordFile nodeHeads;
    private final FixedRecordFile edgeHeads;
    private final FixedRecordFile labelSchemaHeads;
    private final FixedRecordFile etypeSchemaHeads;
    private final FixedRecordFile indexSchemaHeads;
    private final StoreHeader header;

    VersionStore(Path dir, StoreHeader header) {
        this.header = header;
        this.indexFile = new FixedRecordFile(dir.resolve("versions.idx"), RECORD_BYTES);
        this.payloadFile = new AppendSegment(dir.resolve("versions.bin"));
        this.nodeHeads = new FixedRecordFile(dir.resolve("node-heads.tbl"), 8);
        this.edgeHeads = new FixedRecordFile(dir.resolve("edge-heads.tbl"), 8);
        this.labelSchemaHeads = new FixedRecordFile(dir.resolve("label-schema-heads.tbl"), 8);
        this.etypeSchemaHeads = new FixedRecordFile(dir.resolve("etype-schema-heads.tbl"), 8);
        this.indexSchemaHeads = new FixedRecordFile(dir.resolve("index-schema-heads.tbl"), 8);
    }

    /** 打开全部文件；返回已存在的版本条数。 */
    long open() throws IOException {
        long versions = indexFile.open();
        payloadFile.open();
        nodeHeads.open();
        edgeHeads.open();
        labelSchemaHeads.open();
        etypeSchemaHeads.open();
        indexSchemaHeads.open();
        return versions;
    }

    // ==================== 追加 ====================

    /*
     * 64B 记录布局（无重叠）：
     *   0  kind u8        1  flags u8     2-3  pad u16
     *   4-11   payloadOffset long         12-19  versionId long
     *   20-27  entityId long              28-35  commitSeq long
     *   36-51  commitHash byte[16]        52-59  prevVersionId long
     *   60-63  payloadLen u32
     */

    /** 追加一条版本并更新对应链头；payload 可为空（删除类指令）。返回 versionId。 */
    synchronized long append(int kind, long entityId, long commitSeq, byte[] commitHash16,
                             byte[] payload) throws IOException {
        long versionId = header.allocateVersionId();
        long prev = headOf(kind, entityId);
        long payloadOffset = payload == null || payload.length == 0 ? -1L : payloadFile.append(payload);
        int payloadLen = payload == null ? 0 : payload.length;

        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(RECORD_BYTES);
        buf.put(0, (byte) kind);
        buf.put(1, (byte) 0);
        buf.putLong(4, payloadOffset);
        buf.putLong(12, versionId);
        buf.putLong(20, entityId);
        buf.putLong(28, commitSeq);
        if (commitHash16 != null) {
            buf.position(36);
            buf.put(commitHash16, 0, Math.min(16, commitHash16.length));
        }
        buf.putLong(52, prev);
        buf.putInt(60, payloadLen);
        byte[] record = new byte[RECORD_BYTES];
        buf.position(0);
        buf.get(record);
        indexFile.write(versionId, record);
        setHead(kind, entityId, versionId);
        return versionId;
    }

    private FixedRecordFile headFileOf(int kind) {
        switch (kind) {
            case KIND_UPSERT_NODE:
            case KIND_DELETE_NODE:
                return nodeHeads;
            case KIND_UPSERT_EDGE:
            case KIND_DELETE_EDGE:
                return edgeHeads;
            case KIND_UPSERT_TAG:
            case KIND_DELETE_TAG:
                return labelSchemaHeads;
            case KIND_UPSERT_EDGE_TYPE:
            case KIND_DELETE_EDGE_TYPE:
                return etypeSchemaHeads;
            default:
                return indexSchemaHeads;
        }
    }

    private long headOf(int kind, long entityId) throws IOException {
        FixedRecordFile heads = headFileOf(kind);
        if (entityId >= heads.recordCount()) return -1L;
        byte[] slot = new byte[8];
        heads.read(entityId, slot);
        long biased = java.nio.ByteBuffer.wrap(slot).getLong();
        // 槽位存 versionId+1：新映射页零填充，0 表示「该实体从无版本」。
        return biased == 0 ? -1L : biased - 1;
    }

    private void setHead(int kind, long entityId, long versionId) throws IOException {
        byte[] slot = new byte[8];
        java.nio.ByteBuffer.wrap(slot).putLong(versionId + 1);
        headFileOf(kind).write(entityId, slot);
    }

    // ==================== 解析 ====================

    VersionRecord read(long versionId) throws IOException {
        byte[] record = new byte[RECORD_BYTES];
        indexFile.read(versionId, record);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(record);
        int kind = buf.get(0) & 0xFF;
        long payloadOffset = buf.getLong(4);
        long entityId = buf.getLong(20);
        long commitSeq = buf.getLong(28);
        long prev = buf.getLong(52);
        int payloadLen = buf.getInt(60);
        return new VersionRecord(versionId, kind, entityId, commitSeq, prev, payloadOffset, payloadLen);
    }

    byte[] payload(VersionRecord record) throws IOException {
        if (record.payloadOffset < 0 || record.payloadLen == 0) {
            return new byte[0];
        }
        return payloadFile.read(record.payloadOffset, record.payloadLen);
    }

    /** 实体在可见范围内的最新版本；全链不可见返回 null。 */
    VersionRecord latestVisible(int kind, long entityId, Visibility visibility) throws IOException {
        long cursor = headOf(kind, entityId);
        while (cursor >= 0) {
            VersionRecord record = read(cursor);
            if (visibility.isVisible(record.commitSeq)) {
                return record;
            }
            cursor = record.prevVersionId;
        }
        return null;
    }

    /** 实体的可见版本链，从新到旧。 */
    java.util.List<VersionRecord> historyVisible(int kind, long entityId, Visibility visibility) throws IOException {
        java.util.List<VersionRecord> result = new java.util.ArrayList<>();
        long cursor = headOf(kind, entityId);
        while (cursor >= 0) {
            VersionRecord record = read(cursor);
            if (visibility.isVisible(record.commitSeq)) {
                result.add(record);
            }
            cursor = record.prevVersionId;
        }
        return result;
    }

    long nodeCount() {
        return nodeHeads.recordCount();
    }

    long versionRecordCount() {
        // mmap 窗口会把文件扩展到 2 的幂，文件大小不是权威计数；header 的
        // nextVersionId 才是（versionId 从 0 连续递增，等于已写记录数）。
        return header.nextVersionId();
    }

    long edgeCount() {
        return edgeHeads.recordCount();
    }

    void force() throws IOException {
        indexFile.force();
        payloadFile.force();
        nodeHeads.force();
        edgeHeads.force();
        labelSchemaHeads.force();
        etypeSchemaHeads.force();
        indexSchemaHeads.force();
    }

    void close() throws IOException {
        indexFile.close();
        payloadFile.close();
        nodeHeads.close();
        edgeHeads.close();
        labelSchemaHeads.close();
        etypeSchemaHeads.close();
        indexSchemaHeads.close();
    }
}
