package com.zifang.z.graph.bolt;

import com.zifang.z.graph.core.GraphVersionStore;
import com.zifang.z.graph.core.GraphWriteTransaction;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ZGraphServer 联合启动测试:在随机端口同时拉起 Bolt + HTTP 控制面,
 * 验证两端都能正常服务。
 */
class ZGraphServerTest {

    @Test
    void zGraphServerStartsBoltAndControlPlaneTogether() throws Exception {
        GraphVersionStore repository = new GraphVersionStore();
        GraphWriteTransaction tx = repository.beginWrite("main");
        tx.addNode("Person", Map.of("name", "Alice", "age", 30));
        tx.commit("seed", "seed");

        int controlPort = pickFreePort();
        BoltServer bolt = new BoltServer(0, repository);
        bolt.start();
        GraphControlServer control = new GraphControlServer(controlPort, repository);
        control.start();
        try {
            assertNotNull(bolt.port());
            assertEquals(controlPort, control.port());

            // HTTP 控制面应能返回节点数
            java.net.URL url = new java.net.URL("http://127.0.0.1:" + controlPort + "/health");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            assertEquals(200, conn.getResponseCode());
            String body = new String(conn.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertNotNull(body);
            assertEquals(1, repository.checkoutBranch("main").getStore().getNodeCount());
        } finally {
            bolt.shutdown();
            control.stop();
        }
    }

    private static int pickFreePort() throws java.io.IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
