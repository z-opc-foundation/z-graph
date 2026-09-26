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

import java.io.IOException;
import java.nio.file.Path;

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
 */
@AutoConfiguration
@ConditionalOnClass({GraphVersionStore.class, GraphControlServer.class})
@ConditionalOnProperty(prefix = "z.graph", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ZGraphProperties.class)
public class ZGraphAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ZGraphAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public GraphVersionStore graphVersionStore(ZGraphProperties props) {
        String dataDir = props.getDataDir();
        GraphVersionStore store = dataDir == null || dataDir.isBlank()
                ? new GraphVersionStore()
                : new GraphVersionStore(Path.of(dataDir));
        store.withCheckpointInterval(props.getCheckpointInterval())
                .withMaxRetainedViews(props.getMaxRetainedViews())
                .withRetainedWholeGraphViews(props.getRetainedWholeGraphViews())
                .withViewLayerLimit(props.getViewLayerLimit());
        log.info("z-graph 版本仓库就绪: dataDir={}, checkpointInterval={}, maxRetainedViews={}, "
                        + "retainedWholeGraphViews={}, viewLayerLimit={}",
                dataDir == null || dataDir.isBlank() ? "(in-memory)" : dataDir,
                props.getCheckpointInterval(), props.getMaxRetainedViews(),
                props.getRetainedWholeGraphViews(), props.getViewLayerLimit());
        return store;
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean
    public GraphControlServer graphControlServer(GraphVersionStore store, ZGraphProperties props)
            throws IOException {
        GraphControlServer server = new GraphControlServer(props.getPort(), store);
        server.start();
        log.info("z-graph HTTP 控制面已 bind: 配置端口={}, 实际端口={} (未设 Z_GRAPH_API_TOKEN 即不鉴权)",
                props.getPort(), server.port());
        return server;
    }
}
