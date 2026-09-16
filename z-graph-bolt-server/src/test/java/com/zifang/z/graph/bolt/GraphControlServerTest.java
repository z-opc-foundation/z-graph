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

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
