package com.zifang.z.graph.bolt;

import com.zifang.z.graph.core.GraphVersionStore;
import com.zifang.z.graph.core.GraphWriteTransaction;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphControlServerTest {

    @Test
    void exposesMetaHealthAndCommitBoundQuery() throws Exception {
        GraphVersionStore repository = new GraphVersionStore();
        GraphWriteTransaction write = repository.beginWrite("main");
        write.addNode("Person", Map.of("name", "Control Alice"));
        String commitId = write.commit("test", "control server").getId();

        GraphControlServer server = new GraphControlServer(0, repository);
        server.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();

            HttpResponse<String> health = get(client, base + "/health");
            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("\"status\":\"UP\""));

            HttpResponse<String> branches = get(client, base + "/meta/branches");
            assertEquals(200, branches.statusCode());
            assertTrue(branches.body().contains("main"));

            String cypher = URLEncoder.encode(
                    "MATCH (n:Person) RETURN n.name AS name", StandardCharsets.UTF_8);
            HttpResponse<String> query = get(client, base + "/query?commit=" + commitId + "&cypher=" + cypher);
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
        write.addNode("Person", Map.of("name", "Gate Alice"));
        String realCommit = write.commit("test", "gate status codes").getId();

        GraphControlServer server = new GraphControlServer(0, repository);
        server.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            String cypher = URLEncoder.encode(
                    "MATCH (n:Person) RETURN n.name AS name", StandardCharsets.UTF_8);

            assertEquals(200, get(client, base + "/query?commit=" + realCommit + "&cypher=" + cypher).statusCode());
            assertEquals(200, get(client, base + "/query?branch=main&cypher=" + cypher).statusCode());
            assertEquals(200, get(client, base + "/meta/schema?branch=main").statusCode());

            HttpResponse<String> unknownCommit = get(client, base + "/query?commit=deadbeef&cypher=" + cypher);
            assertEquals(404, unknownCommit.statusCode(), unknownCommit.body());
            assertTrue(unknownCommit.body().contains("Unknown commit=deadbeef"), unknownCommit.body());

            assertEquals(404, get(client, base + "/query?branch=nope&cypher=" + cypher).statusCode());
            assertEquals(404, get(client, base + "/meta/schema?branch=nope").statusCode());
            assertEquals(404, get(client, base + "/meta/export?branch=nope").statusCode());

            HttpResponse<String> badBranchName = get(client, base + "/query?branch="
                    + URLEncoder.encode("bad name!", StandardCharsets.UTF_8) + "&cypher=" + cypher);
            assertEquals(400, badBranchName.statusCode(), badBranchName.body());
        } finally {
            server.stop();
        }
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
