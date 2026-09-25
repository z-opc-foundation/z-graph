package com.zifang.z.graph.core;

import com.zifang.z.graph.api.Edge;
import com.zifang.z.graph.api.Node;

/**
 * 一个实体（节点或边）在某个 commit 上留下的版本记录 —— MVCC 版本链的节点。
 *
 * <p>每次提交只把被触碰过的实体写进链里，所以一个节点的版本数等于它被改动的次数，
 * 与图上其他数据无关。{@link Kind#DELETE} 也是链上的一个版本，用来表达
 * "在某个 commit 之后该实体不可见"。</p>
 */
public final class GraphEntityVersion {

    public enum Kind {
        /** 该 commit 之后实体以此内容存在。 */
        UPSERT,
        /** 该 commit 之后实体被删除。 */
        DELETE
    }

    private final long entityId;
    private final Kind kind;
    private final String commitId;
    private final long commitSequence;
    private final long timestampEpochMillis;
    private final Node node;
    private final Edge edge;

    GraphEntityVersion(long entityId,
                       Kind kind,
                       String commitId,
                       long commitSequence,
                       long timestampEpochMillis,
                       Node node,
                       Edge edge) {
        this.entityId = entityId;
        this.kind = kind;
        this.commitId = commitId;
        this.commitSequence = commitSequence;
        this.timestampEpochMillis = timestampEpochMillis;
        this.node = node;
        this.edge = edge;
    }

    public long getEntityId() {
        return entityId;
    }

    public Kind getKind() {
        return kind;
    }

    public String getCommitId() {
        return commitId;
    }

    /** 全局提交序号，单调递增，用于判断版本先后而不依赖墙上时钟。 */
    public long getCommitSequence() {
        return commitSequence;
    }

    public long getTimestampEpochMillis() {
        return timestampEpochMillis;
    }

    public boolean isDelete() {
        return kind == Kind.DELETE;
    }

    /** 该版本对应的节点内容；边版本或删除版本为 null。 */
    public Node getNode() {
        return node;
    }

    /** 该版本对应的边内容；节点版本或删除版本为 null。 */
    public Edge getEdge() {
        return edge;
    }

    @Override
    public String toString() {
        return "GraphEntityVersion{" + (edge == null ? "node:" : "edge:") + entityId
                + " " + kind + " @" + commitSequence + ":" + shortCommit() + "}";
    }

    private String shortCommit() {
        return commitId.length() <= 8 ? commitId : commitId.substring(0, 8);
    }
}
