package com.zifang.z.graph.bolt;

import com.zifang.z.graph.core.GraphVersionStore;
import com.zifang.z.graph.core.GraphWriteTransaction;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.zifang.z.graph.api.Colls;

class GraphControlServerTest {

    @Test
    void exposesMetaHealthAndCommitBoundQuery() throws Exception {
        GraphVersionStore repository = new GraphVersionStore();
        GraphWriteTransaction write = repository.beginWrite("main");
        write.addNode("Person", Colls.mapOf("name", "Control Alice"));
        String commitId = write.commit("test", "control server").getId();

        GraphControlServer server = new GraphControlServer(0, repository);
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.port();

            Response health = get(base + "/health");
            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("\"status\":\"UP\""));

            Response branches = get(base + "/meta/branches");
            assertEquals(200, branches.statusCode());
            assertTrue(branches.body().contains("main"));

            String cypher = URLEncoder.encode(
                    "MATCH (n:Person) RETURN n.name AS name", "UTF-8");
            Response query = get(base + "/query?commit=" + commitId + "&cypher=" + cypher);
            assertEquals(200, query.statusCode());
            assertTrue(query.body().contains("Control Alice"));
        } finally {
            server.stop();
        }
    }

    /**
     * 引用不存在和参数写错都是客户端的错，不能记成 500；但 404 与 400 必须分开，
     * 所以每条红判据旁边都钉一条"合法请求仍然 200"的对照——把所有失败一刀切成一个码骗不过去。
     */
    @Test
    void unknownReferenceIsClientErrorNotServerFailure() throws Exception {
        GraphVersionStore repository = new GraphVersionStore();
        GraphWriteTransaction write = repository.beginWrite("main");
        write.addNode("Person", Colls.mapOf("name", "Gate Alice"));
        String realCommit = write.commit("test", "gate status codes").getId();

        GraphControlServer server = new GraphControlServer(0, repository);
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.port();
            String cypher = URLEncoder.encode(
                    "MATCH (n:Person) RETURN n.name AS name", "UTF-8");

            assertEquals(200, get(base + "/query?commit=" + realCommit + "&cypher=" + cypher).statusCode());
            assertEquals(200, get(base + "/query?branch=main&cypher=" + cypher).statusCode());
            assertEquals(200, get(base + "/meta/schema?branch=main").statusCode());

            Response unknownCommit = get(base + "/query?commit=deadbeef&cypher=" + cypher);
            assertEquals(404, unknownCommit.statusCode(), unknownCommit.body());
            assertTrue(unknownCommit.body().contains("Unknown commit=deadbeef"), unknownCommit.body());

            assertEquals(404, get(base + "/query?branch=nope&cypher=" + cypher).statusCode());
            assertEquals(404, get(base + "/meta/schema?branch=nope").statusCode());
            assertEquals(404, get(base + "/meta/export?branch=nope").statusCode());

            Response badBranchName = get(base + "/query?branch="
                    + URLEncoder.encode("bad name!", "UTF-8") + "&cypher=" + cypher);
            assertEquals(400, badBranchName.statusCode(), badBranchName.body());
        } finally {
            server.stop();
        }
    }

    /** 一次 GET 的状态码 + 响应体；Java 8 没有 java.net.http，用 HttpURLConnection 等价实现。 */
    private static Response get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod("GET");
        try {
            int status = connection.getResponseCode();
            // 4xx/5xx 的正文在 errorStream 里，而这些断言正好要读它
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
