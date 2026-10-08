package com.zifang.z.graph.core.storage;

import com.zifang.z.graph.api.EdgeTypeSchema;
import com.zifang.z.graph.api.TagSchema;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实体 payload 编解码：版本链上的 payload 是实体的完整编码。
 *
 * <p>属性走自洽的 strict 定长标记编码（类型 0–8，格式对齐 GraphCodec 语义但
 * 禁用类型 9 的 toString 有损兜底——编码不了的值当场失败，绝不静默丢型）。
 * label / edgeType 以字典 id 写入，payload 里不重复存字符串。</p>
 */
public final class PayloadCodec {

    private PayloadCodec() {
    }

    // ==================== 节点 ====================

    static byte[] encodeNode(Collection<String> labels, Map<String, Object> properties,
                             NameDictionary labelDict) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        Collection<String> safeLabels = labels == null ? new ArrayList<String>() : labels;
        out.writeInt(safeLabels.size());
        for (String label : safeLabels) {
            out.writeInt(labelDict.intern(label));
        }
        writeStrictMap(out, properties);
        return bytes.toByteArray();
    }

    public static NodePayload decodeNode(byte[] payload, NameDictionary labelDict) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        int labelCount = in.readInt();
        List<String> labels = new ArrayList<>(labelCount);
        for (int i = 0; i < labelCount; i++) {
            labels.add(labelDict.nameOf(in.readInt()));
        }
        return new NodePayload(labels, readStrictMap(in));
    }

    public static final class NodePayload {
        public final List<String> labels;
        public final Map<String, Object> properties;

        NodePayload(List<String> labels, Map<String, Object> properties) {
            this.labels = labels;
            this.properties = properties;
        }
    }

    // ==================== 边 ====================

    static byte[] encodeEdge(String type, long startNodeId, long endNodeId,
                             Map<String, Object> properties, NameDictionary typeDict) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(typeDict.intern(type));
        out.writeLong(startNodeId);
        out.writeLong(endNodeId);
        writeStrictMap(out, properties);
        return bytes.toByteArray();
    }

    public static EdgePayload decodeEdge(byte[] payload, NameDictionary typeDict) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        String type = typeDict.nameOf(in.readInt());
        long start = in.readLong();
        long end = in.readLong();
        return new EdgePayload(type, start, end, readStrictMap(in));
    }

    public static final class EdgePayload {
        public final String type;
        public final long startNodeId;
        public final long endNodeId;
        public final Map<String, Object> properties;

        EdgePayload(String type, long startNodeId, long endNodeId, Map<String, Object> properties) {
            this.type = type;
            this.startNodeId = startNodeId;
            this.endNodeId = endNodeId;
            this.properties = properties;
        }
    }

    // ==================== Tag / EdgeType schema ====================

    static byte[] encodeSchemaFields(List<TagSchema.Field> fields) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        writeFields(out, fields);
        return bytes.toByteArray();
    }

    static TagSchema decodeTagSchema(String name, byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        return new TagSchema(name, readFields(in));
    }

    static EdgeTypeSchema decodeEdgeTypeSchema(String name, byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        return new EdgeTypeSchema(name, readFields(in));
    }

    private static void writeFields(DataOutputStream out, List<TagSchema.Field> fields) throws IOException {
        out.writeInt(fields.size());
        for (TagSchema.Field field : fields) {
            out.writeUTF(field.getName());
            out.writeUTF(field.getType().name());
            out.writeBoolean(field.isNullable());
        }
    }

    private static List<TagSchema.Field> readFields(DataInputStream in) throws IOException {
        int count = in.readInt();
        List<TagSchema.Field> fields = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            fields.add(new TagSchema.Field(in.readUTF(),
                    TagSchema.DataType.valueOf(in.readUTF()), in.readBoolean()));
        }
        return fields;
    }

    // ==================== 属性（strict 定长标记） ====================

    static void writeStrictMap(DataOutputStream out, Map<String, Object> properties) throws IOException {
        Map<String, Object> safe = properties == null ? new LinkedHashMap<String, Object>() : properties;
        out.writeInt(safe.size());
        for (Map.Entry<String, Object> entry : safe.entrySet()) {
            out.writeUTF(entry.getKey());
            writeStrictValue(out, entry.getValue());
        }
    }

    static Map<String, Object> readStrictMap(DataInputStream in) throws IOException {
        int size = in.readInt();
        Map<String, Object> map = new LinkedHashMap<>(Math.max(4, size * 2));
        for (int i = 0; i < size; i++) {
            String key = in.readUTF();
            map.put(key, readStrictValue(in));
        }
        return map;
    }

    private static void writeStrictValue(DataOutputStream out, Object value) throws IOException {
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
            out.writeByte(6);
            ByteArrayOutputStream nested = new ByteArrayOutputStream();
            writeStrictMap(new DataOutputStream(nested), castMap(value));
            byte[] body = nested.toByteArray();
            out.writeInt(body.length);
            out.write(body);
        } else if (value instanceof Collection<?>) {
            out.writeByte(7);
            out.writeInt(((Collection<?>) value).size());
            for (Object item : (Collection<?>) value) {
                writeStrictValue(out, item);
            }
        } else if (value instanceof byte[]) {
            out.writeByte(8);
            out.writeInt(((byte[]) value).length);
            out.write((byte[]) value);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported property value type for v5 layout: " + value.getClass().getName()
                            + " (lossy toString fallback removed)");
        }
    }

    private static Object readStrictValue(DataInputStream in) throws IOException {
        byte type = in.readByte();
        switch (type) {
            case 0:
                return null;
            case 1:
                return in.readUTF();
            case 2:
                return in.readBoolean();
            case 3:
                return (int) in.readInt();
            case 4:
                return in.readLong();
            case 5:
                return in.readDouble();
            case 6: {
                byte[] body = new byte[in.readInt()];
                in.readFully(body);
                return readStrictMap(new DataInputStream(new ByteArrayInputStream(body)));
            }
            case 7: {
                int size = in.readInt();
                List<Object> items = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    items.add(readStrictValue(in));
                }
                return items;
            }
            case 8: {
                byte[] bytes = new byte[in.readInt()];
                in.readFully(bytes);
                return bytes;
            }
            default:
                throw new IOException("Unsupported property value tag in payload: " + type);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
