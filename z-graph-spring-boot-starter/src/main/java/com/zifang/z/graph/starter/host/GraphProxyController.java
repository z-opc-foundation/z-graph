package com.zifang.z.graph.starter.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 图数据库 (z-graph) 管理面代理: 把内嵌 GraphControlServer 挂到 z-opc 的统一前缀 {@code /api/graph/**}.
 *
 * <p>形状与 {@link VectorProxyController} 一致 (逐字节透传上游响应, 不在 z-opc 侧重拼 JSON),
 * 差别只有两处, 都是 z-graph 自己的特性逼出来的:
 * <ol>
 *   <li><b>query string 的校验从"字符白名单"改成"黑名单 (禁 CR/LF/NUL)"。</b>
 *       z-graph 的 {@code GET /query?cypher=...} 要携带整条 Cypher, 而 JS 的
 *       {@code encodeURIComponent} **不转义** {@code ( ) ! * ' ~} ——
 *       {@code MATCH (n:Person)-[r]->(m)} 全都会原样出现在 query string 里。
 *       沿用 vector 那份白名单会把合法查询挡成 400。这里只禁控制字符
 *       (防响应头/请求行注入), 转发目标恒为 {@code 127.0.0.1:<本 JVM 自己 bind 的端口>},
 *       路径段另有白名单校验, 所以不存在 SSRF 面。</li>
 *   <li><b>{@code __instance} 多带一个"外来监听者"判定。</b> 本卡最大的陷阱不是"内嵌没起",
 *       而是"内嵌没起、但那个端口上有**别人**在应答" —— 那样页面照样出数, 只是数不是这个 JVM 的。
 *       判定式是机械的: {@code boundPort == 0 && 配置端口 TCP 可连} ⇒ 应答者一定不是本 JVM
 *       (本 JVM 没 bind 上)。这一格为 true 时**禁止**把下面的任何返回值当自己的数据。</li>
 * </ol>
 *
 * <p>只挂在 {@code /api/**} 下, 不开裸路径 —— 裸路径不在 {@code sso.intercept-paths} 内, 等于不设防
 * (TASK-20260924-018 的教训)。上游 {@code GraphControlServer} 反过来是 bind 通配地址、
 * 默认无鉴权的 ({@code Z_GRAPH_API_TOKEN} 未设即不设防), 所以"前端只走 8888"这条不只是规约,
 * 是实际的安全边界。
 *
 * <p><b>上游端点</b> (逐个对着 z-graph-bolt-server 的
 * {@code GraphControlServer} 构造函数核过, 不是照 README 抄的):
 * {@code /health}、{@code /meta/{branches,commits,schema,stats,metrics,logs,export,import}}、
 * {@code /query}、{@code /query/batch}、{@code /query/explain}、{@code /options}。
 * README 里写的 {@code /meta/{...}} 端点集与实际注册一致, 但**注册顺序**上
 * {@code /query/batch} 与 {@code /query/explain} 排在 {@code /query} 之前 ——
 * JDK {@code HttpServer} 按最长前缀匹配, 所以这个顺序不影响路由 (与 README 的暗示无关)。
 */
@RestController
public class GraphProxyController {

    private static final String PREFIX = "/api/graph";
    /** 上游注册的全是固定路径段: /health /meta/xxx /query[/xxx] */
    private static final Pattern SAFE_PATH = Pattern.compile("^/[A-Za-z0-9_\\-/]*$");
    /** 只挡控制字符 (请求行/头注入), 其余交给上游自己解析 */
    private static final Pattern FORBIDDEN_QUERY = Pattern.compile("[\\r\\n\\x00]");
    private static final int UPSTREAM_TIMEOUT_MS = 8000;
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private final ObjectProvider<GraphEmbeddedServerConfig> embeddedProvider;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 配置端口单独读一份: {@code zgraph.enabled} 不为 true 时内嵌 bean 根本不存在,
     *  但"这个端口上是不是有别人"这个问题仍然要能回答 —— 那是本卡验收第 1 条。 */
    @Value("${z.graph.server.port:8090}")
    private int configuredPort;

    public GraphProxyController(ObjectProvider<GraphEmbeddedServerConfig> embeddedProvider) {
        this.embeddedProvider = embeddedProvider;
    }

    /**
     * 自省接口: 这一路到底连的是谁。孵化期排障必需 —— 只有先确认"应答来自本 JVM 的哪个端口",
     * 后面 {@code /api/graph/meta/schema} 的空数组才是"真·空"而不是"连错了进程"。
     */
    @GetMapping(PREFIX + "/__instance")
    public void instance(HttpServletResponse resp) throws Exception {
        GraphEmbeddedServerConfig cfg = embeddedProvider.getIfAvailable();
        int bound = cfg == null ? 0 : cfg.getBoundPort();
        int configured = cfg == null ? configuredPort : cfg.getConfiguredPort();
        boolean answering = cfg != null && cfg.isAcceptingConnections();
        boolean anyoneOnConfigured = bound > 0 ? answering : tcpReachable(configured);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jvm", ManagementFactory.getRuntimeMXBean().getName());
        out.put("lifecycleBean", cfg != null);
        out.put("configuredPort", configured);
        out.put("boundPort", bound);
        out.put("embeddedRunning", bound > 0);
        out.put("acceptingNow", answering);
        out.put("bindError", cfg == null ? "zgraph.enabled != true ⇒ GraphEmbeddedServerConfig 未装载"
                : cfg.getBindError());
        // 本 JVM 没 bind 成 + 配置端口上有人应答 ⇒ 那是别人的进程。此时页面的一切数据都不属于本次构建。
        out.put("foreignListenerSuspected", bound <= 0 && anyoneOnConfigured);
        // GraphControlServer 的唯一鉴权是环境变量; 不设 = 通配端口完全不设防
        out.put("apiTokenConfigured", System.getenv("Z_GRAPH_API_TOKEN") != null);
        out.put("upstreamEndpoints", Arrays.asList("/health", "/meta/branches", "/meta/commits",
                "/meta/schema", "/meta/stats", "/meta/metrics", "/meta/logs", "/meta/export",
                "/meta/import", "/query", "/query/batch", "/query/explain"));
        writeJson(resp, 200, out);
    }

    @RequestMapping(path = PREFIX + "/**",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public void proxy(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String sub = subPath(req.getRequestURI());
        if (sub == null) {
            writeJson(resp, 400, err("bad path", req.getRequestURI()));
            return;
        }
        String query = req.getQueryString();
        if (query != null && FORBIDDEN_QUERY.matcher(query).find()) {
            writeJson(resp, 400, err("bad query", sub));
            return;
        }
        GraphEmbeddedServerConfig cfg = embeddedProvider.getIfAvailable();
        int port = cfg == null ? 0 : cfg.getBoundPort();
        if (port <= 0) {
            // 静默跳过是这里最难查的故障形态, 宁可显式失败也不要看起来"空得有道理"
            boolean foreign = tcpReachable(cfg == null ? configuredPort : cfg.getConfiguredPort());
            Map<String, Object> body = err("embedded GraphControlServer not bound in this JVM"
                    + " (lifecycleBean=" + (cfg != null) + ", boundPort=" + port
                    + ", bindError=" + (cfg == null ? "config-not-loaded" : String.valueOf(cfg.getBindError())) + ")", sub);
            body.put("hint", foreign
                    ? "配置端口上有监听者但**不是本 JVM** —— 那是外部进程, 不要把它的数据当成本次孵化的结果"
                    : "配置端口上没有任何监听者 —— zgraph.enabled 不为 true, 或 GraphControlServer 构造时 bind 失败");
            writeJson(resp, 503, body);
            return;
        }

        String url = "http://127.0.0.1:" + port + sub + (query == null || query.isEmpty() ? "" : "?" + query);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(UPSTREAM_TIMEOUT_MS);
        conn.setReadTimeout(UPSTREAM_TIMEOUT_MS);
        conn.setRequestMethod(req.getMethod());
        conn.setRequestProperty("Accept", "application/json");
        // 上游 GraphControlServer 的唯一鉴权是环境变量 Z_GRAPH_API_TOKEN (它自己读 env，
        // 见其构造函数第 67 行)，认 Authorization: Bearer <token> 或 ?token=。
        // 8091 是通配地址端口 (HttpServer.create(new InetSocketAddress(port), …) 没有 bind 地址参数)，
        // 所以启动脚本必须 export 一个随机 token 才不会在局域网上裸一个可写图库；
        // 同 JVM 的这里从同一个 env 取值，两边天然一致，也不需要把 token 落到任何配置文件里。
        String apiToken = System.getenv("Z_GRAPH_API_TOKEN");
        if (apiToken != null && !apiToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiToken);
        }
        String ct = req.getContentType();
        boolean wantsBody = "POST".equals(req.getMethod()) || "PUT".equals(req.getMethod());
        byte[] body = wantsBody ? readLimited(req.getInputStream(), MAX_BODY_BYTES) : null;
        if (body != null && body.length > 0) {
            conn.setDoOutput(true);
            if (ct != null) conn.setRequestProperty("Content-Type", ct);
            conn.setFixedLengthStreamingMode(body.length);
        }

        int status;
        byte[] respBody;
        try {
            conn.connect();
            if (body != null && body.length > 0) {
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }
            }
            status = conn.getResponseCode();
            InputStream src = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            respBody = src == null ? new byte[0] : readLimited(src, MAX_BODY_BYTES);
        } catch (Exception e) {
            writeJson(resp, 502, err("upstream call failed: " + e.getClass().getSimpleName()
                    + " " + e.getMessage(), sub));
            return;
        } finally {
            conn.disconnect();
        }

        String upstreamType = conn.getContentType();
        resp.setStatus(status);
        resp.setContentType(upstreamType == null ? "application/json;charset=UTF-8" : upstreamType);
        resp.setContentLength(respBody.length);
        resp.getOutputStream().write(respBody);
        resp.getOutputStream().flush();
    }

    /** "/api/graph/meta/schema" → "/meta/schema"; 不在白名单内返回 null */
    private String subPath(String uri) {
        if (uri == null || !uri.startsWith(PREFIX)) return null;
        String sub = uri.substring(PREFIX.length());
        if (sub.isEmpty()) sub = "/";
        if (sub.indexOf("..") >= 0 || !SAFE_PATH.matcher(sub).matches()) return null;
        return sub;
    }

    private static boolean tcpReachable(int p) {
        if (p <= 0) return false;
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", p), 300);
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static byte[] readLimited(InputStream in, int max) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n, total = 0;
        while ((n = in.read(chunk)) > 0) {
            total += n;
            if (total > max) throw new java.io.IOException("body exceeds " + max + " bytes");
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private Map<String, Object> err(String msg, String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("source", "z-opc-graph-proxy");
        m.put("message", msg);
        m.put("path", path);
        return m;
    }

    private void writeJson(HttpServletResponse resp, int status, Object body) throws Exception {
        byte[] out = mapper.writeValueAsBytes(body);
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setContentLength(out.length);
        resp.getOutputStream().write(out);
        resp.getOutputStream().flush();
    }
}
