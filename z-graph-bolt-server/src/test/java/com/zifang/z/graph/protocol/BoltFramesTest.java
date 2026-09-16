package com.zifang.z.graph.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BoltFrames 编解码单元测试。
 *
 * 覆盖:
 * - null / boolean / int(8/16/32/64)/string(8/16/32)/list/map/struct 编解码
 * - tiny string 0x80-0x8F 编解码
 * - chunk 头读写
 */
class BoltFramesTest {

    @Test
    void testNullRoundTrip() {
        ByteBuf buf = Unpooled.buffer();
        BoltFrames.writeNull(buf);
        Object v = BoltFrames.readValue(buf);
        assertNull(v);
    }

    @Test
    void testBooleanRoundTrip() {
        ByteBuf buf = Unpooled.buffer();
        BoltFrames.writeBoolean(buf, true);
        assertEquals(Boolean.TRUE, BoltFrames.readValue(buf));
        BoltFrames.writeBoolean(buf, false);
        assertEquals(Boolean.FALSE, BoltFrames.readValue(buf));
    }

    @Test
    void testInt8RoundTrip() {
        ByteBuf buf = Unpooled.buffer();
        BoltFrames.writeInt8(buf, 42L);
        assertEquals(42, BoltFrames.readValue(buf));
    }

    @Test
    void testInt16RoundTrip() {
        ByteBuf buf = Unpooled.buffer();
        BoltFrames.writeInt16(buf, 1000);
        assertEquals(1000, BoltFrames.readValue(buf));
    }

    @Test
    void testInt32RoundTrip() {
        ByteBuf buf = Unpooled.buffer();
        BoltFrames.writeInt32(buf, 100_000);
        assertEquals(100_000, BoltFrames.readValue(buf));
    }

    @Test
    void testInt64RoundTrip() {
        ByteBuf buf = Unpooled.buffer();
        BoltFrames.writeInt64(buf, 9_999_999_999L);
        assertEquals(9_999_999_999L, BoltFrames.readValue(buf));
    }

    @Test
    void testString8RoundTrip() {
        ByteBuf buf = Unpooled.buffer();
        BoltFrames.writeString(buf, "hello");
        assertEquals("hello", BoltFrames.readValue(buf));
    }

    @Test
    void testStringLongRoundTrip() {
        ByteBuf buf = Unpooled.buffer();
        String s = "x".repeat(300); // 触发 STRING_16
        BoltFrames.writeString(buf, s);
        assertEquals(s, BoltFrames.readValue(buf));
    }

    @Test
    void testTinyStringRoundTrip() {
        ByteBuf buf = Unpooled.buffer();
        // 0x83 表示 3 字节 tiny string
        buf.writeByte(0x83);
        buf.writeBytes("abc".getBytes());
        assertEquals("abc", BoltFrames.readValue(buf));
    }

    @Test
    void testListRoundTrip() {
        ByteBuf buf = Unpooled.buffer();
        BoltFrames.writeList(buf, List.of(1, "two", Boolean.TRUE));
        Object v = BoltFrames.readValue(buf);
        assertInstanceOf(List.class, v);
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) v;
        assertEquals(3, list.size());
        assertEquals(1, list.get(0));
        assertEquals("two", list.get(1));
        assertEquals(Boolean.TRUE, list.get(2));
    }

    @Test
    void testMapRoundTrip() {
        ByteBuf buf = Unpooled.buffer();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", "z-graph");
        map.put("version", 1);
        BoltFrames.writeMap(buf, map);
        Object v = BoltFrames.readValue(buf);
        assertInstanceOf(Map.class, v);
        @SuppressWarnings("unchecked")
        Map<String, Object> readMap = (Map<String, Object>) v;
        assertEquals("z-graph", readMap.get("name"));
        assertEquals(1, readMap.get("version"));
    }

    @Test
    void testStructRoundTrip() {
        ByteBuf buf = Unpooled.buffer();
        BoltFrames.writeStruct(buf, 0x70, "msg", 42);
        Object v = BoltFrames.readValue(buf);
        assertInstanceOf(List.class, v);
        @SuppressWarnings("unchecked")
        List<Object> struct = (List<Object>) v;
        // 第一个元素是 signature(0x70),后续是字段
        assertEquals(0x70, struct.get(0));
        assertEquals("msg", struct.get(1));
        assertEquals(42, struct.get(2));
    }

    @Test
    void testChunkHeader() {
        ByteBuf buf = Unpooled.buffer();
        BoltFrames.writeChunkHeader(buf, 100, true);
        int len = BoltFrames.readChunkLength(buf);
        assertEquals(100, len);
        assertTrue(BoltFrames.isEndChunk(buf));
    }

    @Test
    void testChunkHeaderNotEnd() {
        ByteBuf buf = Unpooled.buffer();
        BoltFrames.writeChunkHeader(buf, 100, false);
        BoltFrames.readChunkLength(buf);
        assertFalse(BoltFrames.isEndChunk(buf));
    }
}