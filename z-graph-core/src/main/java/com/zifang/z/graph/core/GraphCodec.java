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
        } else if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            out.writeByte(6);
            out.writeInt(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.writeUTF(String.valueOf(entry.getKey()));
                writeValue(out, entry.getValue());
            }
        } else if (value instanceof Collection<?>) {
            Collection<?> collection = (Collection<?>) value;
            out.writeByte(7);
            out.writeInt(collection.size());
            for (Object item : collection) writeValue(out, item);
        } else if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
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
        byte type = in.readByte();
        Object result;
        switch (type) {
            case 0:
                result = null;
                break;
            case 1:
                result = in.readUTF();
                break;
            case 2:
                result = in.readBoolean();
                break;
            case 3:
                result = in.readInt();
                break;
            case 4:
                result = in.readLong();
                break;
            case 5:
                result = in.readDouble();
                break;
            case 6: {
                int size = in.readInt();
                Map<String, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < size; i++) map.put(in.readUTF(), readValue(in));
                result = map;
                break;
            }
            case 7: {
                int size = in.readInt();
                Collection<Object> items = new ArrayList<>(size);
                for (int i = 0; i < size; i++) items.add(readValue(in));
                result = items;
                break;
            }
            case 8: {
                byte[] bytes = new byte[in.readInt()];
                in.readFully(bytes);
                result = bytes;
                break;
            }
            case 9:
                result = in.readUTF();
                break;
            default:
                throw new IOException("Unknown graph property type");
        }
        return result;
    }
}
