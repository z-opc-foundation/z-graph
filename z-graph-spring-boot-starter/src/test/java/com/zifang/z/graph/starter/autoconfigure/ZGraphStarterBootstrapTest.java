package com.zifang.z.graph.starter.autoconfigure;

import com.zifang.z.graph.bolt.GraphControlServer;
import com.zifang.z.graph.core.GraphVersionStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
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

            HttpResponse<String> health = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode(), health.body());
            assertTrue(health.body().contains("\"UP\""), health.body());

            // commit 视图这条链在装配出来的仓库上真的走得通
            var tx = store.beginWrite("main");
            tx.addNode("Person", java.util.Map.of("name", "Booted Alice"));
            tx.commit("t", "boot");
            HttpResponse<String> query = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                            + "/query?cypher=" + java.net.URLEncoder.encode(
                            "MATCH (n:Person) RETURN n.name AS name", StandardCharsets.UTF_8)))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, query.statusCode(), query.body());
            assertTrue(query.body().contains("Booted Alice"), query.body());
        }
    }

    @Test
    void importsFileIsWhereSpringLooksAndNamesThatClass() throws Exception {
        var url = ZGraphAutoConfiguration.class.getClassLoader()
                .getResource("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        assertNotNull(url, "imports 不在 classpath 上 ⇒ 使用方扫不到自动配置");
        try (InputStream in = url.openStream()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            assertEquals(ZGraphAutoConfiguration.class.getName(), body,
                    "imports 里写的类名和被装配的类对不上");
        }
        // 属性类必须挂 @ConfigurationProperties，否则 z.graph.* 十个字段一个都不会被绑上
        assertTrue(ZGraphProperties.class.isAnnotationPresent(ConfigurationProperties.class));
    }
}
