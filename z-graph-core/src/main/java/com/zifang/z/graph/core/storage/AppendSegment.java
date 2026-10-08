package com.zifang.z.graph.core.storage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 追加式变长字节段：payload 区的唯一写入方式是尾部追加，读按 (offset, len) 定位。
 *
 * <p>{@code longAt/intAt} 支持不整段物化地偷读头部字段；{@link #read(long, int)}
 * 供 payload 解码整段取用。刷盘语义同 {@link FixedRecordFile}：写入进 mmap 页，
 * commit 点统一 force。</p>
 */
final class AppendSegment {

    private static final int CHUNK = 1 << 20;

    private final Path path;
    private java.nio.channels.FileChannel channel;
    private java.nio.MappedByteBuffer buffer;
    private long mappedBytes;
    private long appendedBytes;

    AppendSegment(Path path) {
        this.path = path;
    }

    /** 打开已有文件或创建空文件；返回已追加字节数。 */
    long open() throws IOException {
        channel = java.nio.channels.FileChannel.open(path,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE);
        appendedBytes = channel.size();
        mappedBytes = 0;
        if (appendedBytes > 0) {
            mapTo(appendedBytes);
        }
        return appendedBytes;
    }

    long size() {
        return appendedBytes;
    }

    private void mapTo(long bytes) throws IOException {
        long mapped = Math.max(CHUNK, (long) Math.pow(2, Math.ceil(Math.log(Math.max(2, bytes)) / Math.log(2))));
        buffer = channel.map(java.nio.channels.FileChannel.MapMode.READ_WRITE, 0, mapped);
        mappedBytes = mapped;
    }

    private void ensure(long endOffset) throws IOException {
        if (buffer == null || endOffset > mappedBytes) {
            mapTo(Math.max(endOffset, mappedBytes == 0 ? CHUNK : mappedBytes * 2));
        }
    }

    /** 追加一段字节，返回起始 offset。 */
    synchronized long append(byte[] bytes) throws IOException {
        long start = appendedBytes;
        ensure(start + bytes.length);
        buffer.position((int) start);
        buffer.put(bytes);
        appendedBytes = start + bytes.length;
        return start;
    }

    byte[] read(long offset, int length) throws IOException {
        if (offset < 0 || offset + length > appendedBytes) {
            throw new IOException("Payload range out of bounds: " + offset + "+" + length + " / " + appendedBytes);
        }
        byte[] bytes = new byte[length];
        buffer.position((int) offset);
        buffer.get(bytes);
        return bytes;
    }

    long longAt(long offset) throws IOException {
        if (offset < 0 || offset + 8 > appendedBytes) {
            throw new IOException("Long read out of bounds: " + offset + " / " + appendedBytes);
        }
        buffer.position((int) offset);
        return buffer.getLong();
    }

    void force() throws IOException {
        if (buffer != null) {
            buffer.force();
        }
    }

    void close() throws IOException {
        force();
        if (channel != null) {
            channel.close();
        }
        buffer = null;
        channel = null;
    }
}
