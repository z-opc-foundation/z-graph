package com.zifang.z.graph.starter.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * z-graph 内嵌版本仓库与 HTTP 控制面的装配开关。
 *
 * <pre>
 * z:
 *   graph:
 *     enabled: true
 *     port: 8090               # 0 = 让内核分配，之后只能读 graphControlServer.port()
 *     data-dir: /data/zgraph   # 留空 = 进程临时目录仓库（进程退出即弃）
 * </pre>
 *
 * <p>没有 host 这一项：{@code GraphControlServer} bind 的是通配地址，本进程连它就走
 * 127.0.0.1，写一个改不动连接目标的 host 只会骗人。</p>
 *
 * <p>旧版四个视图缓存档位（checkpoint-interval / max-retained-views /
 * retained-whole-graph-views / view-layer-limit）随存储层重构移除：引擎原生 MVCC
 * 之后不存在物化视图缓存，无可调。</p>
 */
@ConfigurationProperties(prefix = "z.graph")
public class ZGraphProperties {

    /** 总开关：false（默认）时本 starter 不装任何 Bean，也不碰磁盘和网络。 */
    private boolean enabled = false;

    /** 控制面端口；0 表示让内核分配，此时必须读 server.port() 而不是本配置值。 */
    private int port = 8090;

    /** 文件仓库目录；留空则用进程临时目录仓库。 */
    private String dataDir;

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
}
