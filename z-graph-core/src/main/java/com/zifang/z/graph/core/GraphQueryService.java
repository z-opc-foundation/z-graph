package com.zifang.z.graph.core;

import java.util.List;
import java.util.Map;

/**
 * Nebula Query 层职责的本地实现入口：把查询绑定到指定 commit 或 branch 快照。
 */
public final class GraphQueryService {

    private final GraphVersionStore repository;

    public GraphQueryService(GraphVersionStore repository) {
        this.repository = repository;
    }

    public List<Map<String, Object>> queryCommit(String commitId, String cypher) {
        return repository.checkout(commitId).query(cypher);
    }

    public List<Map<String, Object>> queryCommit(String commitId,
                                                  String cypher,
                                                  Map<String, Object> parameters) {
        GraphCheckout checkout = repository.checkout(commitId);
        return new CypherEngine(checkout.getStore()).execute(cypher, parameters);
    }

    public List<Map<String, Object>> queryBranch(String branch, String cypher) {
        return repository.checkoutBranch(branch).query(cypher);
    }

    public List<Map<String, Object>> queryBranch(String branch,
                                                 String cypher,
                                                 Map<String, Object> parameters) {
        GraphCheckout checkout = repository.checkoutBranch(branch);
        return new CypherEngine(checkout.getStore()).execute(cypher, parameters);
    }

    public GraphWriteTransaction beginWrite(String branch) {
        return repository.beginWrite(branch);
    }
}
