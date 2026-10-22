package com.zifang.z.graph.bolt;

import com.zifang.z.graph.core.GraphVersionStore;

import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;

/**
 * 统一启动入口:同时拉起 Bolt 4.4 协议与 HTTP 控制面,供 Docker all-in-one
 * 镜像或单机开发使用。生产分布式部署建议分别独立部署两个服务。
 *
 * <p>环境变量/系统属性:</p>
 * <ul>
 *     <li>{@code Z_GRAPH_BOLT_PORT} / {@code z.graph.boltPort} (默认 7687)</li>
 *     <li>{@code Z_GRAPH_HTTP_PORT} / {@code z.graph.httpPort} (默认 8090)</li>
 *     <li>{@code Z_GRAPH_API_TOKEN} (**必填**,缺了拒绝启动 —— 控制面有写接口)</li>
 *     <li>{@code z.graph.bindHost} (控制面 bind 地址,默认回环 127.0.0.1;
 *         容器里要对外暴露时传 {@code -Dz.graph.bindHost=0.0.0.0}。
 *         只认系统属性、**不设环境变量**,免得多一个新 env 名)</li>
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
                : new GraphVersionStore(Paths.get(dataDirectory));

        // 控制面**先构造**：构造里就做 token 的 fail-closed 判定与 bind，
        // 所以缺 token 时这里就抛，进程退出码非 0，且一个端口都没占 ——
        // 反过来（先起 Bolt 再构造控制面）会留下"半个服务起来"的现场。
        GraphControlServer control = new GraphControlServer(
                resolveBindHost(), httpPort, repository);
        BoltServer bolt = new BoltServer(boltPort, repository);
        bolt.start();
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
        if (arg != null && !arg.trim().isEmpty()) return Integer.parseInt(arg);
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.trim().isEmpty()) return Integer.parseInt(envValue);
        String propValue = System.getProperty(propKey);
        if (propValue != null && !propValue.trim().isEmpty()) return Integer.parseInt(propValue);
        return defaultPort;
    }

    /**
     * 控制面 bind 地址：只认系统属性 {@code z.graph.bindHost}，默认回环。
     *
     * <p>为什么不也读环境变量：这张卡要求"不引入新 env 名"（{@code Z_GRAPH_API_TOKEN} 不能改名，
     * 也不该再多一个）。系统属性够用 —— 容器里用 {@code -D} 或 {@code JAVA_TOOL_OPTIONS} 传即可。</p>
     */
    private static String resolveBindHost() {
        String prop = System.getProperty("z.graph.bindHost");
        if (prop == null || prop.trim().isEmpty()) {
            return GraphControlServer.DEFAULT_BIND_HOST;
        }
        return prop.trim();
    }
}
