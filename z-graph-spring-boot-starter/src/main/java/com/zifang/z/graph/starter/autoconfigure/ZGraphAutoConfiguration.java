package com.zifang.z.graph.starter.autoconfigure;

import com.zifang.z.graph.bolt.GraphControlServer;
import com.zifang.z.graph.core.GraphVersionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Paths;

/**
 * z-graph 的 Spring Boot 自动装配：一个 {@link GraphVersionStore} + 内嵌 HTTP 控制面
 * {@link GraphControlServer}，由 {@code z.graph.enabled=true} 打开。
 *
 * <p>控制面用的是 {@code com.sun.net.httpserver.HttpServer}（不是 Spring MVC），
 * bind 就发生在构造函数里，所以这里同步构造：端口被占了就直接让容器启动失败，
 * 而不是丢个后台线程把"什么时候算起来"变成模糊状态。</p>
 *
 * <p>实际监听端口一律以 {@link GraphControlServer#port()} 为准。配 {@code port: 0} 时
 * 内核分配的那个端口只有这个 getter 知道，读配置值会把调用方指向一个没人监听的端口。</p>
 *
 * <p><b>fail-closed（TASK-20261022-004）</b>：API Token 缺失时这个 Bean 直接创建失败 ⇒
 * 容器启动失败、退出码非 0 —— 不会再出现"控制面起来了但不鉴权"。
 * token 取自属性 {@code z.graph.api-token} 或环境变量 {@code Z_GRAPH_API_TOKEN}（名字未变）。</p>
 */
@AutoConfiguration
@ConditionalOnClass({GraphVersionStore.class, GraphControlServer.class})
@ConditionalOnProperty(prefix = "z.graph", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ZGraphProperties.class)
public class ZGraphAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ZGraphAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public GraphVersionStore graphVersionStore(Environment env) {
        // 规范键 z.base.graph.data-dir > 兼容别名 z.graph.data-dir > 环境变量 > 空。
        // 兼容别名生效时行为与改动前逐字一致（只有规范键是新增的、且优先级更高）。
        String dataDir = ZGraphProperties.resolveDataDir(env);
        GraphVersionStore store = dataDir == null
                ? new GraphVersionStore()
                : new GraphVersionStore(Paths.get(dataDir));
        log.info("z-graph 版本仓库就绪: dataDir={}", dataDir == null ? "(ephemeral)" : dataDir);
        // 留空 ⇒ 落进程临时目录 ⇒ 非静默 profile 必须 WARN（文案含配置指引）。
        String warning = ZGraphProperties.dataDirWarning(env);
        if (warning != null) {
            log.warn(warning);
        }
        return store;
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean
    public GraphControlServer graphControlServer(GraphVersionStore store, ZGraphProperties props)
            throws IOException {
        String token = resolveApiToken(props);
        // 缺 token ⇒ GraphControlServer 构造里就抛（fail-closed）⇒ 容器启动失败、退出码非 0。
        // 这里是**故意不 catch**：控制面有写接口，"先起来再说"等于裸奔。
        GraphControlServer server = new GraphControlServer(props.getHost(), props.getPort(), store, token);
        server.start();
        log.info("z-graph HTTP 控制面已 bind: bind 地址={}, 配置端口={}, 实际端口={} (API Token 已配置; 未设即拒绝启动)",
                server.bindHost(), props.getPort(), server.port());
        return server;
    }

    /**
     * API Token 取值顺序：Spring 属性 {@code z.graph.api-token} → 环境变量 {@code Z_GRAPH_API_TOKEN}。
     *
     * <p>环境变量名**没有改**（既有的部署只注入它，改名等于打断线上配置）；
     * 属性名 {@code z.graph.api-token} 正好是它的宽松绑定形式，所以两个来源等价。</p>
     */
    private static String resolveApiToken(ZGraphProperties props) {
        String configured = props.getApiToken();
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        String env = System.getenv("Z_GRAPH_API_TOKEN");
        return env == null || env.trim().isEmpty() ? null : env.trim();
    }
}
