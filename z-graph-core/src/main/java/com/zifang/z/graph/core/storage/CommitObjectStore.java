package com.zifang.z.graph.core.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Git 式 commit object：规范化字节的整体 SHA-256 即 commit id，文件名即 id，
 * 落盘即不可变。id 哈希覆盖 parents / branch / author / message / timestamp /
 * nodeCount / edgeCount / commitSeq / delta 清单 —— 同对象必同 id，改动任何
 * 元数据（包括 message）都产生新 id，与 Git 语义一致。
 *
 * <p>文件布局：magic "ZGRC" | format 1 | parents | branch | author | message |
 * timestamp | nodeCount | edgeCount | commitSeq | legacyId（迁移兼容，新仓恒 null）
 * | delta。idHash 不参与自身哈希：先对「除 idHash 外的规范化字节」求 SHA-256。</p>
 */
public final class CommitObjectStore {

    private static final int MAGIC = 0x5A475243;
    private static final int FORMAT = 1;

    private final Path dir;

    public CommitObjectStore(Path dir) {
        this.dir = dir;
    }

    public static final class CommitMeta {
        public final String id;
        public final List<String> parents;
        public final String branch;
        public final String author;
        public final String message;
        public final long timestampEpochMillis;
        public final long nodeCount;
        public final long edgeCount;
        public final long commitSeq;
        public final String legacyId;
        public final com.zifang.z.graph.core.GraphDelta delta;

        CommitMeta(String id, List<String> parents, String branch, String author, String message,
                   long timestampEpochMillis, long nodeCount, long edgeCount, long commitSeq,
                   String legacyId, com.zifang.z.graph.core.GraphDelta delta) {
            this.id = id;
            this.parents = parents;
            this.branch = branch;
            this.author = author;
            this.message = message;
            this.timestampEpochMillis = timestampEpochMillis;
            this.nodeCount = nodeCount;
            this.edgeCount = edgeCount;
            this.commitSeq = commitSeq;
            this.legacyId = legacyId;
            this.delta = delta;
        }
    }

    /** 规范化字节（不含 id 自身）——对它求 SHA-256 即 commit id。 */
    public static byte[] canonicalBytes(List<String> parents, String branch, String author, String message,
                                        long timestampEpochMillis, long nodeCount, long edgeCount,
                                        long commitSeq, String legacyId,
                                        com.zifang.z.graph.core.GraphDelta delta) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);
            out.writeInt(parents.size());
            for (String parent : parents) {
                writeLenString(out, parent);
            }
            writeLenString(out, branch);
            writeLenString(out, author);
            writeLenString(out, message);
            out.writeLong(timestampEpochMillis);
            out.writeLong(nodeCount);
            out.writeLong(edgeCount);
            out.writeLong(commitSeq);
            writeLenString(out, legacyId);
            delta.writeTo(out);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot serialize commit object", e);
        }
    }

    /** commit id = 规范化字节的 SHA-256 截 40 hex。 */
    public static String hashOf(byte[] canonical) {
        return toHex(sha256(canonical));
    }

    /** 规范化字节的 SHA-256 全量摘要（版本记录里存前 16 字节）。 */
    public static byte[] digestOf(byte[] canonical) {
        return sha256(canonical);
    }

    /** 序列化 + 内容哈希 + 落盘（临时文件 + 原子替换）。返回 commit id。 */
    public synchronized String write(List<String> parents, String branch, String author, String message,
                                     long timestampEpochMillis, long nodeCount, long edgeCount,
                                     long commitSeq, String legacyId,
                                     com.zifang.z.graph.core.GraphDelta delta) throws IOException {
        byte[] canonical = canonicalBytes(parents, branch, author, message, timestampEpochMillis,
                nodeCount, edgeCount, commitSeq, legacyId, delta);
        return writeCanonical(canonical);
    }

    /** 落盘一份规范化字节（文件名 = 其内容哈希）。返回 commit id。 */
    public synchronized String writeCanonical(byte[] canonical) throws IOException {
        String id = hashOf(canonical);
        Files.createDirectories(dir);
        Path target = dir.resolve(id + ".bin");
        if (!Files.exists(target)) {
            Path temp = dir.resolve(id + ".tmp");
            Files.write(temp, canonical);
            force(temp);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return id;
    }

    public synchronized CommitMeta read(String id) throws IOException {
        Path file = dir.resolve(id + ".bin");
        byte[] canonical = Files.readAllBytes(file);
        String actualId = toHex(sha256(canonical));
        if (!actualId.equals(id)) {
            throw new IOException("Commit object hash mismatch: " + file + " (content hashes to " + actualId + ")");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(canonical));
        if (in.readInt() != MAGIC) {
            throw new IOException("Bad commit object magic: " + file);
        }
        if (in.readInt() != FORMAT) {
            throw new IOException("Unsupported commit object format: " + file);
        }
        int parentCount = in.readInt();
        List<String> parents = new ArrayList<>(parentCount);
        for (int i = 0; i < parentCount; i++) {
            parents.add(readLenString(in));
        }
        String branch = readLenString(in);
        String author = readLenString(in);
        String message = readLenString(in);
        long timestamp = in.readLong();
        long nodeCount = in.readLong();
        long edgeCount = in.readLong();
        long commitSeq = in.readLong();
        String legacyId = readLenString(in);
        com.zifang.z.graph.core.GraphDelta delta = com.zifang.z.graph.core.GraphDelta.readFrom(in);
        return new CommitMeta(id, parents, branch, author, message, timestamp, nodeCount, edgeCount,
                commitSeq, legacyId, delta);
    }

    public void delete(String id) throws IOException {
        Files.deleteIfExists(dir.resolve(id + ".bin"));
    }

    private static void force(Path file) throws IOException {
        java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ);
        try {
            ch.force(true);
        } finally {
            ch.close();
        }
    }

    private static void writeLenString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = (value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readLenString(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) {
            throw new IOException("Negative string length in commit object");
        }
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK does not provide SHA-256", e);
        }
    }

    private static String toHex(byte[] hash) {
        StringBuilder result = new StringBuilder(hash.length * 2);
        for (byte item : hash) {
            result.append(String.format("%02x", item));
        }
        return result.substring(0, 40);
    }
}
