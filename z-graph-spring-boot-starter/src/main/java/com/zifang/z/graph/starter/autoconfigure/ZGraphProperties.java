package com.zifang.z.graph.starter.autoconfigure;

import com.zifang.z.graph.core.GraphVersionStore;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * z-graph 内嵌版本仓库与 HTTP 控制面的装配开关。
 *
 * <pre>
 * z:
 *   graph:
 *     enabled: true
 *     port: 8090               # 0 = 让内核分配，之后只能读 graphControlServer.port()
 *     data-dir: /data/zgraph   # 留空 = 纯内存仓库（不落盘、不建目录）
 *     checkpoint-interval: 32
 *     max-retained-views: 64
 *     retained-whole-graph-views: 4
 *     view-layer-limit: 8
 * </pre>
 *
 * <p>没有 host 这一项：{@code GraphControlServer} bind 的是通配地址，本进程连它就走
 * 127.0.0.1，写一个改不动连接目标的 host 只会骗人。</p>
 *
 * <p>四个档位字段的默认值直接取 {@link GraphVersionStore} 的公开常量，不在这里再抄一遍数字，
 * 免得两处默认值各走各的。</p>
 */
@ConfigurationProperties(prefix = "z.graph")
public class ZGraphProperties {

    /** 总开关：false（默认）时本 starter 不装任何 Bean，也不碰磁盘和网络。 */
    private boolean enabled = false;

    /** 控制面端口；0 表示让内核分配，此时必须读 server.port() 而不是本配置值。 */
    private int port = 8090;

    /** 文件仓库目录；留空则用内存仓库。 */
    private String dataDir;

    /** 多少个 commit 摊平一次视图（整图复制的尖峰就落在这次提交上）。 */
    private int checkpointInterval = GraphVersionStore.DEFAULT_CHECKPOINT_INTERVAL;

    /** 视图 LRU 条数上限（分支 head 视图豁免淘汰）。 */
    private int maxRetainedViews = GraphVersionStore.DEFAULT_MAX_RETAINED_VIEWS;

    /** 视图 LRU 合计可驻留多少份整图（预算随图规模换算）。 */
    private int retainedWholeGraphViews = GraphVersionStore.DEFAULT_RETAINED_WHOLE_GRAPH_VIEWS;

    /** 单个视图最多套叠多少层 delta，超过就摊平。 */
    private int viewLayerLimit = GraphVersionStore.DEFAULT_VIEW_LAYER_LIMIT;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public int getCheckpointInterval() {
        return checkpointInterval;
    }

    public void setCheckpointInterval(int checkpointInterval) {
        this.checkpointInterval = checkpointInterval;
    }

    public int getMaxRetainedViews() {
        return maxRetainedViews;
    }

    public void setMaxRetainedViews(int maxRetainedViews) {
        this.maxRetainedViews = maxRetainedViews;
    }

    public int getRetainedWholeGraphViews() {
        return retainedWholeGraphViews;
    }

    public void setRetainedWholeGraphViews(int retainedWholeGraphViews) {
        this.retainedWholeGraphViews = retainedWholeGraphViews;
    }

    public int getViewLayerLimit() {
        return viewLayerLimit;
    }

    public void setViewLayerLimit(int viewLayerLimit) {
        this.viewLayerLimit = viewLayerLimit;
    }
}
