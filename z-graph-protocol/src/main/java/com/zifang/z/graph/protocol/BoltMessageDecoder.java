package com.zifang.z.graph.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * Bolt 4.4 帧解码器:把 ByteBuf 切分成 chunk,合并成完整的 BoltMessage。
 *
 * 帧结构:
 * | 2-byte length | 2-byte marker | payload |
 *
 * 解码后输出 BoltMessage 对象(含完整 payload + 是否结束)。
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
            out.add(new BoltMessage(payload, isEnd));
        }
    }

    /**
     * 解码后的 Bolt 消息包装。
     */
    public static class BoltMessage {
        public final ByteBuf payload;
        public final boolean isEnd;

        public BoltMessage(ByteBuf payload, boolean isEnd) {
            this.payload = payload;
            this.isEnd = isEnd;
        }

        public int signature() {
            return payload.readUnsignedByte();
        }

        public void release() {
            payload.release();
        }
    }
}