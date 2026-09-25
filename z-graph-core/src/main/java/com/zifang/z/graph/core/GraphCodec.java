package com.zifang.z.graph.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * z-graph 版本仓库的定长标记编码。
 *
 * <p>类型标签沿用历史仓库格式，V2/V3 的旧文件仍然可以读入；新增的 schema 段以固定
 * 顺序写在每个 commit 的增量尾部，读写两端必须同步修改。</p>
 */
final class GraphCodec {

    static final int STORAGE_MAGIC = 0x5A475246;
    /** V4 起仓库按 commit 存增量，不再内联整图快照。 */
    static final int STORAGE_VERSION = 4;
    /** 仍可读取的历史格式下界。 */
    static final int LEGACY_STORAGE_VERSION = 2;

    private GraphCodec() {
    }

    static void writeString(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) out.writeUTF(value);
    }

    static String readString(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }

    static void writeValue(DataOutputStream out, Object value) throws IOException {
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

    @SuppressWarnings("unchecked")
    static Map<String, Object> readMap(DataInputStream in) throws IOException {
        Object value = readValue(in);
        if (!(value instanceof Map)) {
            throw new IOException("Expected property map in graph payload");
        }
        return (Map<String, Object>) value;
    }

    static Object readValue(DataInputStream in) throws IOException {
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
                Collection<Object> items = new ArrayList<>(size);
                for (int i = 0; i < size; i++) items.add(readValue(in));
                yield items;
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
}
