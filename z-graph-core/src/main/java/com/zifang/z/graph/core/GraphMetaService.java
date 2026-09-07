package com.zifang.z.graph.core;

import com.zifang.z.graph.api.GraphCommit;
import com.zifang.z.graph.api.GraphMergeResult;

import java.util.List;

/**
 * Nebula Meta 层职责的本地实现入口，负责 commit 图、branch head 和 merge 元数据。
 * 数据快照仍由 {@link GraphVersionStore} 管理，调用方不需要直接操作内部索引。
 */
public final class GraphMetaService {

    private final GraphVersionStore repository;

    public GraphMetaService(GraphVersionStore repository) {
        this.repository = repository;
    }

    public GraphCommit head(String branch) {
        return repository.getBranchHead(branch);
    }

    public GraphCommit commit(String commitId) {
        return repository.getCommit(commitId);
    }

    public List<GraphCommit> commits() {
        return repository.listCommits();
    }

    public List<GraphCommit> log(String ref) {
        return repository.log(ref);
    }

    public List<String> branches() {
        return repository.listBranches();
    }

    public GraphCommit branch(String name, String fromCommitId) {
        return repository.createBranch(name, fromCommitId);
    }

    public GraphMergeResult merge(String targetBranch,
                                  String sourceBranch,
                                  String author,
                                  String message) {
        return repository.merge(targetBranch, sourceBranch, author, message);
    }
}
