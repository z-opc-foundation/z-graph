package com.zifang.z.graph.bolt;

import com.zifang.z.graph.core.GraphVersionStore;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制面暴露面守卫（TASK-20261022-004）。
 *
 * <p>三件事必须同时成立，缺一条就是老形态复活：
 * <ol>
 *   <li><b>缺 token ⇒ 拒绝启动</b>：构造即抛，且**端口一个都没被占**（不能变成
 *       "端口占了但服务没起来"）。</li>
 *   <li><b>默认 bind 回环</b>：内核实际绑定的地址不是通配（看 {@code boundAddress()}，
 *       不是看配置值 —— 端口传 0 时两者根本不是一回事）。</li>
 *   <li><b>token 真的在拦</b>：带对 token 的写接口 200，不带/带错的 401。</li>
 * </ol>
 */
class GraphControlServerSecurityTest {

    private static final String TOKEN = "security-test-token";

    @Test
    void refusesToStartWithoutApiTokenAndDoesNotEvenBindThePort() throws Exception {
        GraphVersionStore repository = new GraphVersionStore();
        int freePort = pickFreePort();

        // null token
        IllegalStateException nullToken = assertThrows(GraphControlServer.MissingApiTokenException.class,
                () -> new GraphControlServer(freePort, repository, null),
                "缺 token 竟然起来了 —— 这就是「裸奔」的回归");
        assertTrue(nullToken.getMessage().contains("Z_GRAPH_API_TOKEN"), nullToken.getMessage());

        // 空白 token 与 null 等价（不能被 trim 绕过）
        assertThrows(GraphControlServer.MissingApiTokenException.class,
                () -> new GraphControlServer(freePort, repository, "   "),
                "空白 token 竟然起来了");

        // 关键：失败之后端口必须还是空的 —— 拒绝启动不能变成"占了端口却没服务"
        assertFalse(stillListening(freePort),
                "缺 token 失败后 " + freePort + " 上仍有监听者 ⇒ 端口被白白占了");
    }

    @Test
    void bindsLoopbackByDefaultInsteadOfTheWildcardAddress() throws Exception {
        GraphVersionStore repository = new GraphVersionStore();
        GraphControlServer server = new GraphControlServer(0, repository, TOKEN);
        server.start();
        try {
            InetSocketAddress bound = server.boundAddress();
            assertNotNull(bound);
            assertEquals("127.0.0.1", bound.getAddress().getHostAddress(),
                    "默认 bind 的不是回环，而是 " + bound.getAddress().getHostAddress());
            assertTrue(bound.getAddress().isLoopbackAddress(), "bind 地址不是回环：" + bound);
            assertEquals("127.0.0.1", server.bindHost());
        } finally {
            server.stop();
        }
    }

    @Test
    void explicitHostIsHonouredSoContainersCanOptIn() throws Exception {
        GraphVersionStore repository = new GraphVersionStore();
        GraphControlServer server = new GraphControlServer("0.0.0.0", 0, repository, TOKEN);
        server.start();
        try {
            assertEquals("0.0.0.0", server.bindHost());
            assertTrue(server.boundAddress().getAddress().isAnyLocalAddress(),
                    "显式给 0.0.0.0 时 bind 地址应当是通配，实际=" + server.boundAddress());
        } finally {
            server.stop();
        }
    }

    @Test
    void tokenIsEnforcedOnWriteEndpointsWhileHealthStaysOpen() throws Exception {
        GraphVersionStore repository = new GraphVersionStore();
        GraphControlServer server = new GraphControlServer(0, repository, TOKEN);
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.port();
            String cypher = URLEncoder.encode("MATCH (n) RETURN n", "UTF-8");

            // /health 豁免（容器健康检查要能打）
            assertEquals(200, get(base + "/health", null).statusCode());

            // 读接口也要 token
            assertEquals(401, get(base + "/meta/branches", null).statusCode());
            assertEquals(401, get(base + "/meta/branches", "wrong-token").statusCode());

            // 带对 token ⇒ 放行
            assertEquals(200, get(base + "/meta/branches", TOKEN).statusCode());
            assertEquals(200, get(base + "/query?cypher=" + cypher, TOKEN).statusCode());
        } finally {
            server.stop();
        }
    }

    private static Response get(String url, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod("GET");
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(status, stream == null ? "" : readFully(stream));
        } finally {
            connection.disconnect();
        }
    }

    private static String readFully(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        stream.close();
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int pickFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean stillListening(int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static final class Response {
        private final int statusCode;
        private final String body;

        private Response(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        int statusCode() { return statusCode; }

        String body() { return body; }
    }
}
