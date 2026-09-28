package com.zifang.z.graph.starter.autoconfigure;

import com.zifang.z.graph.api.Colls;
import com.zifang.z.graph.bolt.GraphControlServer;
import com.zifang.z.graph.core.GraphVersionStore;
import com.zifang.z.graph.core.GraphWriteTransaction;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前面那组测试用 {@code AutoConfigurations.of(...)} 显式注册配置类，等于绕开了
 * {@code AutoConfiguration.imports}——文件写错路径或写错类名，它们照样全绿，
 * 而使用方引了 starter 却什么也没有。这一支只走"裸 Boot 应用 + 只有这个 jar 在 classpath"那条路。
 */
class ZGraphStarterBootstrapTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class PlainApp {
    }

    @Test
    void plainBootApplicationPicksThePlaneUpWithZeroRegistration() throws Exception {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(PlainApp.class)
                .web(WebApplicationType.NONE)
                .properties("z.graph.enabled=true", "z.graph.port=0")
                .run()) {
            GraphVersionStore store = ctx.getBean(GraphVersionStore.class);
            GraphControlServer server = ctx.getBean(GraphControlServer.class);
            assertNotNull(store);
            int port = server.port();
            assertTrue(port > 0, "自动装配没 bind 出端口");

            Response health = get("http://127.0.0.1:" + port + "/health");
            assertEquals(200, health.statusCode(), health.body());
            assertTrue(health.body().contains("\"UP\""), health.body());

            // commit 视图这条链在装配出来的仓库上真的走得通
            GraphWriteTransaction tx = store.beginWrite("main");
            tx.addNode("Person", Colls.mapOf("name", "Booted Alice"));
            tx.commit("t", "boot");
            Response query = get("http://127.0.0.1:" + port
                    + "/query?cypher=" + URLEncoder.encode(
                    "MATCH (n:Person) RETURN n.name AS name", "UTF-8"));
            assertEquals(200, query.statusCode(), query.body());
            assertTrue(query.body().contains("Booted Alice"), query.body());
        }
    }

    @Test
    void importsFileIsWhereSpringLooksAndNamesThatClass() throws Exception {
        URL url = ZGraphAutoConfiguration.class.getClassLoader()
                .getResource("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        assertNotNull(url, "imports 不在 classpath 上 ⇒ 使用方扫不到自动配置");
        try (InputStream in = url.openStream()) {
            String body = readBody(in).trim();
            assertEquals(ZGraphAutoConfiguration.class.getName(), body,
                    "imports 里写的类名和被装配的类对不上");
        }
        // 属性类必须挂 @ConfigurationProperties，否则 z.graph.* 十个字段一个都不会被绑上
        assertTrue(ZGraphProperties.class.isAnnotationPresent(ConfigurationProperties.class));
    }

    /** Java 8 没有 java.net.http，用 HttpURLConnection 做同样的 GET。 */
    private static Response get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        try {
            int status = connection.getResponseCode();
            // 4xx/5xx 的正文在 errorStream 里，断言消息要用到它
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(status, stream == null ? "" : readBody(stream));
        } finally {
            connection.disconnect();
        }
    }

    private static String readBody(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        try {
            while ((read = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
        } finally {
            stream.close();
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
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
