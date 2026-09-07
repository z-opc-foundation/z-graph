package com.zifang.z.graph.bolt;

import com.zifang.z.graph.core.CypherEngine;
import com.zifang.z.graph.core.GraphVersionStore;
import com.zifang.z.graph.core.GraphWriteTransaction;
import com.zifang.z.graph.protocol.BoltConstants;
import com.zifang.z.graph.protocol.BoltFrames;
import com.zifang.z.graph.protocol.BoltMessageDecoder.BoltMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bolt 4.4 消息处理 — 当前支持:
 * - HELLO:握手,返回 SUCCESS(connection_id + server info)
 * - BEGIN/RUN/PULL/COMMIT/ROLLBACK:连接级事务,提交到 main 图版本
 * - RUN:支持参数 Map,读查询绑定当前 head,写查询自动提交 commit
 * - PULL:拉取结果,返回 SUCCESS(has_more=false) + RECORD * N + SUCCESS 完成
 * - RESET/DISCARD/GOODBYE:流清理和连接生命周期
 */
public class BoltMessageHandler extends SimpleChannelInboundHandler<BoltMessage> {

    private static final Logger log = LogManager.getLogger(BoltMessageHandler.class);

    private final GraphVersionStore graphRepository;
    private GraphWriteTransaction activeTransaction;

    public BoltMessageHandler() {
        this(new GraphVersionStore());
    }

    public BoltMessageHandler(GraphVersionStore graphRepository) {
        this.graphRepository = graphRepository;
    }

    /** qid → 当前流的执行结果 */
    private final Map<Long, List<Map<String, Object>>> streams = new ConcurrentHashMap<>();
    /** qid → 字段名列表 */
    private final Map<Long, List<String>> streamFields = new ConcurrentHashMap<>();
    /** qid → 下一个要返回的 record 索引 */
    private final Map<Long, Integer> streamCursor = new ConcurrentHashMap<>();
    /** qid 生成器(AtomicLong 保证多 event-loop 线程并发分配 qid 不冲突) */
    private final AtomicLong nextQid = new AtomicLong(0L);

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, BoltMessage msg) {
        try {
            int signature = msg.signature();
            log.debug("Received message signature=0x{}", String.format("%02X", signature & 0xFF));

            switch (signature) {
                case BoltConstants.MSG_HELLO -> handleHello(ctx, msg);
                case BoltConstants.MSG_RUN -> handleRun(ctx, msg);
                case BoltConstants.MSG_PULL -> handlePull(ctx, msg);
                case BoltConstants.MSG_GOODBYE -> {
                    log.info("Client sent GOODBYE, closing connection");
                    if (activeTransaction != null) {
                        activeTransaction.rollback();
                        activeTransaction = null;
                    }
                    ctx.close();
                }
                case BoltConstants.MSG_RESET -> handleReset(ctx);
                case BoltConstants.MSG_DISCARD -> handleDiscard(ctx, msg);
                case BoltConstants.MSG_BEGIN -> handleBegin(ctx, msg);
                case BoltConstants.MSG_COMMIT -> handleCommit(ctx, msg);
                case BoltConstants.MSG_ROLLBACK -> handleRollback(ctx, msg);
                default -> {
                    log.warn("Unsupported message signature=0x{}", String.format("%02X", signature & 0xFF));
                    writeFailure(ctx, "Unsupported message signature: 0x" + Integer.toHexString(signature & 0xFF));
                }
            }
        } catch (Exception e) {
            log.error("Error handling Bolt message", e);
            writeFailure(ctx, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            msg.release();
        }
    }

    private void handleBegin(ChannelHandlerContext ctx, BoltMessage msg) {
        if (activeTransaction != null) {
            writeFailure(ctx, "Transaction already active");
            return;
        }
        activeTransaction = graphRepository.beginWrite("main");
        writeSuccess(ctx, Map.of("tx_id", UUID.randomUUID().toString(), "branch", "main"));
    }

    private void handleCommit(ChannelHandlerContext ctx, BoltMessage msg) {
        if (activeTransaction == null) {
            writeFailure(ctx, "No active transaction");
            return;
        }
        try {
            var commit = activeTransaction.commit("bolt", "Bolt transaction commit");
            activeTransaction = null;
            writeSuccess(ctx, Map.of("commit", commit.getId(), "branch", "main"));
        } catch (RuntimeException error) {
            activeTransaction.rollback();
            activeTransaction = null;
            throw error;
        }
    }

    private void handleRollback(ChannelHandlerContext ctx, BoltMessage msg) {
        if (activeTransaction == null) {
            writeFailure(ctx, "No active transaction");
            return;
        }
        activeTransaction.rollback();
        activeTransaction = null;
        writeSuccess(ctx, Map.of());
    }

    private void handleHello(ChannelHandlerContext ctx, BoltMessage msg) {
        // HELLO 结构:[extra_metadata_map]，字段已被 decoder 解析
        if (!msg.fields.isEmpty()) {
            log.info("HELLO metadata: {}", msg.fields.get(0));
        }
        Map<String, Object> successMeta = new LinkedHashMap<>();
        successMeta.put("connection_id", UUID.randomUUID().toString());
        successMeta.put("server", "z-graph/1.0.0-SNAPSHOT");
        successMeta.put("edition", "community");
        writeSuccess(ctx, successMeta);
    }

    private void handleRun(ChannelHandlerContext ctx, BoltMessage msg) {
        // RUN 结构:[statement, parameters, [extra_metadata]] — decoder 已切好字段
        if (msg.fields.isEmpty()) {
            writeFailure(ctx, "RUN missing statement");
            return;
        }
        String cypher = (String) msg.fields.get(0);
        Map<String, Object> parameters = msg.fields.size() >= 2 && msg.fields.get(1) instanceof Map
                ? (Map<String, Object>) msg.fields.get(1) : Map.of();
        log.info("RUN cypher='{}' params={}", cypher, parameters);

        long qid = nextQid.getAndIncrement();
        try {
            List<Map<String, Object>> rows = executeCypher(cypher, parameters);
            List<String> fields = rows.isEmpty() ? List.of() : List.copyOf(rows.get(0).keySet());
            streams.put(qid, rows);
            streamFields.put(qid, fields);
            streamCursor.put(qid, 0);
            writeSuccess(ctx, buildRunSuccessMeta(qid, fields));
        } catch (Exception e) {
            streams.remove(qid);
            streamFields.remove(qid);
            streamCursor.remove(qid);
            writeFailure(ctx, "Cypher execution failed: " + e.getMessage());
        }
    }

    /**
     * Bolt RUN 的统一执行入口：读请求绑定当前 main head，写请求在独立工作区提交成一个 commit。
     * 这样不同连接共享同一个图和版本历史，同时不会让一个连接直接修改旧快照。
     */
    private List<Map<String, Object>> executeCypher(String cypher, Map<String, Object> parameters) {
        String upper = cypher.trim().toUpperCase();
        boolean mutating = upper.startsWith("CREATE")
                || upper.startsWith("MERGE")
                || (upper.startsWith("MATCH") && (upper.contains(" SET ")
                || upper.contains(" DELETE ")
                || upper.contains("DETACH DELETE")));
        if (activeTransaction != null) {
            return new CypherEngine(activeTransaction, graphRepository).execute(cypher, parameters);
        }
        if (!mutating) {
            return new CypherEngine(graphRepository.checkoutBranch("main").getStore(), graphRepository)
                    .execute(cypher, parameters);
        }

        IllegalStateException lastConflict = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            GraphWriteTransaction transaction = graphRepository.beginWrite("main");
            List<Map<String, Object>> rows = new CypherEngine(transaction, graphRepository).execute(cypher, parameters);
            try {
                transaction.commit("bolt", "RUN " + cypher);
                return rows;
            } catch (IllegalStateException conflict) {
                lastConflict = conflict;
            }
        }
        throw lastConflict == null
                ? new IllegalStateException("Could not commit Bolt write")
                : lastConflict;
    }

    private void handlePull(ChannelHandlerContext ctx, BoltMessage msg) {
        // PULL 结构:[{qid, n}] — 字段已被 decoder 解析
        Map<String, Object> extra = msg.fields.isEmpty()
                ? Map.of()
                : (Map<String, Object>) msg.fields.get(0);
        long qid = ((Number) extra.get("qid")).longValue();
        long n = extra.containsKey("n") ? ((Number) extra.get("n")).longValue() : -1;

        List<Map<String, Object>> rows = streams.get(qid);
        List<String> fields = streamFields.get(qid);
        Integer cursorObj = streamCursor.get(qid);
        int cursor = cursorObj == null ? 0 : cursorObj;

        if (rows == null) {
            writeFailure(ctx, "Stream not found: qid=" + qid);
            return;
        }

        // 先返回 PULL_SUCCESS(流的元数据)
        writePullSuccess(ctx, qid);

        // 逐 record 返回
        int emitted = 0;
        while (cursor < rows.size() && (n < 0 || emitted < n)) {
            Map<String, Object> row = rows.get(cursor++);
            emitted++;
            writeRecord(ctx, fields, row);
        }
        streamCursor.put(qid, cursor);
        boolean hasMore = cursor < rows.size();
        if (!hasMore) {
            // 清理流
            streams.remove(qid);
            streamFields.remove(qid);
            streamCursor.remove(qid);
        }
        // 流完成 SUCCESS
        writeSuccess(ctx, buildPullCompleteMeta(qid, hasMore));
    }

    private void handleDiscard(ChannelHandlerContext ctx, BoltMessage msg) {
        // DISCARD 结构:[{qid, n}] — 字段已被 decoder 解析
        if (msg.fields.isEmpty()) {
            writeSuccess(ctx, Map.of());
            return;
        }
        Map<String, Object> extra = (Map<String, Object>) msg.fields.get(0);
        long qid = ((Number) extra.get("qid")).longValue();
        streams.remove(qid);
        streamFields.remove(qid);
        streamCursor.remove(qid);
        writeSuccess(ctx, buildDiscardSuccessMeta(qid));
    }

    private void handleReset(ChannelHandlerContext ctx) {
        if (activeTransaction != null) {
            activeTransaction.rollback();
            activeTransaction = null;
        }
        streams.clear();
        streamFields.clear();
        streamCursor.clear();
        writeSuccess(ctx, Map.of());
    }

    // ===== 响应构造 =====

    private Map<String, Object> buildRunSuccessMeta(long qid, List<String> fields) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("qid", qid);
        meta.put("fields", fields);
        meta.put("t_first", 0L);
        return meta;
    }

    private void writePullSuccess(ChannelHandlerContext ctx, long qid) {
        // PULL_SUCCESS 是另一个响应签名(POC 阶段用普通 SUCCESS 简化)
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("qid", qid);
        writeSuccess(ctx, meta);
    }

    private Map<String, Object> buildPullCompleteMeta(long qid, boolean hasMore) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("qid", qid);
        meta.put("has_more", hasMore);
        return meta;
    }

    private Map<String, Object> buildDiscardSuccessMeta(long qid) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("qid", qid);
        return meta;
    }

    private void writeSuccess(ChannelHandlerContext ctx, Map<String, Object> meta) {
        ByteBuf buf = ctx.alloc().buffer();
        BoltFrames.writeStruct(buf, BoltConstants.RESP_SUCCESS, meta);
        ctx.writeAndFlush(wrapAsChunk(buf));
    }

    private void writeIgnored(ChannelHandlerContext ctx) {
        ByteBuf buf = ctx.alloc().buffer();
        BoltFrames.writeStruct(buf, BoltConstants.RESP_IGNORED, Map.of());
        ctx.writeAndFlush(wrapAsChunk(buf));
    }

    private void writeFailure(ChannelHandlerContext ctx, String message) {
        ByteBuf buf = ctx.alloc().buffer();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("code", "z-graph.POC.Failure");
        meta.put("message", message);
        BoltFrames.writeStruct(buf, BoltConstants.RESP_FAILURE, meta);
        ctx.writeAndFlush(wrapAsChunk(buf));
    }

    private void writeRecord(ChannelHandlerContext ctx, List<String> fields, Map<String, Object> row) {
        ByteBuf buf = ctx.alloc().buffer();
        // RECORD 结构:struct signature=0x71,fields=[value1, value2, ...] 按字段顺序
        java.util.List<Object> values = new java.util.ArrayList<>(fields.size());
        for (String f : fields) values.add(row.get(f));
        // values.toArray() 把 List 解构成 varargs,否则 writeStruct 会把它当成 1 个字段
        BoltFrames.writeStruct(buf, BoltConstants.RESP_RECORD, values.toArray());
        ctx.writeAndFlush(wrapAsChunk(buf));
    }

    /**
     * 把整个消息体包成单 chunk(POC 阶段消息短到不需要多 chunk)。
     */
    private ByteBuf wrapAsChunk(ByteBuf payload) {
        ByteBuf chunk = payload.alloc().buffer(payload.readableBytes() + 4);
        int len = payload.readableBytes();
        chunk.writeShort(len);
        chunk.writeShort(0x0E00); // end marker
        chunk.writeBytes(payload);
        payload.release();
        return chunk;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Channel exception", cause);
        ctx.close();
    }
}