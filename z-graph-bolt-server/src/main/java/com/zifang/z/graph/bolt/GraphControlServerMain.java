package com.zifang.z.graph.bolt;

import com.zifang.z.graph.core.GraphVersionStore;

import java.nio.file.Path;

/** 独立启动 Meta/Query HTTP 控制面，默认监听 8090。 */
public final class GraphControlServerMain {

    private GraphControlServerMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8090;
        String dataDirectory = System.getProperty("z.graph.dataDir");
        GraphVersionStore repository = dataDirectory == null
                ? new GraphVersionStore()
                : new GraphVersionStore(Path.of(dataDirectory));
        GraphControlServer server = new GraphControlServer(port, repository);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        Thread.currentThread().join();
    }
}
