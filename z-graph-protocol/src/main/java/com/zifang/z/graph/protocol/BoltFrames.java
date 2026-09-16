package com.zifang.z.graph.protocol;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bolt 4.4 帧编解码工具。
 *
 * 帧格式:
 * | 2-byte length | 2-byte marker | payload |
 * - length:payload 字节数(不含头 4 字节)
 * - marker:0x00 0x00 表示中间 chunk,0x00 0x0E 表示最后一个 chunk
 */
public final class BoltFrames {

    private BoltFrames() {}

    /**
     * 写一个 chunk 头(4 字节)。
     */
    public static void writeChunkHeader(ByteBuf buf, int payloadLength, boolean isEndChunk) {
        buf.writeShort(payloadLength);
        if (isEndChunk) {
            buf.writeShort(0x0E00); // 0x00 0x0E,大端
        } else {
            buf.writeShort(0x0000);
        }
    }

    /**
     * 读一个 chunk 头,返回 payload 长度。
     */
    public static int readChunkLength(ByteBuf buf) {
        return buf.readUnsignedShort();
    }

    /**
     * 判断是否为最后一个 chunk。
     */
    public static boolean isEndChunk(ByteBuf buf) {
        int marker = buf.readUnsignedShort();
        // 0x0E00 = marker 0x0E(高字节)+0x00(低字节),大端
        return marker == 0x0E00 || marker == 0x000E;
    }

    // ===== Bolt 4.4 值写入 =====

    public static void writeNull(ByteBuf buf) {
        buf.writeByte(BoltConstants.MARKER_NULL);
    }

    public static void writeBoolean(ByteBuf buf, boolean value) {
        buf.writeByte(value ? BoltConstants.MARKER_BOOLEAN_TRUE : BoltConstants.MARKER_BOOLEAN_FALSE);
    }

    public static void writeInt(ByteBuf buf, long value) {
        if (value >= -16 && value <= 127) {
            // Tiny int(0xF0-0xFF 范围,这里简单用 INT_8)
            writeInt8(buf, value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            writeInt8(buf, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            writeInt16(buf, (int) value);
        } else if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
            writeInt32(buf, (int) value);
        } else {
            writeInt64(buf, value);
        }
    }

    public static void writeInt8(ByteBuf buf, long value) {
        buf.writeByte(BoltConstants.MARKER_INT_8);
        buf.writeByte((int) value);
    }

    public static void writeInt16(ByteBuf buf, int value) {
        buf.writeByte(BoltConstants.MARKER_INT_16);
        buf.writeShort(value);
    }

    public static void writeInt32(ByteBuf buf, int value) {
        buf.writeByte(BoltConstants.MARKER_INT_32);
        buf.writeInt(value);
    }

    public static void writeInt64(ByteBuf buf, long value) {
        buf.writeByte(BoltConstants.MARKER_INT_64);
        buf.writeLong(value);
    }

    public static void writeString(ByteBuf buf, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int len = bytes.length;
        if (len <= 0xFF) {
            buf.writeByte(BoltConstants.MARKER_STRING_8);
            buf.writeByte(len);
        } else if (len <= 0xFFFF) {
            buf.writeByte(BoltConstants.MARKER_STRING_16);
            buf.writeShort(len);
        } else {
            buf.writeByte(BoltConstants.MARKER_STRING_32);
            buf.writeInt(len);
        }
        buf.writeBytes(bytes);
    }

    public static void writeList(ByteBuf buf, List<Object> values) {
        int len = values.size();
        if (len <= 0xFF) {
            buf.writeByte(BoltConstants.MARKER_LIST_8);
            buf.writeByte(len);
        } else if (len <= 0xFFFF) {
            buf.writeByte(BoltConstants.MARKER_LIST_16);
            buf.writeShort(len);
        } else {
            buf.writeByte(BoltConstants.MARKER_LIST_32);
            buf.writeInt(len);
        }
        for (Object v : values) {
            writeValue(buf, v);
        }
    }

    public static void writeMap(ByteBuf buf, Map<String, Object> values) {
        int len = values.size();
        if (len <= 0xFF) {
            buf.writeByte(BoltConstants.MARKER_MAP_8);
            buf.writeByte(len);
        } else {
            buf.writeByte(BoltConstants.MARKER_MAP_16);
            buf.writeShort(len);
        }
        for (Map.Entry<String, Object> e : values.entrySet()) {
            writeString(buf, e.getKey());
            writeValue(buf, e.getValue());
        }
    }

    public static void writeStruct(ByteBuf buf, int signature, Object... fields) {
        // POC 简化:struct 字段数用 1 字节
        buf.writeByte(BoltConstants.MARKER_STRUCT_8);
        buf.writeByte(signature & 0xFF);
        buf.writeByte(fields.length);
        for (Object f : fields) {
            writeValue(buf, f);
        }
    }

    /**
     * 通用值写入(POC 范围:Integer / Long / Boolean / String / Map<String,Object> / List<Object> / null)。
     */
    public static void writeValue(ByteBuf buf, Object value) {
        if (value == null) {
            writeNull(buf);
        } else if (value instanceof Boolean) {
            writeBoolean(buf, (Boolean) value);
        } else if (value instanceof Integer) {
            writeInt(buf, ((Integer) value).longValue());
        } else if (value instanceof Long) {
            writeInt(buf, (Long) value);
        } else if (value instanceof String) {
            writeString(buf, (String) value);
        } else if (value instanceof Map) {
            writeMap(buf, (Map<String, Object>) value);
        } else if (value instanceof List) {
            writeList(buf, (List<Object>) value);
        } else {
            // fallback:写字符串
            writeString(buf, value.toString());
        }
    }

    // ===== Bolt 4.4 值读取(POC 范围)=====

    public static Object readValue(ByteBuf buf) {
        int marker = buf.readUnsignedByte(); /* unsigned */
        if (marker == BoltConstants.MARKER_NULL) {
            return null;
        } else if (marker == BoltConstants.MARKER_BOOLEAN_TRUE) {
            return Boolean.TRUE;
        } else if (marker == BoltConstants.MARKER_BOOLEAN_FALSE) {
            return Boolean.FALSE;
        } else if (marker == BoltConstants.MARKER_INT_8) {
            return (int) buf.readByte();
        } else if (marker == BoltConstants.MARKER_INT_16) {
            return (int) buf.readShort();
        } else if (marker == BoltConstants.MARKER_INT_32) {
            return buf.readInt();
        } else if (marker == BoltConstants.MARKER_INT_64) {
            return buf.readLong();
        } else if (marker == BoltConstants.MARKER_STRING_8) {
            int len = buf.readUnsignedByte();
            return readUtf8(buf, len);
        } else if (marker == BoltConstants.MARKER_STRING_16) {
            int len = buf.readUnsignedShort();
            return readUtf8(buf, len);
        } else if (marker == BoltConstants.MARKER_STRING_32) {
            int len = buf.readInt();
            return readUtf8(buf, len);
        } else if (marker == BoltConstants.MARKER_LIST_8) {
            int len = buf.readUnsignedByte();
            List<Object> list = new ArrayList<>(len);
            for (int i = 0; i < len; i++) list.add(readValue(buf));
            return list;
        } else if (marker == BoltConstants.MARKER_LIST_16) {
            int len = buf.readUnsignedShort();
            List<Object> list = new ArrayList<>(len);
            for (int i = 0; i < len; i++) list.add(readValue(buf));
            return list;
        } else if (marker >= 0xA0 && marker <= 0xAF) {
            // TINY_MAP(N=marker-0xA0)
            int len = marker - 0xA0;
            Map<String, Object> map = new java.util.LinkedHashMap<>(len);
            for (int i = 0; i < len; i++) {
                String k = (String) readValue(buf);
                Object v = readValue(buf);
                map.put(k, v);
            }
            return map;
        } else if (marker >= 0x90 && marker <= 0x9F) {
            // TINY_LIST(N=marker-0x90)
            int len = marker - 0x90;
            List<Object> list = new ArrayList<>(len);
            for (int i = 0; i < len; i++) list.add(readValue(buf));
            return list;
        } else if (marker == BoltConstants.MARKER_MAP_8) {
            int len = buf.readUnsignedByte();
            Map<String, Object> map = new LinkedHashMap<>(len);
            for (int i = 0; i < len; i++) {
                String k = (String) readValue(buf);
                Object v = readValue(buf);
                map.put(k, v);
            }
            return map;
        } else if (marker == BoltConstants.MARKER_MAP_16) {
            int len = buf.readUnsignedShort();
            Map<String, Object> map = new LinkedHashMap<>(len);
            for (int i = 0; i < len; i++) {
                String k = (String) readValue(buf);
                Object v = readValue(buf);
                map.put(k, v);
            }
            return map;
        } else if (marker == BoltConstants.MARKER_STRUCT_8) {
            int sig = buf.readUnsignedByte();
            int nFields = buf.readUnsignedByte();
            List<Object> struct = new ArrayList<>(nFields + 1);
            struct.add(sig); // 第一个元素是 signature
            for (int i = 0; i < nFields; i++) struct.add(readValue(buf));
            return struct;
        } else if (marker >= 0x80 && marker <= 0x8F) {
            // Tiny String
            int len = marker - 0x80;
            return readUtf8(buf, len);
        } else {
            throw new IllegalStateException("Unknown marker: 0x" + Integer.toHexString(marker));
        }
    }

    private static String readUtf8(ByteBuf buf, int len) {
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}