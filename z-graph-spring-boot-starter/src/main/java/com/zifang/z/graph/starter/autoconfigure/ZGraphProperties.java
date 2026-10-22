package com.zifang.z.graph.starter.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * z-graph 内嵌版本仓库与 HTTP 控制面的装配开关。
 *
 * <pre>
 * z:
 *   graph:
 *     enabled: true
 *     port: 8090               # 0 = 让内核分配，之后只能读 graphControlServer.port()
 *     host: 127.0.0.1          # 控制面 bind 地址，默认回环
 *     api-token: xxx           # 控制面 API Token；留空 = 读环境变量 Z_GRAPH_API_TOKEN
 *     data-dir: /data/zgraph   # 留空 = 进程临时目录仓库（进程退出即弃）
 * </pre>
 *
 * <p><b>data-dir 的规范键是 {@code z.base.graph.data-dir}</b>（对齐组织里
 * {@code z.base.db.*} 家族命名）；{@code z.graph.data-dir} 保留为**兼容别名**
 * —— 既有部署（k8s ConfigMap 的 {@code Z_GRAPH_DATA_DIR}、z-opc 的
 * {@code application.properties}）已经在用它，直接改前缀会打断现网。</p>
 *
 * <p><b>解析优先级</b>（{@link #resolveDataDir(Environment)}）：
 * {@code z.base.graph.data-dir} &gt; {@code z.graph.data-dir} &gt;
 * 环境变量 {@code Z_BASE_GRAPH_DATA_DIR} / {@code Z_GRAPH_DATA_DIR} &gt; 空。
 * 留空 ⇒ 仓库落**进程临时目录**，进程退出即弃。</p>
 *
 * <p><b>静默 profile 集合</b>（{@link #isQuietProfile(Environment)}）：默认
 * {@code dev,local,localhost} —— 这些是开发口径，临时目录是预期行为，不该吵；
 * 其它 profile（{@code prod} / 无 profile 的 {@code default} / 自定义名）留空即 WARN。
 * 集合可用 {@code z.base.graph.quiet-profiles} 覆盖（逗号分隔）。</p>
 *
 * <p><b>host 现在是真配置</b>：{@code GraphControlServer} 原先 bind 的是通配地址
 * （{@code new InetSocketAddress(port)}），本进程连它走 127.0.0.1 纯属事实而非配置；
 * 现在 L3 真的按 host bind（默认回环），这一项才第一次能改到连接目标。</p>
 *
 * <p><b>api-token 与环境变量是同一件事</b>：Spring 的宽松绑定把属性
 * {@code z.graph.api-token} 对应到环境变量 {@code Z_GRAPH_API_TOKEN}，
 * 所以既有的"只注入环境变量"的部署不用改任何东西；而两处都没有时
 * 控制面**拒绝启动**（fail-closed，见 {@code GraphControlServer}）。</p>
 *
 * <p>旧版四个视图缓存档位（checkpoint-interval / max-retained-views /
 * retained-whole-graph-views / view-layer-limit）随存储层重构移除：引擎原生 MVCC
 * 之后不存在物化视图缓存，无可调。</p>
 */
@ConfigurationProperties(prefix = "z.graph")
public class ZGraphProperties {

    /** 总开关：false（默认）时本 starter 不装任何 Bean，也不碰磁盘和网络。 */
    private boolean enabled = false;

    /** 控制面端口；0 表示让内核分配，此时必须读 server.port() 而不是本配置值。 */
    private int port = 8090;

    /** 控制面 bind 地址；默认回环 127.0.0.1 —— 控制面有写接口，不该默认对外。 */
    private String host = "127.0.0.1";

    /** API Token；留空 = 读环境变量 Z_GRAPH_API_TOKEN（名字未变）；两处都没有 ⇒ 拒绝启动。 */
    private String apiToken;

    /** 文件仓库目录；留空则用进程临时目录仓库。 */
    private String dataDir;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    // ==================================================================
    // data-dir 解析与「留空即 WARN」策略
    //
    // 为什么放在这里而不是各自 @Value：ZGraphAutoConfiguration（starter 口径）与
    // GraphEmbeddedServerConfig（宿主胶水口径）是**两个装 Bean 的入口**，卡纪律要求
    // 「两处口径对齐」——所以策略必须是**同一份代码**，不能各写一遍。
    // 本类是本模块唯一的配置契约载体（规范键 z.base.graph.data-dir 不在本类绑定的
    // z.graph 前缀下，只能走 Environment），策略放这儿正好。
    // ==================================================================

    /** 规范键：对齐组织 z.base.db.* 家族（新增，优先）。 */
    public static final String CANONICAL_DATA_DIR_PROPERTY = "z.base.graph.data-dir";

    /** 兼容别名：既有部署在用（k8s ConfigMap 的 Z_GRAPH_DATA_DIR、z-opc 的 application.properties）。 */
    public static final String COMPAT_DATA_DIR_PROPERTY = "z.graph.data-dir";

    /** 规范键的环境变量形态。 */
    public static final String CANONICAL_DATA_DIR_ENV = "Z_BASE_GRAPH_DATA_DIR";

    /** 兼容别名的环境变量形态（Spring 宽松绑定 z.graph.data-dir 的等价物）。 */
    public static final String COMPAT_DATA_DIR_ENV = "Z_GRAPH_DATA_DIR";

    /** 静默 profile 集合的配置键（逗号分隔）。 */
    public static final String QUIET_PROFILES_PROPERTY = "z.base.graph.quiet-profiles";

    /** 静默 profile 默认集合：dev / local / localhost —— 开发口径，临时目录是预期行为，不该吵。 */
    public static final String DEFAULT_QUIET_PROFILES = "dev,local,localhost";

    /**
     * 解析最终生效的 data-dir。
     *
     * <p>优先级（先到先得，空白值视为「没配」往下走）：
     * <ol>
     *   <li>{@code z.base.graph.data-dir}（规范键，属性形态）</li>
     *   <li>{@code Z_BASE_GRAPH_DATA_DIR}（规范键，环境变量形态）</li>
     *   <li>{@code z.graph.data-dir}（兼容别名，属性形态）</li>
     *   <li>{@code Z_GRAPH_DATA_DIR}（兼容别名，环境变量形态）</li>
     * </ol>
     *
     * <p>规范键的环境变量排在兼容别名的属性**之前**：它是同一个键的两种形态，
     * 规范键整体高于别名整体；别名内部仍然是「属性形态高于环境变量形态」，
     * 与 Spring 自己的属性源顺序一致。</p>
     *
     * @return 生效目录；**null 表示没配**（⇒ 落进程临时目录）
     */
    public static String resolveDataDir(Environment env) {
        if (env == null) {
            return null;
        }
        String canonical = trimmed(env.getProperty(CANONICAL_DATA_DIR_PROPERTY));
        if (canonical != null) {
            return canonical;
        }
        String canonicalEnv = trimmed(System.getenv(CANONICAL_DATA_DIR_ENV));
        if (canonicalEnv != null) {
            return canonicalEnv;
        }
        String compat = trimmed(env.getProperty(COMPAT_DATA_DIR_PROPERTY));
        if (compat != null) {
            return compat;
        }
        return trimmed(System.getenv(COMPAT_DATA_DIR_ENV));
    }

    /**
     * 当前 profile 是否属于「静默集合」。
     *
     * <p>静默集合默认 {@code dev,local,localhost}：这三者是开发口径，临时目录是预期行为，
     * 启动 WARN 只会变成噪音（组织实际 profile 名是 local / prod / localhost，**没有 dev**；
     * 卡里说的 dev 按「开发环境」这一语义落到 local/localhost 上，dev 也一并留着兼容）。</p>
     *
     * <p>可用 {@code z.base.graph.quiet-profiles} 覆盖（逗号分隔）。</p>
     */
    public static boolean isQuietProfile(Environment env) {
        if (env == null) {
            return false;
        }
        Set<String> quiet = quietProfiles(env);
        for (String active : env.getActiveProfiles()) {
            if (quiet.contains(active.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 生效的静默 profile 集合（已小写化）。 */
    public static Set<String> quietProfiles(Environment env) {
        String raw = env == null ? null : env.getProperty(QUIET_PROFILES_PROPERTY);
        if (raw == null || raw.trim().isEmpty()) {
            raw = DEFAULT_QUIET_PROFILES;
        }
        Set<String> result = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String token = part.trim().toLowerCase(Locale.ROOT);
            if (!token.isEmpty()) {
                result.add(token);
            }
        }
        return result;
    }

    /**
     * 「data-dir 留空」的启动 WARN 文案。
     *
     * <p>纯函数：把「该不该吵」与「吵什么」从日志框架里剥出来，两个装 Bean 的入口共用，
     * 也让断言能落在决策上而不是日志文本的模糊匹配上。</p>
     *
     * @param env 当前环境
     * @return WARN 文案；**不该 WARN 时返回 null**（已显式配置目录 / 静默 profile）
     */
    public static String dataDirWarning(Environment env) {
        if (resolveDataDir(env) != null) {
            return null;
        }
        if (isQuietProfile(env)) {
            return null;
        }
        return "[z-graph] data-dir 未配置：版本仓库将落在**进程临时目录**（进程退出即弃，"
                + "已提交的图数据随进程一起丢弃，重启后不可恢复）。"
                + "生产口径请显式配置目录：设置 " + CANONICAL_DATA_DIR_PROPERTY + "=<绝对路径>"
                + "（或环境变量 " + CANONICAL_DATA_DIR_ENV + "）；"
                + "兼容键 " + COMPAT_DATA_DIR_PROPERTY + " / " + COMPAT_DATA_DIR_ENV
                + " 仍然生效，但仅作过渡。";
    }

    /** 去掉首尾空白；空串按「没配」处理（返回 null）。 */
    private static String trimmed(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return value.isEmpty() ? null : value;
    }
}
