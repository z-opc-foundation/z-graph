package com.zifang.z.graph.starter.autoconfigure;

import com.zifang.z.graph.bolt.GraphControlServer;
import com.zifang.z.graph.core.GraphVersionStore;
import com.zifang.z.graph.core.GraphWriteTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动装配的判据一律"读回来等于什么"，不看有没有调用过 setter——
 * 属性字段挂着但没人读，是这个 starter 上一版的病。
 */
class ZGraphAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ZGraphAutoConfiguration.class));

    @Test
    void installsNothingUnlessEnabledButReallyInstallsOnceEnabled() {
        runner.withPropertyValues("z.graph.port=0")
                .run(ctx -> assertEquals(0, ctx.getBeanNamesForType(GraphVersionStore.class).length,
                        "没开 z.graph.enabled 就不该装仓库 Bean（更不该 bind 端口）"));

        // 阳性对照：同一个 runner 打开开关必须真有两个 Bean，否则上面那条"没有"只是空跑。
        runner.withPropertyValues("z.graph.enabled=true", "z.graph.port=0")
                .run(ctx -> {
                    assertEquals(1, ctx.getBeanNamesForType(GraphVersionStore.class).length);
                    assertEquals(1, ctx.getBeanNamesForType(GraphControlServer.class).length);
                });
    }

    @Test
    void blankDataDirKeepsTheRepositoryOffDisk(@TempDir Path dir) {
        runner.withPropertyValues("z.graph.enabled=true", "z.graph.port=0")
                .run(ctx -> {
                    GraphVersionStore store = ctx.getBean(GraphVersionStore.class);
                    GraphWriteTransaction tx = store.beginWrite("main");
                    tx.addNode("Person", Map.of("name", "In-memory Alice"));
                    tx.commit("test", "in-memory write");
                    assertEquals(1, entriesUnder(dir), "data-dir 留空却往目录里写了东西");
                });
    }

    @Test
    void reportsThePortActuallyListeningAndServesHealth(@TempDir Path dir) {
        int[] boundPort = new int[1];
        runner.withPropertyValues("z.graph.enabled=true", "z.graph.port=0", "z.graph.data-dir=" + dir)
                .run(ctx -> {
                    GraphControlServer server = ctx.getBean(GraphControlServer.class);
                    int port = server.port();
                    boundPort[0] = port;
                    assertTrue(port > 0, "port=0 时读到 " + port + "，说明拿的是配置值而不是内核分配的端口");

                    HttpResponse<String> health = HttpClient.newHttpClient().send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, health.statusCode(), "按 port() 报的端口连不上，那个读数就是假的");
                    assertTrue(health.body().contains("\"UP\""), health.body());

                    GraphVersionStore store = ctx.getBean(GraphVersionStore.class);
                    GraphWriteTransaction tx = store.beginWrite("main");
                    tx.addNode("Person", Map.of("name", "Starter Alice"));
                    tx.commit("test", "starter write");
                    assertTrue(Files.isDirectory(dir.resolve("objects")),
                            "data-dir 配了却没落盘，说明这个属性没人读");
                });

        assertFalse(stillListening(boundPort[0]),
                "上下文关闭后 " + boundPort[0] + " 仍在监听 ⇒ destroyMethod=stop 没管住生命周期");
    }

    @Test
    void mvccKnobsComeFromPropertiesAndDefaultToLibraryConstants() {
        runner.withPropertyValues("z.graph.enabled=true", "z.graph.port=0")
                .run(ctx -> {
                    Map<String, Object> stats = ctx.getBean(GraphVersionStore.class).versionStats();
                    assertEquals(GraphVersionStore.DEFAULT_CHECKPOINT_INTERVAL,
                            number(stats, "checkpointInterval"), "starter 自己抄了一份默认值，没走库里的常量");
                    assertEquals(GraphVersionStore.DEFAULT_MAX_RETAINED_VIEWS,
                            number(stats, "maxRetainedViews"));
                    assertEquals(GraphVersionStore.DEFAULT_MAX_RETAINED_ENTITIES,
                            number(stats, "maxRetainedEntities"));
                    assertEquals(GraphVersionStore.DEFAULT_VIEW_LAYER_LIMIT,
                            number(stats, "viewLayerLimit"));
                });

        runner.withPropertyValues("z.graph.enabled=true", "z.graph.port=0",
                        "z.graph.checkpoint-interval=3",
                        "z.graph.max-retained-views=5",
                        "z.graph.max-retained-entities=777",
                        "z.graph.view-layer-limit=9")
                .run(ctx -> {
                    Map<String, Object> stats = ctx.getBean(GraphVersionStore.class).versionStats();
                    assertEquals(3, number(stats, "checkpointInterval"));
                    assertEquals(5, number(stats, "maxRetainedViews"));
                    assertEquals(777, number(stats, "maxRetainedEntities"));
                    assertEquals(9, number(stats, "viewLayerLimit"));
                });
    }

    private static long number(Map<String, Object> stats, String key) {
        Object raw = stats.get(key);
        assertTrue(raw instanceof Number, key + " 读回来是 " + raw);
        return ((Number) raw).longValue();
    }

    private static long entriesUnder(Path dir) {
        try (var walk = Files.walk(dir)) {
            return walk.count(); // 目录本身没被写过时就是 1
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static boolean stillListening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
