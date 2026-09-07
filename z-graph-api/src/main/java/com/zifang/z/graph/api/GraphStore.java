package com.zifang.z.graph.api;

import java.util.*;

/**
 * 图存储接口 — 图引擎核心抽象。
 * <p>
 * 定义图数据库的 CRUD 操作和遍历能力，具体实现在 z-graph-core。
 * 设计参考 Neo4j Embedded API + Gremlin Graph 接口。
 */
public interface GraphStore {

    // ==================== 节点操作 ====================

    /** 创建节点，自动生成 ID，返回新节点 */
    Node addNode(String label, Map<String, Object> properties);

    /** 创建指定 ID 的节点 */
    Node addNode(long id, String label, Map<String, Object> properties);

    /** 按 ID 获取节点，不存在返回 null */
    Node getNode(long id);

    /** 更新节点属性（合并写入） */
    void updateNode(long id, Map<String, Object> properties);

    /** 删除节点及其所有边 */
    boolean removeNode(long id);

    /** 按标签获取所有节点 ID 列表 */
    List<Long> getNodeIdsByLabel(String label);

    /** 获取所有节点数量 */
    long getNodeCount();

    // ==================== 边操作 ====================

    /** 创建边，自动生成 ID，返回新边 */
    Edge addEdge(String type, long startNodeId, long endNodeId, Map<String, Object> properties);

    /** 创建指定 ID 的边 */
    Edge addEdge(long id, String type, long startNodeId, long endNodeId, Map<String, Object> properties);

    /** 按 ID 获取边，不存在返回 null */
    Edge getEdge(long id);

    /** 更新边属性（合并写入） */
    void updateEdge(long id, Map<String, Object> properties);

    /** 删除边 */
    boolean removeEdge(long id);

    /** 获取某节点的所有出边 */
    List<Edge> getOutEdges(long nodeId);

    /** 获取某节点的所有入边 */
    List<Edge> getInEdges(long nodeId);

    /** 获取某节点的所有边（出入双向） */
    List<Edge> getEdges(long nodeId);

    /** 获取某类型的边 */
    List<Edge> getEdgesByType(String edgeType);

    /** 获取所有边数量 */
    long getEdgeCount();

    // ==================== 遍历 ====================

    /** 从指定节点做 N 步向外遍历，返回可达节点集合 */
    Set<Long> traverse(long startNodeId, int maxDepth, String edgeType);

    // ==================== 属性索引（基础）====================

    /** 按标签+属性精确匹配查找节点 */
    List<Long> findNodesByProperty(String label, String propertyKey, Object propertyValue);

    /** 获取所有节点，结果按节点 ID 升序排列。 */
    List<Node> getAllNodes();

    /** 获取所有边，结果按边 ID 升序排列。 */
    List<Edge> getAllEdges();

    /** 获取所有节点 ID，结果按节点 ID 升序排列。 */
    default List<Long> getAllNodeIds() {
        return getAllNodes().stream().map(Node::getId).toList();
    }

    /** 获取所有边 ID，结果按边 ID 升序排列。 */
    default List<Long> getAllEdgeIds() {
        return getAllEdges().stream().map(Edge::getId).toList();
    }

    // ==================== 事务（简化）====================

    /** 提交当前变更（内存模式下是 no-op） */
    void commit();

    /** 回滚变更（内存模式下是 no-op） */
    void rollback();
}
