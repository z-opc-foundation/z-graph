package com.zifang.z.graph.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.ArrayList;
import java.util.List;

/**
 * Bolt 4.4 帧解码器:把 ByteBuf 切分成 chunk,合并成完整的 BoltMessage。
 *
 * 帧结构:
 * | 2-byte length | 2-byte marker | payload |
 *
 * payload 以 STRUCT 标记开头：marker(1) + signature(1) + field_count(1) + fields。
 * 解码后 BoltMessage 同时缓存 signature 与字段列表（按出现顺序），handler 直接
 * 读取即可，无需关心 STRUCT 头部的字节切分。
 */
public class BoltMessageDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (in.readableBytes() >= BoltConstants.CHUNK_HEADER_SIZE) {
            in.markReaderIndex();
            int payloadLength = in.readUnsignedShort();
            int marker = in.readUnsignedShort();

            // 检查大 chunk 头(0xFF ?? ?? ?? 表示 4 字节长度)
            if ((payloadLength >>> 8) == 0xFF) {
                throw new IllegalStateException("Large chunk not supported in POC");
            }

            if (in.readableBytes() < payloadLength) {
                in.resetReaderIndex();
                return; // 等待更多数据
            }

            ByteBuf payload = in.readRetainedSlice(payloadLength);
            boolean isEnd = (marker == 0x0E00 || marker == 0x000E);
            out.add(BoltMessage.parse(payload, isEnd));
        }
    }

    /**
     * 解码后的 Bolt 消息包装。
     */
    public static final class BoltMessage {
        public final boolean isEnd;
        public final int signature;
        public final List<Object> fields;
        /** 剩余未被消费的 payload 缓冲区（用于直接读取原始字节）。 */
        public final ByteBuf payload;

        private BoltMessage(boolean isEnd, int signature, List<Object> fields, ByteBuf payload) {
            this.isEnd = isEnd;
            this.signature = signature;
            this.fields = fields;
            this.payload = payload;
        }

        static BoltMessage parse(ByteBuf payload, boolean isEnd) {
            int structMarker = payload.readUnsignedByte();
            int signature = payload.readUnsignedByte();
            int fieldCount;
            int fieldCountBytes;
            if ((structMarker & 0xF0) == 0xB0) {
                // TINY_STRUCT: 4-bit signature + 4-bit field count
                fieldCount = structMarker & 0x0F;
                fieldCountBytes = 0;
            } else if (structMarker == 0xDC) {
                // STRUCT_8: signature(1) + field_count(1)
                fieldCount = payload.readUnsignedByte();
                fieldCountBytes = 1;
            } else if (structMarker == 0xDD) {
                // STRUCT_16: signature(1) + field_count(2)
                fieldCount = payload.readUnsignedShort();
                fieldCountBytes = 2;
            } else {
                throw new IllegalStateException(
                        "Unsupported struct marker 0x" + Integer.toHexString(structMarker & 0xFF));
            }
            List<Object> fields = new ArrayList<>(fieldCount);
            for (int i = 0; i < fieldCount; i++) {
                fields.add(BoltFrames.readValue(payload));
            }
            return new BoltMessage(isEnd, signature, fields, payload);
        }

        public int signature() {
            return signature;
        }

        public void release() {
            payload.release();
        }
    }
}
