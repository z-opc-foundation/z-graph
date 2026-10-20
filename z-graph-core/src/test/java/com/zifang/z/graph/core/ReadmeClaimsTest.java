package com.zifang.z.graph.core;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * z-graph README 定量主张自守卫。
 *
 * <p>判据：README 里写死的定量主张（版本号 / 父项目版本 / reactor 模块数 / HTTP context 数 /
 * 控制台 tab 数 / 日志环形缓冲条数 / 聚合函数数 / 默认端口）必须能从仓内源码重算出来 ——
 * README 改了就红，源码变了 README 没跟上也得红。
 *
 * <p>口径约定（踩过的坑，勿随意改）：
 * <ul>
 *   <li>根 pom 的 version / parent version / module 数走 XML 解析：正则会抓到 {@code <parent>} 里的
 *       {@code <version>}，也躲不掉被 {@code <!-- -->} 注释掉的 {@code <module>}；</li>
 *   <li>README 里的数字靠「定位标签 + 抠数正则」取，<b>抠不到就红</b>（不允许「没匹配上就跳过」，
 *       那正是空跑）；</li>
 *   <li>定位标签用「表格行首关键字」或「唯一行」，不拿数字本身当定位标签
 *       （短数字是长数字的子串，按数字定位会匹配到别人的行上）；</li>
 *   <li>控制台 tab 数从 {@code z-graph-console/src/App.jsx} 的 {@code TABS} 数组数出来 ——
 *       控制台不在 Maven reactor 里，但它是仓内源码，README 说它有 N 个 tab 就得真是 N 个；</li>
 *   <li>本仓 README 里「195 个 @Test / 25 个测试类」是自指计数（守卫自己会加一个文件进去），
 *       按组织口径不纳入守卫，README 侧用「计数口径」注明快照口径。</li>
 * </ul>
 */
class ReadmeClaimsTest {

    // ------------------------------------------------------------------ 定位与读取

    /** 仓根：测试跑在模块目录，user.dir 不是仓根 —— 向上找同时有 README.md 与 pom.xml 的目录（最多 5 层）。 */
    private static Path repoRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int i = 0; i < 5 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("README.md")) && Files.isRegularFile(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("定位仓根失败：从 " + System.getProperty("user.dir")
                + " 向上 5 层内没有同时含 README.md 与 pom.xml 的目录");
    }

    /** README 全文（统一成 \n 换行，便于按行算偏移）。 */
    private static String readmeText() {
        try {
            String raw = new String(Files.readAllBytes(repoRoot().resolve("README.md")), StandardCharsets.UTF_8);
            return raw.replace("\r\n", "\n");
        } catch (IOException e) {
            throw new AssertionError("读取 README.md 失败", e);
        }
    }

    /** 读一个仓内文件（相对仓根）。 */
    private static String sourceText(String relativePath) {
        Path file = repoRoot().resolve(relativePath);
        if (!Files.isRegularFile(file)) {
            throw new AssertionError("仓内文件不存在：" + relativePath);
        }
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("读取 " + relativePath + " 失败", e);
        }
    }

    // ------------------------------------------------------------------ 源码重算

    /** 根 pom 的 project 元素（XML 解析：注释掉的 <module> 天然不计入）。 */
    private static Element rootProject() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder().parse(repoRoot().resolve("pom.xml").toFile());
            return doc.getDocumentElement();
        } catch (Exception e) {
            throw new AssertionError("解析根 pom.xml 失败", e);
        }
    }

    private static Element directChild(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    /** 根 pom 的版本号（本仓没用 ${revision}，逐字面写）。 */
    private static String rootPomVersion() {
        Element version = directChild(rootProject(), "version");
        if (version == null) {
            throw new AssertionError("根 pom.xml 没有 <version>");
        }
        return version.getTextContent().trim();
    }

    /** 根 pom <parent> 的版本号（README「父项目」行）。 */
    private static String rootParentVersion() {
        Element version = directChild(directChild(rootProject(), "parent"), "version");
        if (version == null) {
            throw new AssertionError("根 pom.xml 的 <parent> 没有 <version>");
        }
        return version.getTextContent().trim();
    }

    /** 根 pom <modules> 下实际参与构建的模块数（被注释掉的 module 不算）。 */
    private static int rootPomModuleCount() {
        Element modules = directChild(rootProject(), "modules");
        if (modules == null) {
            throw new AssertionError("根 pom.xml 没有 <modules>");
        }
        int count = 0;
        NodeList children = modules.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && "module".equals(node.getNodeName())) {
                count++;
            }
        }
        return count;
    }

    /** GraphControlServer 注册的 HTTP context 数。 */
    private static int httpContextCount() {
        String src = sourceText("z-graph-bolt-server/src/main/java/com/zifang/z/graph/bolt/GraphControlServer.java");
        int count = 0;
        Matcher m = Pattern.compile("createContext\\(").matcher(src);
        while (m.find()) {
            count++;
        }
        if (count == 0) {
            throw new AssertionError("GraphControlServer 里找不到 createContext 调用");
        }
        return count;
    }

    /** 控制台 tab 数（z-graph-console/src/App.jsx 的 TABS 数组）。 */
    private static int consoleTabCount() {
        String src = sourceText("z-graph-console/src/App.jsx");
        int start = src.indexOf("const TABS = [");
        if (start < 0) {
            throw new AssertionError("App.jsx 里找不到 TABS 数组");
        }
        int end = src.indexOf("];", start);
        if (end < 0) {
            throw new AssertionError("App.jsx 的 TABS 数组没有闭合");
        }
        Matcher m = Pattern.compile("\\{\\s*id\\s*:").matcher(src.substring(start, end));
        int count = 0;
        while (m.find()) {
            count++;
        }
        if (count == 0) {
            throw new AssertionError("App.jsx 的 TABS 数组里没有条目");
        }
        return count;
    }

    /** 请求日志环形缓冲条数（GraphControlServer.LOG_BUFFER_SIZE）。 */
    private static int logBufferSize() {
        String src = sourceText("z-graph-bolt-server/src/main/java/com/zifang/z/graph/bolt/GraphControlServer.java");
        Matcher m = Pattern.compile("LOG_BUFFER_SIZE\\s*=\\s*(\\d+)").matcher(src);
        if (!m.find()) {
            throw new AssertionError("GraphControlServer 里找不到 LOG_BUFFER_SIZE");
        }
        return Integer.parseInt(m.group(1));
    }

    /** CypherEngine 的 parseAggregate 认识的聚合函数个数（正则的候选组）。 */
    private static int aggregateFunctionCount() {
        String src = sourceText("z-graph-core/src/main/java/com/zifang/z/graph/core/CypherEngine.java");
        int method = src.indexOf("parseAggregate");
        if (method < 0) {
            throw new AssertionError("CypherEngine 里找不到 parseAggregate");
        }
        Matcher m = Pattern.compile("Pattern\\.compile\\(\"[^\"]*\\^\\(([a-zA-Z|]+)\\)").matcher(src);
        if (!m.find(method)) {
            throw new AssertionError("CypherEngine 的 parseAggregate 里找不到聚合函数正则");
        }
        return m.group(1).split("\\|").length;
    }

    /** ZGraphServer 的两个默认端口（Bolt / HTTP 控制面）。 */
    private static String defaultPorts() {
        String src = sourceText("z-graph-bolt-server/src/main/java/com/zifang/z/graph/bolt/ZGraphServer.java");
        Matcher bolt = Pattern.compile("\"Z_GRAPH_BOLT_PORT\",\\s*\"z\\.graph\\.boltPort\",\\s*(\\d+)").matcher(src);
        Matcher http = Pattern.compile("\"Z_GRAPH_HTTP_PORT\",\\s*\"z\\.graph\\.httpPort\",\\s*(\\d+)").matcher(src);
        if (!bolt.find()) {
            throw new AssertionError("ZGraphServer 里找不到 Bolt 默认端口");
        }
        if (!http.find()) {
            throw new AssertionError("ZGraphServer 里找不到 HTTP 控制面默认端口");
        }
        return bolt.group(1) + "/" + http.group(1);
    }

    // ------------------------------------------------------------------ README 主张抽取

    /** 主张所在文本块：全文区间 + 文本。 */
    private static final class Block {
        final int start;
        final int end;
        final String text;

        Block(int start, int end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    /** 主张定位：把 README 全文映射到一个唯一的文本块。 */
    private interface Anchor {
        Block locate(String readme, String label);
    }

    /** README 侧取值：从主张块里抠出「值」。抠不到就红。 */
    private interface ValueExtractor {
        String extract(Block block, String label);
    }

    /** 合成变异：返回一份「把这条主张改错」的 README 全文。 */
    private interface Mutator {
        String mutate(String readme, Block block, String label);
    }

    /** 一条定量主张：定位标签 + 抠值 + 源码重算口径 + 变异方式。 */
    private static final class Claim {
        final String label;
        final Anchor anchor;
        final ValueExtractor extract;
        final Supplier<String> expected;
        final Mutator mutate;

        Claim(String label, Anchor anchor, ValueExtractor extract, Supplier<String> expected, Mutator mutate) {
            this.label = label;
            this.anchor = anchor;
            this.extract = extract;
            this.expected = expected;
            this.mutate = mutate;
        }
    }

    private static List<String> lines(String readme) {
        return Arrays.asList(readme.split("\n", -1));
    }

    private static int lineStart(String readme, int lineIndex) {
        int idx = 0;
        for (int i = 0; i < lineIndex; i++) {
            int nl = readme.indexOf('\n', idx);
            if (nl < 0) {
                return readme.length();
            }
            idx = nl + 1;
        }
        return idx;
    }

    /** 定位 README 中唯一匹配的行；找不到或不唯一都红。 */
    private static Block uniqueLine(String readme, Pattern anchor, String label) {
        List<String> all = lines(readme);
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (anchor.matcher(all.get(i)).find()) {
                hits.add(i);
            }
        }
        if (hits.isEmpty()) {
            throw new AssertionError("README 里找不到主张行：" + label + "（定位 " + anchor.pattern() + "）");
        }
        if (hits.size() > 1) {
            throw new AssertionError("README 里主张行定位不唯一（命中 " + hits.size() + " 行）：" + label);
        }
        int i = hits.get(0);
        int start = lineStart(readme, i);
        return new Block(start, start + all.get(i).length(), all.get(i));
    }

    /** 数字型取值：正则第 1 组。 */
    private static ValueExtractor number(final Pattern pattern) {
        return (block, label) -> {
            Matcher m = pattern.matcher(block.text);
            if (!m.find()) {
                throw new AssertionError("README 主张里抠不到数字：" + label + "（正则 " + pattern.pattern() + "）");
            }
            return m.group(1);
        };
    }

    /** 双数字取值：两个正则各取第 1 组，用 "/" 拼起来（一行里两个数，如两个默认端口）。 */
    private static ValueExtractor numberPair(final Pattern first, final Pattern second) {
        return (block, label) -> {
            Matcher a = first.matcher(block.text);
            Matcher b = second.matcher(block.text);
            if (!a.find()) {
                throw new AssertionError("README 主张里抠不到数字：" + label + "（正则 " + first.pattern() + "）");
            }
            if (!b.find()) {
                throw new AssertionError("README 主张里抠不到数字：" + label + "（正则 " + second.pattern() + "）");
            }
            return a.group(1) + "/" + b.group(1);
        };
    }

    /** 数字型变异：把抠出的数字末段 +1。 */
    private static Mutator shiftNumber(final Pattern pattern) {
        return (readme, block, label) -> {
            Matcher m = pattern.matcher(block.text);
            if (!m.find()) {
                throw new AssertionError("变异前提不成立（抠不到数字）：" + label);
            }
            String original = m.group(1);
            String mutated = shiftLastSegment(original);
            assertNotEquals(original, mutated, "变异没有生效：" + label);
            int from = block.start + m.start(1);
            int to = block.start + m.end(1);
            return readme.substring(0, from) + mutated + readme.substring(to);
        };
    }

    /** 数字末段自增：1.0.8 → 1.0.9；13 → 14；7687 → 7688。 */
    private static String shiftLastSegment(String raw) {
        String[] parts = raw.split("\\.");
        int last = parts.length - 1;
        parts[last] = String.valueOf(Integer.parseInt(parts[last]) + 1);
        return String.join(".", parts);
    }

    // ------------------------------------------------------------------ 主张表

    private static List<Claim> claims() {
        List<Claim> list = new ArrayList<>();

        // 1. 基本信息表：当前版本 = 根 POM 的 <version>（本仓没用 ${revision}）
        Pattern version = Pattern.compile("^\\|\\s*\\*\\*当前版本\\*\\*\\s*\\|\\s*`(\\d+\\.\\d+\\.\\d+)`");
        list.add(new Claim("根 POM 版本号（当前版本）",
                (readme, label) -> uniqueLine(readme, Pattern.compile("\\*\\*当前版本\\*\\*"), label),
                number(version),
                ReadmeClaimsTest::rootPomVersion,
                shiftNumber(version)));

        // 2. 基本信息表：父项目版本 = 根 POM <parent><version>
        Pattern parentVersion = Pattern.compile("z-boot-parent:(\\d+\\.\\d+\\.\\d+)");
        list.add(new Claim("父项目版本号",
                (readme, label) -> uniqueLine(readme, Pattern.compile("\\*\\*父项目\\*\\*"), label),
                number(parentVersion),
                ReadmeClaimsTest::rootParentVersion,
                shiftNumber(parentVersion)));

        // 3. 项目结构说明：reactor 实际参与构建的模块数
        Pattern moduleCount = Pattern.compile("(\\d+)\\s*个模块");
        list.add(new Claim("reactor 模块数",
                (readme, label) -> uniqueLine(readme, Pattern.compile("Maven reactor 只有"), label),
                number(moduleCount),
                () -> String.valueOf(rootPomModuleCount()),
                shiftNumber(moduleCount)));

        // 4. 能力清单：HTTP 控制面注册的 context 数
        //    锚点带 `GraphControlServer` —— 「技术栈」表里还有一行以「| HTTP 控制面 |」开头，
        //    只按行首关键字定位会命中两行。
        Pattern contextCount = Pattern.compile("(\\d+)\\s*个\\s*context");
        list.add(new Claim("HTTP 控制面 context 数",
                (readme, label) -> uniqueLine(readme, Pattern.compile("^\\|\\s*HTTP 控制面\\s*\\|\\s*`GraphControlServer`"), label),
                number(contextCount),
                () -> String.valueOf(httpContextCount()),
                shiftNumber(contextCount)));

        // 5. 能力清单：控制台 tab 数
        Pattern tabCount = Pattern.compile("(\\d+)\\s*个\\s*tab");
        list.add(new Claim("控制台 tab 数",
                (readme, label) -> uniqueLine(readme, Pattern.compile("^\\|\\s*可视化控制台\\s*\\|"), label),
                number(tabCount),
                () -> String.valueOf(consoleTabCount()),
                shiftNumber(tabCount)));

        // 6. 能力清单：请求日志环形缓冲条数
        Pattern bufferSize = Pattern.compile("(\\d+)\\s*条环形缓冲");
        list.add(new Claim("请求日志环形缓冲条数",
                (readme, label) -> uniqueLine(readme, Pattern.compile("^\\|\\s*观测\\s*\\|"), label),
                number(bufferSize),
                () -> String.valueOf(logBufferSize()),
                shiftNumber(bufferSize)));

        // 7. Cypher 支持矩阵：parseAggregate 认识的聚合函数数
        Pattern aggregateCount = Pattern.compile("只收这\\s*(\\d+)\\s*个函数");
        list.add(new Claim("聚合函数数",
                (readme, label) -> uniqueLine(readme, Pattern.compile("只收这"), label),
                number(aggregateCount),
                () -> String.valueOf(aggregateFunctionCount()),
                shiftNumber(aggregateCount)));

        // 8. 基本信息表：Bolt / HTTP 控制面两个默认端口
        Pattern boltPort = Pattern.compile("Bolt\\s*`(\\d+)`");
        Pattern httpPort = Pattern.compile("HTTP 控制面\\s*`(\\d+)`");
        list.add(new Claim("默认端口（Bolt / HTTP 控制面）",
                (readme, label) -> uniqueLine(readme, Pattern.compile("\\*\\*默认端口\\*\\*"), label),
                numberPair(boltPort, httpPort),
                ReadmeClaimsTest::defaultPorts,
                shiftNumber(boltPort)));

        return list;
    }

    /** 逐条对账：README 的值 == 源码重算值（一次全报：把不一致全列出来再抛）。 */
    private static void checkClaims(String readme, List<Claim> claims) {
        List<String> mismatches = new ArrayList<>();
        for (Claim claim : claims) {
            Block block = claim.anchor.locate(readme, claim.label);
            String actual = claim.extract.extract(block, claim.label);
            String expected = claim.expected.get();
            if (!expected.equals(actual)) {
                mismatches.add(claim.label + " —— README 写 " + actual + "，源码重算 " + expected);
            }
        }
        if (!mismatches.isEmpty()) {
            throw new AssertionError("README 定量主张与源码重算值不一致（" + mismatches.size() + " 条）：\n  - "
                    + String.join("\n  - ", mismatches));
        }
    }

    // ------------------------------------------------------------------ 用例

    /** 用例 A：真实 README 的所有主张与源码重算值逐条对账 —— 必须全绿。 */
    @Test
    void readmeQuantitativeClaimsMustMatchSource() {
        checkClaims(readmeText(), claims());
    }

    /** 用例 B（牙口）：每条主张都合成一个改错的 README，必须抛 AssertionError，且报错只指向那条主张。 */
    @Test
    void mutatedReadmeMustBeRejected() {
        String readme = readmeText();
        List<Claim> claims = claims();
        // 基线必须先健康，否则「报错指向哪条主张」的判断没意义
        assertDoesNotThrow(() -> checkClaims(readme, claims), "基线 README 自身已漂移，先把 README 修回与源码一致");
        for (Claim claim : claims) {
            Block block = claim.anchor.locate(readme, claim.label);
            String broken = claim.mutate.mutate(readme, block, claim.label);
            assertNotEquals(readme, broken, "变异没有生效：" + claim.label);
            AssertionError error = assertThrows(AssertionError.class, () -> checkClaims(broken, claims),
                    "主张被放过（README 写错但没红）：" + claim.label);
            assertTrue(error.getMessage().contains(claim.label),
                    "报错没指向真正对不上的主张：" + claim.label + " / " + error.getMessage());
            for (Claim other : claims) {
                if (!other.label.equals(claim.label)) {
                    assertTrue(!error.getMessage().contains(other.label),
                            "报错混进了别的主张（" + other.label + "）：" + error.getMessage());
                }
            }
        }
    }

    /** 用例 C（不许冤枉健康页）：真实 README 不得被判红，且每条主张都必须真的定位到并抠出值。 */
    @Test
    void realReadmeMustNotBeFlagged() {
        String readme = readmeText();
        List<Claim> claims = claims();
        for (Claim claim : claims) {
            Block block = claim.anchor.locate(readme, claim.label);
            String value = claim.extract.extract(block, claim.label);
            assertTrue(value != null && !value.isEmpty(), "主张没抠出值（守卫空跑）：" + claim.label);
        }
        assertDoesNotThrow(() -> checkClaims(readme, claims), "真实 README 被误判为红");
    }
}
