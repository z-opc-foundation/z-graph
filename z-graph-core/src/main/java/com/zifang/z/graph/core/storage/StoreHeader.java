package com.zifang.z.graph.core.storage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 版本仓库头（header.bin，64B 定长）。每次 commit 后经临时文件 + 原子替换重写，
 * 保证崩溃时不出现撕裂头；crc32 校验字节 0..55。
 */
public final class StoreHeader {

    public static final int MAGIC = 0x5A47524F;
    public static final int LAYOUT_VERSION = 5;
    static final int BYTES = 64;

    private static final int OFF_MAGIC = 0;
    private static final int OFF_LAYOUT = 4;
    private static final int OFF_CREATION_TS = 8;
    private static final int OFF_NEXT_NODE = 16;
    private static final int OFF_NEXT_EDGE = 24;
    private static final int OFF_NEXT_VERSION = 32;
    private static final int OFF_NEXT_COMMIT_SEQ = 40;
    private static final int OFF_CRC = 56;

    private long creationTs;
    private long nextNodeId;
    private long nextEdgeId;
    private long nextVersionId;
    private long nextCommitSeq;

    public static StoreHeader fresh() {
        StoreHeader header = new StoreHeader();
        header.creationTs = System.currentTimeMillis();
        header.nextCommitSeq = 1;
        return header;
    }

    public long nextNodeId() { return nextNodeId; }

    public long allocateNodeId() { return nextNodeId++; }

    public long nextEdgeId() { return nextEdgeId; }

    public long allocateEdgeId() { return nextEdgeId++; }

    public long nextVersionId() { return nextVersionId; }

    public long allocateVersionId() { return nextVersionId++; }

    public long nextCommitSeq() { return nextCommitSeq; }

    public long allocateCommitSeq() { return nextCommitSeq++; }

    /** 反序列化；magic/layout/crc 任一不符抛 IOException。 */
    public static StoreHeader read(java.nio.file.Path file) throws IOException {
        byte[] bytes;
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            bytes = readFully(in, BYTES);
        }
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes, 0, OFF_CRC);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes);
        if (buf.getInt(OFF_MAGIC) != MAGIC) {
            throw new IOException("Bad store magic in " + file);
        }
        if (buf.getInt(OFF_LAYOUT) != LAYOUT_VERSION) {
            throw new IOException("Unsupported store layout version " + buf.getInt(OFF_LAYOUT) + " in " + file);
        }
        if ((int) crc.getValue() != buf.getInt(OFF_CRC)) {
            throw new IOException("Store header crc mismatch in " + file);
        }
        StoreHeader header = new StoreHeader();
        header.creationTs = buf.getLong(OFF_CREATION_TS);
        header.nextNodeId = buf.getLong(OFF_NEXT_NODE);
        header.nextEdgeId = buf.getLong(OFF_NEXT_EDGE);
        header.nextVersionId = buf.getLong(OFF_NEXT_VERSION);
        header.nextCommitSeq = buf.getLong(OFF_NEXT_COMMIT_SEQ);
        return header;
    }

    /** 临时文件 + 原子替换写入，崩溃安全。 */
    public void write(java.nio.file.Path file) throws IOException {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(BYTES);
        buf.putInt(OFF_MAGIC, MAGIC);
        buf.putInt(OFF_LAYOUT, LAYOUT_VERSION);
        buf.putLong(OFF_CREATION_TS, creationTs);
        buf.putLong(OFF_NEXT_NODE, nextNodeId);
        buf.putLong(OFF_NEXT_EDGE, nextEdgeId);
        buf.putLong(OFF_NEXT_VERSION, nextVersionId);
        buf.putLong(OFF_NEXT_COMMIT_SEQ, nextCommitSeq);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(buf.array(), 0, OFF_CRC);
        buf.putInt(OFF_CRC, (int) crc.getValue());

        java.nio.file.Path parent = file.toAbsolutePath().getParent();
        if (parent != null) java.nio.file.Files.createDirectories(parent);
        java.nio.file.Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(temp,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            out.write(buf.array());
            out.flush();
            java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(temp,
                    java.nio.file.StandardOpenOption.WRITE);
            try {
                ch.force(true);
            } finally {
                ch.close();
            }
        }
        try {
            java.nio.file.Files.move(temp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            java.nio.file.Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] readFully(java.io.InputStream in, int length) throws IOException {
        byte[] bytes = new byte[length];
        int off = 0;
        while (off < length) {
            int read = in.read(bytes, off, length - off);
            if (read < 0) throw new java.io.EOFException("Store header truncated");
            off += read;
        }
        return bytes;
    }
}
