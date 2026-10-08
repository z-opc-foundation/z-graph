package com.zifang.z.graph.core.storage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 版本化倒排 postings：40B 定长条目按 owner 串成磁盘链（新条目做链头）。
 *
 * <pre>
 * entry = [0] prevEntryOffset long   同 owner 的次新条目，-1 链尾
 *         [8] targetId        long   edgeId 或 nodeId
 *         [16] commitSeq      long
 *         [24] kind           u8     0=ADD, 1=TOMBSTONE
 *         [25..39] pad
 * </pre>
 *
 * <p>owner 的链头（最新条目 offset）由调用方的槽表维护。解析 = 从链头往下走，
 * 对每个 targetId 取「可见范围内 commitSeq 最大」的那条，kind=TOMBSTONE 表示该
 * target 在 ref 上不存在 —— tombstone 即 undo log 记录。</p>
 */
final class PostingStore {

    static final int KIND_ADD = 0;
    static final int KIND_TOMBSTONE = 1;
    static final int ENTRY_BYTES = 40;

    interface Visitor {
        /** 返回 false 提前终止遍历。 */
        boolean entry(long targetId, long commitSeq, int kind, long entryOffset) throws IOException;
    }

    private final Path path;
    private final AppendSegment segment;
    private final FixedRecordFile heads;

    PostingStore(Path entryFile, Path headFile) {
        this.path = entryFile;
        this.segment = new AppendSegment(entryFile);
        this.heads = new FixedRecordFile(headFile, 8);
    }

    /** 打开条目文件与链头槽表；返回已有条目数（40B 一条）。 */
    long open() throws IOException {
        long entries = segment.open();
        heads.open();
        return entries / ENTRY_BYTES;
    }

    private long headOffset(long ownerSlot) throws IOException {
        if (ownerSlot >= heads.recordCount()) return -1L;
        byte[] slot = new byte[8];
        heads.read(ownerSlot, slot);
        // 槽位存 offset+1：新映射页零填充，0 表示「该 owner 从无条目」。
        long biased = java.nio.ByteBuffer.wrap(slot).getLong();
        return biased == 0 ? -1L : biased - 1;
    }

    /** 追加一条 posting 并把 owner 链头指向它。 */
    synchronized void add(long ownerSlot, long targetId, long commitSeq, int kind) throws IOException {
        long prev = headOffset(ownerSlot);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(ENTRY_BYTES);
        buf.putLong(0, prev);
        buf.putLong(8, targetId);
        buf.putLong(16, commitSeq);
        buf.put(24, (byte) kind);
        byte[] entry = new byte[ENTRY_BYTES];
        buf.position(0);
        buf.get(entry);
        long offset = segment.append(entry);

        byte[] slot = new byte[8];
        java.nio.ByteBuffer.wrap(slot).putLong(offset + 1);
        heads.write(ownerSlot, slot);
    }

    /** 从 owner 链头遍历（新→旧）；visitor 返回 false 提前终止。 */
    void walk(long ownerSlot, Visitor visitor) throws IOException {
        long cursor = headOffset(ownerSlot);
        while (cursor >= 0) {
            byte[] entry = segment.read(cursor, ENTRY_BYTES);
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(entry);
            long prev = buf.getLong(0);
            long targetId = buf.getLong(8);
            long commitSeq = buf.getLong(16);
            int kind = buf.get(24) & 0xFF;
            if (!visitor.entry(targetId, commitSeq, kind, cursor)) {
                return;
            }
            cursor = prev;
        }
    }

    /**
     * 解析 owner 在可见范围内的存活 target 集合：每个 targetId 取可见的最新条目，
     * kind=ADD 才算存活。返回按「最新条目先出现」排序的 targetId 列表。
     */
    java.util.List<Long> resolveLive(long ownerSlot, Visibility visibility) throws IOException {
        java.util.LinkedHashMap<Long, int[]> latest = new java.util.LinkedHashMap<>();
        walk(ownerSlot, (targetId, commitSeq, kind, offset) -> {
            if (!visibility.isVisible(commitSeq)) {
                return true;
            }
            if (!latest.containsKey(targetId)) {
                latest.put(targetId, new int[]{kind});
            }
            return true;
        });
        java.util.List<Long> result = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Long, int[]> entry : latest.entrySet()) {
            if (entry.getValue()[0] == KIND_ADD) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    long headSlotCount() {
        return heads.recordCount();
    }

    long entryBytes() {
        return segment.size();
    }

    void force() throws IOException {
        segment.force();
        heads.force();
    }

    void close() throws IOException {
        segment.close();
        heads.close();
    }
}
