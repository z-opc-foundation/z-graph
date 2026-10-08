package com.zifang.z.graph.core.storage;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 定长 record 的 mmap 文件：{@code offset = index × recordSize} 直达定位。
 *
 * <p>容量按需倍增 remap（新窗口容量 ≥ 所需槽位的 2 的幂），旧窗口在 Java 8 上
 * 依赖 GC 回收；文件只追加、从不 truncate，因此已发出的旧 buffer 读到的字节
 * 仍然有效。写入不立即刷盘，由引擎在 commit 点统一 {@link #force()}。</p>
 */
final class FixedRecordFile {

    private final Path path;
    private final int recordSize;
    private FileChannel channel;
    private MappedByteBuffer buffer;
    private long mappedRecords;
    private long committedRecords;

    FixedRecordFile(Path path, int recordSize) {
        if (recordSize <= 0) throw new IllegalArgumentException("recordSize must be positive");
        this.path = path;
        this.recordSize = recordSize;
    }

    /** 打开已有文件或创建空文件；返回逻辑 record 数。 */
    long open() throws IOException {
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        long bytes = channel.size();
        committedRecords = bytes / recordSize;
        mappedRecords = 0;
        if (committedRecords > 0) {
            mapTo(committedRecords);
        }
        return committedRecords;
    }

    long recordCount() {
        return committedRecords;
    }

    private void mapTo(long records) throws IOException {
        long wantedBytes = records * recordSize;
        long mappedBytes = Math.max(4096L, (long) Math.pow(2, Math.ceil(Math.log(Math.max(2, wantedBytes)) / Math.log(2))));
        buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, mappedBytes);
        mappedRecords = mappedBytes / recordSize;
    }

    private void ensure(long index) throws IOException {
        if (buffer == null || index >= mappedRecords) {
            mapTo(Math.max(index + 1, mappedRecords == 0 ? 1 : mappedRecords * 2));
        }
    }

    /** 读第 {@code index} 条 record 到 {@code target}（长度必须等于 recordSize）。 */
    void read(long index, byte[] target) throws IOException {
        if (index < 0 || index >= committedRecords) {
            throw new IOException("Record index out of bounds: " + index + " / " + committedRecords + " in " + path);
        }
        buffer.position((int) (index * recordSize));
        buffer.get(target);
    }

    /** 写第 {@code index} 条 record；越过已提交边界即追加扩展。 */
    void write(long index, byte[] source) throws IOException {
        if (source.length != recordSize) {
            throw new IllegalArgumentException("Record size mismatch: " + source.length + " != " + recordSize);
        }
        ensure(index);
        buffer.position((int) (index * recordSize));
        buffer.put(source);
        if (index >= committedRecords) {
            committedRecords = index + 1;
        }
    }

    void force() throws IOException {
        if (buffer != null) {
            buffer.force();
        }
    }

    void close() throws IOException {
        force();
        channel.close();
        buffer = null;
        channel = null;
    }
}
