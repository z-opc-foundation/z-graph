package com.zifang.z.graph.starter.autoconfigure;

import com.zifang.z.graph.core.GraphVersionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收：data-dir 留空 ⇒ 非静默 profile 启动 WARN（含配置指引）；dev 不吵；显式配置不吵。
 *
 * <p>三条判据的分工：
 * <ul>
 *   <li><b>日志级</b>：{@link RecordingSlf4jProvider} 接管本模块测试进程的日志，
 *       断言「真收到了 WARN」且文案含 {@code z.base.graph.data-dir}（配置指引）；</li>
 *   <li><b>决策级</b>：{@link ZGraphProperties#dataDirWarning(org.springframework.core.env.Environment)}
 *       纯函数直接断言「该吵/不该吵」，顺带覆盖优先级与静默集合；</li>
 *   <li><b>落盘级</b>：解析出来的目录确实被 {@link GraphVersionStore} 用上。</li>
 * </ul>
 *
 * <p><b>反向钉</b>（防「报绿只是根本没查」）：显式配置目录 ⇒ 必须**没有** WARN；
 * dev/local profile ⇒ 必须**没有** WARN；规范键与兼容别名同时给出时规范键胜出。</p>
 */
class ZGraphDataDirWarnTest {

    /** 控制面 token 必填（fail-closed）：测试必须显式给一个。 */
    private static final String TOKEN = "starter-test-token";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ZGraphAutoConfiguration.class));

    @BeforeEach
    void resetRecordedWarnings() {
        RecordingSlf4jProvider.reset();
    }

    // ==================== 验收 1：留空 + 非静默 profile ⇒ WARN ====================

    @Test
    void blankDataDirOnNonQuietProfileWarnsWithConfigurationGuidance() {
        runner.withPropertyValues("z.graph.enabled=true", "z.graph.port=0", "z.graph.api-token=" + TOKEN,
                        "spring.profiles.active=prod")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), String.valueOf(ctx.getStartupFailure()));
                    assertEquals(1, RecordingSlf4jProvider.WARNINGS.size(),
                            "留空 + prod profile 应当恰好一条 WARN，实际：" + RecordingSlf4jProvider.WARNINGS);
                    String warning = RecordingSlf4jProvider.WARNINGS.get(0);
                    assertTrue(warning.contains("z.base.graph.data-dir"),
                            "WARN 文案必须写明规范键怎么配：" + warning);
                    assertTrue(warning.contains("Z_BASE_GRAPH_DATA_DIR"),
                            "WARN 文案必须写明环境变量怎么配：" + warning);
                    assertTrue(warning.contains("临时目录"),
                            "WARN 文案必须说清数据落在临时目录：" + warning);
                    assertTrue(warning.contains("进程退出"),
                            "WARN 文案必须说清数据随进程退出丢弃：" + warning);
                });
    }

    /** 没有 profile 时环境里的活动 profile 是 default —— 同样算「非静默」，必须吵。 */
    @Test
    void blankDataDirWithoutProfileWarnsToo() {
        runner.withPropertyValues("z.graph.enabled=true", "z.graph.port=0", "z.graph.api-token=" + TOKEN)
                .run(ctx -> assertEquals(1, RecordingSlf4jProvider.WARNINGS.size(),
                        "没设 profile（default）也属于非静默，必须 WARN：" + RecordingSlf4jProvider.WARNINGS));
    }

    // ==================== 验收 3：dev（及本地口径）profile ⇒ 不吵 ====================

    @Test
    void blankDataDirOnDevProfileStaysQuiet() {
        runner.withPropertyValues("z.graph.enabled=true", "z.graph.port=0", "z.graph.api-token=" + TOKEN,
                        "spring.profiles.active=dev")
                .run(ctx -> assertTrue(RecordingSlf4jProvider.WARNINGS.isEmpty(),
                        "dev profile 下不该有 WARN（临时目录是开发口径的预期行为）："
                                + RecordingSlf4jProvider.WARNINGS));
    }

    /**
     * 组织实际的 profile 名是 local / prod / localhost，**没有 dev**。
     * 口径：dev / local / localhost 都算「开发口径」，默认静默；prod 与其它名字吵。
     */
    @Test
    void blankDataDirOnLocalProfilesStaysQuiet() {
        for (String profile : new String[]{"local", "localhost"}) {
            RecordingSlf4jProvider.reset();
            runner.withPropertyValues("z.graph.enabled=true", "z.graph.port=0", "z.graph.api-token=" + TOKEN,
                            "spring.profiles.active=" + profile)
                    .run(ctx -> assertTrue(RecordingSlf4jProvider.WARNINGS.isEmpty(),
                            profile + " profile 下不该有 WARN：" + RecordingSlf4jProvider.WARNINGS));
        }
    }

    // ==================== 反向钉：显式配置 ⇒ 不吵 ====================

    @Test
    void explicitDataDirDoesNotWarn() {
        runner.withPropertyValues("z.graph.enabled=true", "z.graph.port=0", "z.graph.api-token=" + TOKEN,
                        "spring.profiles.active=prod", "z.base.graph.data-dir=/tmp/zgraph-explicit")
                .run(ctx -> assertTrue(RecordingSlf4jProvider.WARNINGS.isEmpty(),
                        "显式配置了目录就不该 WARN：" + RecordingSlf4jProvider.WARNINGS));
    }

    /** 兼容别名显式配置 ⇒ 同样不吵（兼容路径的行为零变化）。 */
    @Test
    void explicitCompatAliasDoesNotWarn() {
        runner.withPropertyValues("z.graph.enabled=true", "z.graph.port=0", "z.graph.api-token=" + TOKEN,
                        "spring.profiles.active=prod", "z.graph.data-dir=/tmp/zgraph-compat")
                .run(ctx -> assertTrue(RecordingSlf4jProvider.WARNINGS.isEmpty(),
                        "兼容别名显式配置也不该 WARN：" + RecordingSlf4jProvider.WARNINGS));
    }

    // ==================== 决策级：解析优先级 ====================

    @Test
    void canonicalKeyWinsOverCompatAlias() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("z.base.graph.data-dir", "/tmp/canonical")
                .withProperty("z.graph.data-dir", "/tmp/compat");
        assertEquals("/tmp/canonical", ZGraphProperties.resolveDataDir(env));
    }

    @Test
    void compatAliasStillResolvesWhenCanonicalAbsent() {
        MockEnvironment env = new MockEnvironment().withProperty("z.graph.data-dir", "/tmp/compat");
        assertEquals("/tmp/compat", ZGraphProperties.resolveDataDir(env));
    }

    @Test
    void blankValuesCountAsNotConfigured() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("z.base.graph.data-dir", "   ")
                .withProperty("z.graph.data-dir", "");
        assertNull(ZGraphProperties.resolveDataDir(env), "空白值一律按「没配」处理");
    }

    /** 静默集合可配：把 prod 也加进静默集合 ⇒ prod 不吵。 */
    @Test
    void quietProfilesAreConfigurable() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("z.base.graph.quiet-profiles", "dev,local,localhost,prod")
                .withProperty("spring.profiles.active", "prod");
        assertTrue(ZGraphProperties.isQuietProfile(env));
        assertNull(ZGraphProperties.dataDirWarning(env));
    }

    /** 决策级双向断言：非静默 ⇒ 有文案且含配置指引；静默 ⇒ 无文案。 */
    @Test
    void warningDecisionIsPureFunction() {
        MockEnvironment loud = new MockEnvironment().withProperty("spring.profiles.active", "prod");
        String warning = ZGraphProperties.dataDirWarning(loud);
        assertNotNull(warning);
        assertTrue(warning.contains("z.base.graph.data-dir"), warning);
        assertTrue(warning.contains("Z_BASE_GRAPH_DATA_DIR"), warning);

        MockEnvironment quiet = new MockEnvironment().withProperty("spring.profiles.active", "dev");
        assertNull(ZGraphProperties.dataDirWarning(quiet));

        MockEnvironment configured = new MockEnvironment()
                .withProperty("spring.profiles.active", "prod")
                .withProperty("z.base.graph.data-dir", "/tmp/zgraph");
        assertNull(ZGraphProperties.dataDirWarning(configured), "显式配置了就不该有文案");
    }

    // ==================== 落盘级：解析出的目录真的被用上 ====================

    @Test
    void resolvedDirectoryIsActuallyUsedByTheStore(@org.junit.jupiter.api.io.TempDir Path dir) {
        runner.withPropertyValues("z.graph.enabled=true", "z.graph.port=0", "z.graph.api-token=" + TOKEN,
                        "spring.profiles.active=prod", "z.base.graph.data-dir=" + dir)
                .run(ctx -> {
                    GraphVersionStore store = ctx.getBean(GraphVersionStore.class);
                    assertEquals(dir.toAbsolutePath(), store.getStorageDirectory().toAbsolutePath(),
                            "规范键解析出来的目录必须真的被版本仓库用上");
                    assertTrue(java.nio.file.Files.isDirectory(dir.resolve("store")),
                            "落盘目录结构不对：" + dir);
                });
    }

    /** 兼容别名路径行为零变化：显式给的目录照样被用上。 */
    @Test
    void compatAliasDirectoryIsActuallyUsedByTheStore(@org.junit.jupiter.api.io.TempDir Path dir) {
        runner.withPropertyValues("z.graph.enabled=true", "z.graph.port=0", "z.graph.api-token=" + TOKEN,
                        "spring.profiles.active=prod", "z.graph.data-dir=" + dir)
                .run(ctx -> assertEquals(dir.toAbsolutePath(),
                        ctx.getBean(GraphVersionStore.class).getStorageDirectory().toAbsolutePath(),
                        "兼容别名给的目录必须真的被版本仓库用上"));
    }

    /** starter 关掉时压根不装 Bean ⇒ 不吵（口径对齐：enabled=false 不装 Bean 就没启动日志可吵）。 */
    @Test
    void disabledStarterInstallsNothingAndDoesNotWarn() {
        runner.withPropertyValues("z.graph.port=0", "spring.profiles.active=prod")
                .run(ctx -> {
                    assertEquals(0, ctx.getBeanNamesForType(GraphVersionStore.class).length);
                    assertFalse(RecordingSlf4jProvider.WARNINGS.iterator().hasNext(),
                            "没开开关就不该有 Bean，也就不该有 WARN：" + RecordingSlf4jProvider.WARNINGS);
                });
    }
}
