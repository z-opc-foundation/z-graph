package com.zifang.z.graph.bolt;

import com.zifang.z.graph.protocol.BoltConstants;
import com.zifang.z.graph.protocol.BoltFrames;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 Bolt 4.4 客户端：单连接、同步 HELLO / RUN / PULL 流程，用于服务端 e2e 集成测试。
 */
public class BoltTestClient implements AutoCloseable {

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;

    public BoltTestClient(String host, int port) throws IOException {
        this.socket = new Socket(host, port);
        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());
    }

    /** HELLO 握手：发送空 extra 元数据，返回 SUCCESS 中的 server map。 */
    public Map<String, Object> hello() throws IOException {
        ByteBuf buf = Unpooled.buffer();
        BoltFrames.writeStruct(buf, BoltConstants.MSG_HELLO, Map.of());
        writeMessage(buf);
        return readMessage().meta;
    }

    /** RUN statement，返回 SUCCESS metadata（含 qid / fields）。如收到 FAILURE 则抛 IOException。 */
    public Map<String, Object> run(String cypher, Map<String, Object> params) throws IOException {
        ByteBuf buf = Unpooled.buffer();
        Object[] fields = params == null || params.isEmpty()
                ? new Object[]{cypher, Map.of()}
                : new Object[]{cypher, params};
        BoltFrames.writeStruct(buf, BoltConstants.MSG_RUN, fields);
        writeMessage(buf);
        Frame frame = readMessage();
        if (frame.signature == BoltConstants.RESP_FAILURE) {
            throw new IOException("RUN failed: " + frame.meta);
        }
        return frame.meta;
    }

    public Map<String, Object> run(String cypher) throws IOException {
        return run(cypher, null);
    }

    /** PULL all（n=-1）,按 RUN meta 中的 fields 顺序将 RECORD 字段拼装成 Map。 */
    public List<Map<String, Object>> pullAll(List<String> fields) throws IOException {
        ByteBuf buf = Unpooled.buffer();
        BoltFrames.writeStruct(buf, BoltConstants.MSG_PULL, Map.of("n", -1));
        writeMessage(buf);
        return consumeStream(fields);
    }

    /** PULL 指定 qid 的流,按 fields 顺序把 RECORD 字段拼成 Map。 */
    public List<Map<String, Object>> pull(long qid, int n, List<String> fields) throws IOException {
        ByteBuf buf = Unpooled.buffer();
        BoltFrames.writeStruct(buf, BoltConstants.MSG_PULL, Map.of("qid", qid, "n", n));
        writeMessage(buf);
        return consumeStream(fields);
    }

    /** 兼容旧签名(没有 fields 时按位置索引 i0/i1/... 组装)。 */
    public List<Map<String, Object>> pull(long qid, int n) throws IOException {
        return pull(qid, n, null);
    }

    public List<Map<String, Object>> pullAll() throws IOException {
        return pullAll(null);
    }

    private List<Map<String, Object>> consumeStream(List<String> fields) throws IOException {
        // 服务端先发 PULL_SUCCESS（携带流元数据），然后逐 record，最后发拉取完成 SUCCESS。
        List<Map<String, Object>> rows = new ArrayList<>();
        Frame frame;
        boolean complete = false;
        while (!complete) {
            frame = readMessage();
            if (frame.signature == BoltConstants.RESP_RECORD) {
                Map<String, Object> row = new LinkedHashMap<>();
                int n = frame.values.size();
                for (int i = 0; i < n; i++) {
                    String key = (fields != null && i < fields.size()) ? fields.get(i) : "i" + i;
                    row.put(key, frame.values.get(i));
                }
                rows.add(row);
            } else if (frame.signature == BoltConstants.RESP_SUCCESS) {
                if (frame.meta.containsKey("has_more")) complete = true;
                // 第一个 SUCCESS 是 PULL_SUCCESS,继续循环读取 records
            } else if (frame.signature == BoltConstants.RESP_FAILURE) {
                throw new IOException("PULL failure: " + frame.meta);
            }
        }
        return rows;
    }

    /** 从最近一次 RUN 的 SUCCESS 元数据中取出 qid。 */
    public long lastQid(Map<String, Object> runMeta) {
        Object qid = runMeta.get("qid");
        if (qid instanceof Number n) return n.longValue();
        throw new IllegalStateException("qid not found in RUN meta: " + runMeta);
    }

    /** 从最近一次 RUN 的 SUCCESS 元数据中取出 fields（按顺序的字段名列表）。 */
    @SuppressWarnings("unchecked")
    public List<String> lastFields(Map<String, Object> runMeta) {
        Object fields = runMeta.get("fields");
        if (fields instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object f : list) out.add(f == null ? null : f.toString());
            return out;
        }
        throw new IllegalStateException("fields not found in RUN meta: " + runMeta);
    }

    public void begin() throws IOException {
        ByteBuf buf = Unpooled.buffer();
        BoltFrames.writeStruct(buf, BoltConstants.MSG_BEGIN, Map.of());
        writeMessage(buf);
        readMessage();
    }

    public String commit() throws IOException {
        ByteBuf buf = Unpooled.buffer();
        BoltFrames.writeStruct(buf, BoltConstants.MSG_COMMIT, Map.of());
        writeMessage(buf);
        Frame frame = readMessage();
        Object commit = frame.meta.get("commit");
        return commit == null ? null : commit.toString();
    }

    public void rollback() throws IOException {
        ByteBuf buf = Unpooled.buffer();
        BoltFrames.writeStruct(buf, BoltConstants.MSG_ROLLBACK, Map.of());
        writeMessage(buf);
        readMessage();
    }

    public void goodbye() throws IOException {
        ByteBuf buf = Unpooled.buffer();
        BoltFrames.writeStruct(buf, BoltConstants.MSG_GOODBYE);
        writeMessage(buf);
    }

    public void close() throws IOException {
        socket.close();
    }

    // ==================== wire format helpers ====================

    private void writeMessage(ByteBuf payload) throws IOException {
        byte[] bytes = new byte[payload.readableBytes()];
        payload.getBytes(payload.readerIndex(), bytes);
        int chunkSize = bytes.length;
        out.writeShort(chunkSize);
        out.writeShort(0x0E00); // end chunk marker
        out.write(bytes);
        out.flush();
    }

    private Frame readMessage() throws IOException {
        // 当前实现只读取单 chunk（消息体足够小），简化解析
        int chunkLen = in.readUnsignedShort();
        int marker = in.readUnsignedShort();
        boolean isEnd = marker == 0x0E00 || marker == 0x000E;
        if (!isEnd) {
            throw new IOException("Multi-chunk messages not supported in BoltTestClient");
        }
        byte[] payload = in.readNBytes(chunkLen);
        return parseFrame(payload);
    }

    @SuppressWarnings("unchecked")
    private Frame parseFrame(byte[] payload) {
        ByteBuf buf = Unpooled.wrappedBuffer(payload);
        // 第一个字节：marker
        byte marker = buf.readByte();
        if (marker != (byte) BoltConstants.MARKER_STRUCT_8) {
            throw new IllegalStateException("Expected struct marker, got 0x" + Integer.toHexString(marker & 0xFF));
        }
        int signature = buf.readByte() & 0xFF;
        int fieldCount = buf.readByte() & 0xFF;
        Map<String, Object> meta = new LinkedHashMap<>();
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < fieldCount; i++) {
            Object value = BoltFrames.readValue(buf);
            values.add(value);
        }
        if (signature == BoltConstants.RESP_SUCCESS || signature == BoltConstants.RESP_FAILURE) {
            // metadata 是 fields[0]
            if (!values.isEmpty() && values.get(0) instanceof Map) {
                meta = (Map<String, Object>) values.get(0);
            }
        }
        System.err.println("BoltTestClient.parseFrame: sig=0x" + Integer.toHexString(signature)
                + " values=" + values);
        return new Frame(signature, meta, values);
    }

    private record Frame(int signature, Map<String, Object> meta, List<Object> values) {
    }

    public static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    public static int asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(o));
    }

    public static List<String> asStringList(Object o) {
        @SuppressWarnings("unchecked")
        List<Object> raw = (List<Object>) o;
        List<String> out = new ArrayList<>();
        for (Object v : raw) out.add(asString(v));
        return out;
    }

    public static byte[] utf8(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
