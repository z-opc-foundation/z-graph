package com.zifang.z.graph.api;

import java.util.List;
import java.util.Objects;

/**
 * 图版本提交记录。
 *
 * <p>提交只保存不可变元数据，具体图数据由版本存储按 commitId 管理。一个提交
 * 通常有一个父提交，合并提交有两个父提交。</p>
 */
public final class GraphCommit {

    private final String id;
    private final List<String> parents;
    private final String branch;
    private final String author;
    private final String message;
    private final long timestampEpochMillis;
    private final long nodeCount;
    private final long edgeCount;

    public GraphCommit(String id,
                       List<String> parents,
                       String branch,
                       String author,
                       String message,
                       long timestampEpochMillis,
                       long nodeCount,
                       long edgeCount) {
        this.id = Objects.requireNonNull(id, "id");
        this.parents = List.copyOf(parents == null ? List.of() : parents);
        this.branch = Objects.requireNonNull(branch, "branch");
        this.author = author == null ? "unknown" : author;
        this.message = message == null ? "" : message;
        this.timestampEpochMillis = timestampEpochMillis;
        this.nodeCount = nodeCount;
        this.edgeCount = edgeCount;
    }

    public String getId() {
        return id;
    }

    public List<String> getParents() {
        return parents;
    }

    public String getBranch() {
        return branch;
    }

    public String getAuthor() {
        return author;
    }

    public String getMessage() {
        return message;
    }

    public long getTimestampEpochMillis() {
        return timestampEpochMillis;
    }

    public long getNodeCount() {
        return nodeCount;
    }

    public long getEdgeCount() {
        return edgeCount;
    }

    public boolean isMergeCommit() {
        return parents.size() > 1;
    }

    @Override
    public String toString() {
        return "GraphCommit{" +
                "id='" + id + '\'' +
                ", parents=" + parents +
                ", branch='" + branch + '\'' +
                ", message='" + message + '\'' +
                ", nodeCount=" + nodeCount +
                ", edgeCount=" + edgeCount +
                '}';
    }
}
