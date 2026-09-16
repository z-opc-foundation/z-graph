package com.zifang.z.graph.api;

import java.util.List;

/** 版本合并结果，冲突时不会移动目标分支指针。 */
public final class GraphMergeResult {

    private final boolean merged;
    private final String baseCommitId;
    private final GraphCommit commit;
    private final List<String> conflicts;

    public GraphMergeResult(boolean merged,
                            String baseCommitId,
                            GraphCommit commit,
                            List<String> conflicts) {
        this.merged = merged;
        this.baseCommitId = baseCommitId;
        this.commit = commit;
        this.conflicts = List.copyOf(conflicts == null ? List.of() : conflicts);
    }

    public boolean isMerged() {
        return merged;
    }

    public String getBaseCommitId() {
        return baseCommitId;
    }

    public GraphCommit getCommit() {
        return commit;
    }

    public List<String> getConflicts() {
        return conflicts;
    }

    public boolean hasConflicts() {
        return !conflicts.isEmpty();
    }

    @Override
    public String toString() {
        return "GraphMergeResult{" +
                "merged=" + merged +
                ", baseCommitId='" + baseCommitId + '\'' +
                ", commit=" + commit +
                ", conflicts=" + conflicts +
                '}';
    }
}
