package com.zifang.z.graph.protocol;

import io.netty.buffer.ByteBuf;

/**
 * Bolt 4.4 二进制协议常量与结构体定义。
 *
 * 参考规范:https://neo4j.com/docs/bolt/current/bolt-protocol/
 *
 * Bolt 消息由 chunk 组成,每个 chunk 头 4 字节:2 字节长度 + 2 字节签名标记。
 * 多 chunk 消息按 0x00 0x00 0x00 ... 0x00 0x01 0x0E 0x00 拆分(最后一个 chunk 头标记)。
 */
public final class BoltConstants {

    private BoltConstants() {}

    // ===== Chunk 头标记 =====
    public static final int CHUNK_HEADER_SIZE = 4;
    /** 大 chunk 头标记(> 0x7FFF 长度) */
    public static final int CHUNK_EXT_MARKER = 0xFF;
    /** 最后一个 chunk 标记(0x00 0x0E 0x00 0x00) */
    public static final int CHUNK_END_MARKER_LOW = 0x00;
    public static final int CHUNK_END_MARKER_HIGH = 0x0E;

    // ===== Bolt 4.4 结构体签名(StructType) =====
    /** Node:0x4E */
    public static final int SIG_NODE = 0x4E;
    /** Relationship:0x52 */
    public static final int SIG_RELATIONSHIP = 0x52;
    /** Path:0x50 */
    public static final int SIG_PATH = 0x50;
    /** Date:0x44 */
    public static final int SIG_DATE = 0x44;
    /** Time:0x54 */
    public static final int SIG_TIME = 0x54;
    /** DateTime:0x49 */
    public static final int SIG_DATETIME = 0x49;
    /** Duration:0x45 */
    public static final int SIG_DURATION = 0x45;
    /** Point2D:0x58 */
    public static final int SIG_POINT2D = 0x58;
    /** Point3D:0x59 */
    public static final int SIG_POINT3D = 0x59;
    /** ByteArray:0x42 */
    public static final int SIG_BYTEARRAY = 0x42;
    /** String:0x81 */
    public static final int SIG_STRING = 0x81;
    /** ByteString:0x82 */
    public static final int SIG_BYTESTRING = 0x82;

    // ===== Bolt 4.4 消息签名 =====
    /** HELLO 消息:0x01 */
    public static final int MSG_HELLO = 0x01;
    /** GOODBYE 消息:0x02 */
    public static final int MSG_GOODBYE = 0x02;
    /** RESET 消息:0x0F */
    public static final int MSG_RESET = 0x0F;
    /** RUN 消息:0x10 */
    public static final int MSG_RUN = 0x10;
    /** BEGIN 消息:0x11 */
    public static final int MSG_BEGIN = 0x11;
    /** COMMIT 消息:0x12 */
    public static final int MSG_COMMIT = 0x12;
    /** ROLLBACK 消息:0x13 */
    public static final int MSG_ROLLBACK = 0x13;
    /** PULL 消息:0x3F(0x3E 用于3.x) */
    public static final int MSG_PULL = 0x3F;
    /** DISCARD 消息:0x2F */
    public static final int MSG_DISCARD = 0x2F;

    // ===== 响应签名(服务器→客户端) =====
    /** SUCCESS:0x70 */
    public static final int RESP_SUCCESS = 0x70;
    /** RECORD:0x71 */
    public static final int RESP_RECORD = 0x71;
    /** IGNORED:0x7E */
    public static final int RESP_IGNORED = 0x7E;
    /** FAILURE:0x7F */
    public static final int RESP_FAILURE = 0x7F;

    // ===== Bolt 4.4 数据类型标记 =====
    public static final int MARKER_TINY_STRING = 0x80;        // 0x80-0x8F 范围
    public static final int MARKER_TINY_STRING_MAX = 0x8F;
    public static final int MARKER_TINY_LIST = 0x90;         // 0x90-0x9F
    public static final int MARKER_TINY_MAP = 0xA0;          // 0xA0-0xAF
    public static final int MARKER_TINY_STRUCT = 0xB0;       // 0xB0-0xBF
    public static final int MARKER_NULL = 0xC0;
    public static final int MARKER_FLOAT = 0xC1;
    public static final int MARKER_BOOLEAN_TRUE = 0xC3;
    public static final int MARKER_BOOLEAN_FALSE = 0xC2;
    public static final int MARKER_INT_8 = 0xC8;
    public static final int MARKER_INT_16 = 0xC9;
    public static final int MARKER_INT_32 = 0xCA;
    public static final int MARKER_INT_64 = 0xCB;
    public static final int MARKER_STRING_8 = 0xD0;
    public static final int MARKER_STRING_16 = 0xD1;
    public static final int MARKER_STRING_32 = 0xD2;
    public static final int MARKER_LIST_8 = 0xD4;
    public static final int MARKER_LIST_16 = 0xD5;
    public static final int MARKER_LIST_32 = 0xD6;
    public static final int MARKER_MAP_8 = 0xD8;
    public static final int MARKER_MAP_16 = 0xD9;
    public static final int MARKER_MAP_32 = 0xDA;
    public static final int MARKER_STRUCT_8 = 0xDC;
    public static final int MARKER_STRUCT_16 = 0xDD;

    // ===== 常量值 =====
    /** Bolt 4.4 协议版本 major:minor */
    public static final int BOLT_VERSION = 0x0404;
}