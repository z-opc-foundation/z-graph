package com.zifang.z.graph.bolt;

import com.zifang.z.graph.core.GraphVersionStore;

import java.nio.file.Paths;

/**
 * 独立启动 Meta/Query HTTP 控制面，默认监听 8090。
 *
 * <p><b>两条硬约束（TASK-20261022-004 收口）</b>：
 * <ul>
 *   <li>bind 地址默认**回环**（{@code 127.0.0.1}）。控制面有写接口
 *       （{@code /query} 支持 CREATE/MERGE/DELETE，{@code /meta/import} 是写接口），
 *       通配地址等于把一张可读可写的图挂在局域网上。</li>
 *   <li>环境变量 {@code Z_GRAPH_API_TOKEN} **必填**：缺了就抛异常、进程退出码非 0，
 *       而不是"照起 + 不鉴权"。</li>
 * </ul>
 *
 * <p>要对外暴露时用系统属性 {@code -Dz.graph.bindHost=0.0.0.0}（不引入新环境变量名）。</p>
 */
public final class GraphControlServerMain {

    private GraphControlServerMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8090;
        String dataDirectory = System.getProperty("z.graph.dataDir");
        GraphVersionStore repository = dataDirectory == null
                ? new GraphVersionStore()
                : new GraphVersionStore(Paths.get(dataDirectory));
        // 构造里就做 token 的 fail-closed 判定与 bind：缺 token ⇒ 抛异常、退出码非 0、端口没被占。
        GraphControlServer server = new GraphControlServer(resolveBindHost(), port, repository);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        Thread.currentThread().join();
    }

    /** bind 地址：只认系统属性 {@code z.graph.bindHost}，默认回环。 */
    private static String resolveBindHost() {
        String prop = System.getProperty("z.graph.bindHost");
        if (prop == null || prop.trim().isEmpty()) {
            return GraphControlServer.DEFAULT_BIND_HOST;
        }
        return prop.trim();
    }
}
