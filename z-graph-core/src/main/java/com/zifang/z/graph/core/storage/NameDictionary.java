package com.zifang.z.graph.core.storage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 名称字典：字符串 ↔ u32 id，追加持久化（{@code labels.dict} / {@code types.dict} / {@code names.dict}）。
 *
 * <p>全量驻留内存（name→id 表 + id→name 数组）；新名追加即落通道，刷盘随 commit 点
 * 统一 force。id 从 0 递增、永不复用，槽表文件直接用 dict id 做下标。</p>
 */
public final class NameDictionary {

    private final Path path;
    private final java.util.HashMap<String, Integer> ids = new java.util.HashMap<>();
    private final java.util.ArrayList<String> names = new java.util.ArrayList<>();
    private java.nio.channels.FileChannel channel;

    NameDictionary(Path path) {
        this.path = path;
    }

    /** 打开已有字典或创建空字典；返回条目数。 */
    synchronized int open() throws IOException {
        channel = java.nio.channels.FileChannel.open(path,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE);
        long size = channel.size();
        if (size > 0) {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate((int) size);
            channel.position(0);
            while (buf.hasRemaining()) {
                if (channel.read(buf) < 0) throw new java.io.EOFException("Dictionary truncated: " + path);
            }
            buf.flip();
            // 条目自定界：[id u32][len u16][utf8 bytes]，读到 EOF 为止，无 count 前缀。
            while (buf.hasRemaining()) {
                int id = buf.getInt();
                int len = buf.getShort() & 0xFFFF;
                byte[] bytes = new byte[len];
                buf.get(bytes);
                String name = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                ids.put(name, id);
                if (id >= names.size()) {
                    names.ensureCapacity(id + 1);
                    while (names.size() <= id) names.add(null);
                }
                names.set(id, name);
            }
        }
        return ids.size();
    }

    synchronized int size() {
        return ids.size();
    }

    public synchronized int idOf(String name) {
        Integer id = ids.get(name);
        return id == null ? -1 : id;
    }

    /** 已注册返回既有 id；否则分配新 id 并追加落盘。 */
    synchronized int intern(String name) throws IOException {
        Integer existing = ids.get(name);
        if (existing != null) return existing;
        int id = names.size();
        byte[] bytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > 0xFFFF) throw new IllegalArgumentException("Name too long: " + name);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(4 + 2 + bytes.length);
        buf.putInt(id);
        buf.putShort((short) bytes.length);
        buf.put(bytes);
        buf.flip();
        channel.position(channel.size());
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
        ids.put(name, id);
        names.add(name);
        return id;
    }

    synchronized String nameOf(int id) {
        if (id < 0 || id >= names.size()) return null;
        return names.get(id);
    }

    /** 按字典序遍历全部名称（listTags/listEdgeTypes 用）。 */
    synchronized java.util.List<String> allNames() {
        java.util.List<String> result = new java.util.ArrayList<>();
        for (String name : names) {
            if (name != null) result.add(name);
        }
        java.util.Collections.sort(result);
        return result;
    }

    synchronized void force() throws IOException {
        if (channel != null) {
            channel.force(false);
        }
    }

    synchronized void close() throws IOException {
        force();
        if (channel != null) {
            channel.close();
        }
        channel = null;
    }
}
