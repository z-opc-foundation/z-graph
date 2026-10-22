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
 *     host: 127.0.0.1          # 控制面 bind 地址，默认回环
 *     api-token: xxx           # 控制面 API Token；留空 = 读环境变量 Z_GRAPH_API_TOKEN
 *     data-dir: /data/zgraph   # 留空 = 进程临时目录仓库（进程退出即弃）
 * </pre>
 *
 * <p><b>host 现在是真配置</b>：{@code GraphControlServer} 原先 bind 的是通配地址
 * （{@code new InetSocketAddress(port)}），本进程连它走 127.0.0.1 纯属事实而非配置；
 * 现在 L3 真的按 host bind（默认回环），这一项才第一次能改到连接目标。</p>
 *
 * <p><b>api-token 与环境变量是同一件事</b>：Spring 的宽松绑定把属性
 * {@code z.graph.api-token} 对应到环境变量 {@code Z_GRAPH_API_TOKEN}，
 * 所以既有的"只注入环境变量"的部署不用改任何东西；而两处都没有时
 * 控制面**拒绝启动**（fail-closed，见 {@code GraphControlServer}）。</p>
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

    /** 控制面 bind 地址；默认回环 127.0.0.1 —— 控制面有写接口，不该默认对外。 */
    private String host = "127.0.0.1";

    /** API Token；留空 = 读环境变量 Z_GRAPH_API_TOKEN（名字未变）；两处都没有 ⇒ 拒绝启动。 */
    private String apiToken;

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

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }
}
