package com.zifang.z.graph.starter.host;

import com.zifang.z.graph.bolt.GraphControlServer;
import com.zifang.z.graph.core.GraphVersionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * z-graph 控制面 (GraphControlServer) 内嵌启动器.
 *
 * <p><b>被谁拖进来的 / 为什么要这个文件</b>: z-graph 的 HTTP 面不是 Spring MVC ——
 * {@code com.zifang.z.graph.bolt.GraphControlServer} 是 {@code com.sun.net.httpserver.HttpServer}
 * (z-graph-bolt-server 模块, 构造即 bind), 所以它永远不出现在 {@code /actuator/mappings} 里,
 * 也不在 vite 的 {@code /api} 代理范围内。孵化出口只能是 z-opc 侧的薄代理
 * ({@link GraphProxyController} → {@code /api/graph/**}), 而代理只能连**本 JVM 自己 bind 的那个端口**。
 *
 * <p><b>编译前置条件 (本文件不是自足的)</b>: {@code GraphControlServer} 在
 * {@code io.github.yuku123:z-graph-bolt-server} 里, 而该 artifact **当前不在 main-starter 的
 * 依赖图上** —— main-starter 只有 z-graph-api/core/protocol 1.0.1, 且是被
 * {@code z-kb-graph:1.0.2} 传递带进来的 (z-graph-core 里 18 个类全是引擎, 没有任何 HTTP 类)。
 * 实测: 用 main-starter 当前的 classpath 编译本文件报
 * {@code error: package com.zifang.z.graph.bolt does not exist};
 * 把 z-graph-bolt-server-1.0.1.jar 追加到 -cp 后同一份源码通过。
 * ⇒ **先落 pom 依赖，再合本文件**，否则整个 main-starter 模块编译不过。
 *
 * <p><b>与 z-vector 那份模板的两处刻意差异</b>:
 * <ol>
 *   <li><b>同步 bind，不开启动线程。</b> {@code HttpServer.create()} 在构造函数里就完成 bind，
 *       失败直接抛 {@code IOException}; 放到后台线程只是把"状态什么时候确定"变模糊
 *       (容器 ready 后第一次 {@code /api/graph/__instance} 可能读到还没跑到的中间态)。</li>
 *   <li><b>端口取 {@code server.port()}，不取配置值。</b> {@code GraphControlServer.port()}
 *       返回的是 {@code server.getAddress().getPort()} —— 即**内核实际分配的端口**。
 *       配 {@code zgraph.server.port=0} 时两者不同, 取配置值会把代理指向一个没人监听的端口
 *       (z-vector 那边 L3 的 {@code getPort()} 返回配置值, 是它的一个已知坑)。</li>
 * </ol>
 * bind 失败时 {@code boundPort} 保持 0、并把异常文本留在 {@code bindError} 里,
 * 让 {@link GraphProxyController} 能给出 503 + 原因, 而不是"空数组看起来像没数据"。
 * 这条是 z-cache :6379 那次假孵化的直接教训 (端口被一个几天前的半僵尸 JVM 占着,
 * 内嵌实例因 {@code catch Throwable} 静默跳过, 页面照常打开)。
 *
 * <p><b>安全注意 (须由用户拍板, 这里只如实摊开)</b>: {@code GraphControlServer} 用
 * {@code new InetSocketAddress(port)} bind, 那是**通配地址** {@code *:port}
 * (实测外来实例的 lsof 就是 {@code TCP *:8090 (LISTEN)}), 而它唯一的鉴权是环境变量
 * {@code Z_GRAPH_API_TOKEN} (不设 = 完全不鉴权)。⇒ 一旦 {@code zgraph.enabled=true},
 * z-opc 这台机器就在局域网上开一个可读可写的裸图端口; {@code __instance} 会显示
 * {@code apiTokenConfigured=false} 来提示这件事。z-opc 侧的 {@code /api/graph/**} 反过来是
 * 被 {@code sso.intercept-paths} 覆盖的, 所以"只从 8888 出"这条约束本身仍然成立。
 *
 * <p><b>生命周期</b>: {@code GraphControlServer.start()} 自己注册了 JVM shutdown hook
 * ({@code server.stop(5)} 优雅关闭), 这里不重复注册第二个。
 */
@Configuration
@ConditionalOnProperty(prefix = "z.graph", name = "enabled", havingValue = "true")
public class GraphEmbeddedServerConfig {

    private static final Logger log = LoggerFactory.getLogger(GraphEmbeddedServerConfig.class);

    @Value("${z.graph.server.port:8090}")
    private int port;

    /** 留空 = 纯内存 (进程重启即清空, 与 z-graph 独立进程默认行为一致); 配目录 = 每个 commit 落盘可恢复 */
    @Value("${z.graph.data-dir:}")
    private String dataDir;

    private static final AtomicBoolean SERVER_STARTED = new AtomicBoolean(false);

    private volatile GraphControlServer server;
    private volatile GraphVersionStore store;
    private volatile int boundPort = 0;
    private volatile String bindError = null;

    /**
     * 图引擎本体。z-opc 里没有别的 bean 依赖它, 所以直接 new —— 注意 {@code GraphVersionStore()}
     * 无参会**新建一个空的 "Initial graph" 根提交**, 不会接上任何已有进程的数据:
     * 内嵌实例和外部 :8090 进程是两个各自独立的图, 这一点必须让页面看得见 (见 __instance)。
     */
    @Bean(name = "zGraphVersionStore")
    public GraphVersionStore zGraphVersionStore() {
        GraphVersionStore created;
        if (dataDir == null || dataDir.trim().isEmpty()) {
            log.info("[z-graph] GraphVersionStore = in-memory (zgraph.data-dir 未配置, 重启即清空)");
            created = new GraphVersionStore();
        } else {
            Path dir = Paths.get(dataDir.trim());
            log.info("[z-graph] GraphVersionStore = durable, storageDirectory={}", dir);
            created = new GraphVersionStore(dir);
        }
        this.store = created;
        return created;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed(ContextRefreshedEvent event) {
        if (!SERVER_STARTED.compareAndSet(false, true)) {
            return;
        }
        GraphVersionStore store = event.getApplicationContext().getBean("zGraphVersionStore", GraphVersionStore.class);
        try {
            server = new GraphControlServer(port, store);
            server.start();
            // port() 来自 server.getAddress().getPort() —— 真 bind 出来的端口, 0 表示内核没给到
            boundPort = server.port();
            log.info("[z-graph] GraphControlServer bound on port {} (endpoints: /health /meta/* /query*)", boundPort);
        } catch (Throwable t) {
            boundPort = 0;
            bindError = t.getClass().getSimpleName() + ": " + t.getMessage();
            // 端口被别的进程占着时**不要**再往下走。这里显式记 error 而不是重演 static{} 的静默跳过,
            // 否则页面拿到空数组会被读成"图里没有数据"。
            log.error("[z-graph] GraphControlServer start FAILED on configured port {} —— /api/graph/** 将返回 503."
                    + " 若该端口已有监听者, 那它是**别的进程**, 不是本 JVM; 用 lsof -nP -iTCP:{} -sTCP:LISTEN 确认属主",
                    port, port, t);
        }
    }

    /** 内嵌实例实际 bind 成功的端口; 没 bind 成就是 0 (调用方须据此显式失败, 不许猜端口) */
    public int getBoundPort() {
        return boundPort;
    }

    /** 配置里写的那个端口, 用于把"配置说 8090"和"实际 bind 上没"分开显示 */
    public int getConfiguredPort() {
        return port;
    }

    /** bind 失败的原文; null = 没有失败记录 (不代表成功, 要看 boundPort) */
    public String getBindError() {
        return bindError;
    }

    /**
     * 此刻还能不能连上 —— bind 成功过但后来被关掉/进程换过, 只有这一层测得出来。
     * 注意它测的是"配到的那个端口上有人应答", 不等于"应答的是我们":
     * 所以 {@code boundPort==0 && isAcceptingConnections()==true} 这一组合就是
     * "有外来进程在冒充我们的上游"的判定式, 由 {@link GraphProxyController} 摊到 __instance 上。
     */
    public boolean isAcceptingConnections() {
        int p = boundPort;
        if (p <= 0) {
            return false;
        }
        return tcpReachable(p);
    }

    /** 配置端口上此刻是否有**任意**监听者 (用于区分"没人"和"是别人") */
    public boolean isConfiguredPortAnswering() {
        return tcpReachable(port);
    }

    public boolean isRunning() {
        return boundPort > 0;
    }

    /** 本 JVM 内嵌实例自己那份图存储 —— 与外部 :8090 进程是两个互不相通的图 */
    public GraphVersionStore getStore() {
        return store;
    }

    public GraphControlServer getServer() {
        return server;
    }

    private static boolean tcpReachable(int p) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", p), 300);
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }
}
