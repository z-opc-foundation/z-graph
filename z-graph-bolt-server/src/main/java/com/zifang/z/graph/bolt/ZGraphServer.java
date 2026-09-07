package com.zifang.z.graph.bolt;

import com.zifang.z.graph.core.GraphVersionStore;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * 统一启动入口:同时拉起 Bolt 4.4 协议与 HTTP 控制面,供 Docker all-in-one
 * 镜像或单机开发使用。生产分布式部署建议分别独立部署两个服务。
 *
 * <p>环境变量/系统属性:</p>
 * <ul>
 *     <li>{@code Z_GRAPH_BOLT_PORT} / {@code z.graph.boltPort} (默认 7687)</li>
 *     <li>{@code Z_GRAPH_HTTP_PORT} / {@code z.graph.httpPort} (默认 8090)</li>
 *     <li>{@code Z_GRAPH_DATA_DIR} / {@code z.graph.dataDir} (可选,仓库持久化目录)</li>
 *     <li>{@code Z_GRAPH_CORS_ALLOWED_ORIGINS} (逗号分隔 origin 列表)</li>
 * </ul>
 */
public final class ZGraphServer {

    private ZGraphServer() {
    }

    public static void main(String[] args) throws Exception {
        int boltPort = resolvePort(args.length > 0 ? args[0] : null,
                "Z_GRAPH_BOLT_PORT", "z.graph.boltPort", 7687);
        int httpPort = resolvePort(args.length > 1 ? args[1] : null,
                "Z_GRAPH_HTTP_PORT", "z.graph.httpPort", 8090);
        String dataDirectory = System.getenv().getOrDefault("Z_GRAPH_DATA_DIR",
                System.getProperty("z.graph.dataDir"));

        GraphVersionStore repository = dataDirectory == null
                ? new GraphVersionStore()
                : new GraphVersionStore(Path.of(dataDirectory));

        BoltServer bolt = new BoltServer(boltPort, repository);
        bolt.start();
        GraphControlServer control = new GraphControlServer(httpPort, repository);
        control.start();
        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { bolt.shutdown(); } catch (Exception ignored) { }
            try { control.stop(); } catch (Exception ignored) { }
            latch.countDown();
        }));
        System.out.printf("z-graph ready: bolt=%d http=%d dataDir=%s%n",
                bolt.port(), control.port(), dataDirectory == null ? "<memory>" : dataDirectory);

        latch.await();
    }

    private static int resolvePort(String arg, String envKey, String propKey, int defaultPort) {
        if (arg != null && !arg.isBlank()) return Integer.parseInt(arg);
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) return Integer.parseInt(envValue);
        String propValue = System.getProperty(propKey);
        if (propValue != null && !propValue.isBlank()) return Integer.parseInt(propValue);
        return defaultPort;
    }
}
