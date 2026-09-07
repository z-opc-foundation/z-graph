package com.zifang.z.graph.bolt;

import com.zifang.z.graph.core.GraphVersionStore;
import com.zifang.z.graph.core.GraphWriteTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端测试：通过原生 Bolt 4.4 socket 协议与服务端交互，
 * 覆盖 HELLO / RUN / PULL / BEGIN / COMMIT / ROLLBACK / GOODBYE。
 */
class BoltServerE2ETest {

    private BoltServer server;
    private GraphVersionStore repository;

    @BeforeEach
    void setUp() throws Exception {
        repository = new GraphVersionStore();
        // 预置数据：通过事务写入 Person 节点，验证连接可以直接读到 main head
        GraphWriteTransaction tx = repository.beginWrite("main");
        tx.addNode("Person", Map.of("name", "E2E Alice", "age", 30));
        tx.commit("seed", "seed");

        server = new BoltServer(0, repository);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.shutdown();
    }

    @Test
    void helloReturnsServerMetadata() throws Exception {
        try (BoltTestClient client = new BoltTestClient("127.0.0.1", server.port())) {
            Map<String, Object> meta = client.hello();
            assertEquals("z-graph/1.0.0-SNAPSHOT", meta.get("server"));
            assertEquals("community", meta.get("edition"));
            assertNotNull(meta.get("connection_id"));
        }
    }

    @Test
    void runReturnAndPullRows() throws Exception {
        try (BoltTestClient client = new BoltTestClient("127.0.0.1", server.port())) {
            client.hello();
            Map<String, Object> meta = client.run("MATCH (n:Person) RETURN n.name AS name, n.age AS age");
            assertNotNull(meta.get("qid"));
            assertNotNull(meta.get("fields"));
            long qid = client.lastQid(meta);
            List<String> fields = client.lastFields(meta);
            List<Map<String, Object>> rows = client.pull(qid, -1, fields);
            assertEquals(1, rows.size());
            assertEquals("E2E Alice", rows.get(0).get("name"));
            assertEquals(30, ((Number) rows.get(0).get("age")).intValue());
            client.goodbye();
        }
    }

    @Test
    void runWithParametersExpandsDollarVariables() throws Exception {
        try (BoltTestClient client = new BoltTestClient("127.0.0.1", server.port())) {
            client.hello();
            Map<String, Object> meta = client.run("MATCH (n:Person) WHERE n.name = $who RETURN n.age AS age",
                    Map.of("who", "E2E Alice"));
            long qid = client.lastQid(meta);
            List<String> fields = client.lastFields(meta);
            List<Map<String, Object>> rows = client.pull(qid, -1, fields);
            assertEquals(1, rows.size());
            assertEquals(30, ((Number) rows.get(0).get("age")).intValue());
        }
    }

    @Test
    void beginRunCommitRoundTrip() throws Exception {
        try (BoltTestClient client = new BoltTestClient("127.0.0.1", server.port())) {
            client.hello();
            client.begin();
            Map<String, Object> meta = client.run("CREATE (n:Person {name: 'Tx Bob', age: 25})");
            client.pull(client.lastQid(meta), -1, client.lastFields(meta));
            String commitId = client.commit();
            assertNotNull(commitId);
            assertFalse(commitId.isBlank());
            // 提交后用新连接可以看到新节点（main head 已推进）
            try (BoltTestClient second = new BoltTestClient("127.0.0.1", server.port())) {
                second.hello();
                Map<String, Object> runMeta = second.run(
                        "MATCH (n:Person) WHERE n.name = 'Tx Bob' RETURN n.age AS age");
                List<String> fields = second.lastFields(runMeta);
                List<Map<String, Object>> rows = second.pull(second.lastQid(runMeta), -1, fields);
                assertEquals(1, rows.size());
                assertEquals(25, ((Number) rows.get(0).get("age")).intValue());
            }
        }
    }

    @Test
    void unsupportedCypherReturnsFailure() throws Exception {
        try (BoltTestClient client = new BoltTestClient("127.0.0.1", server.port())) {
            client.hello();
            // 服务端对未知存储过程会发 FAILURE,客户端 run() 直接抛 IOException
            assertThrows(java.io.IOException.class, () -> {
                client.run("CALL db.this_procedure_does_not_exist()");
            });
        }
    }

    @Test
    void callDbVersionReturnsVersionRow() throws Exception {
        try (BoltTestClient client = new BoltTestClient("127.0.0.1", server.port())) {
            client.hello();
            Map<String, Object> meta = client.run("CALL db.version()");
            List<String> fields = client.lastFields(meta);
            List<Map<String, Object>> rows = client.pull(client.lastQid(meta), -1, fields);
            assertEquals(1, rows.size());
            assertEquals("z-graph-1.0.0", rows.get(0).get("version"));
            assertEquals("in-memory MVP", rows.get(0).get("build"));
        }
    }

    @Test
    void callDbBranchesAndCommitsExposeVersionMetadata() throws Exception {
        try (BoltTestClient client = new BoltTestClient("127.0.0.1", server.port())) {
            client.hello();

            Map<String, Object> branchMeta = client.run("CALL db.branches()");
            List<String> branchFields = client.lastFields(branchMeta);
            List<Map<String, Object>> branchRows = client.pull(client.lastQid(branchMeta), -1, branchFields);
            assertEquals(1, branchRows.size());
            assertEquals("main", branchRows.get(0).get("Name"));
            assertNotNull(branchRows.get(0).get("Head"));

            Map<String, Object> commitMeta = client.run("CALL db.commits()");
            List<String> commitFields = client.lastFields(commitMeta);
            List<Map<String, Object>> commitRows = client.pull(client.lastQid(commitMeta), -1, commitFields);
            // 预置 seed commit + 初始化空 commit = 2
            assertEquals(2, commitRows.size());
            // 找到 seed commit 行
            Map<String, Object> seedRow = commitRows.stream()
                    .filter(row -> "seed".equals(row.get("Message")))
                    .findFirst()
                    .orElseThrow();
            assertEquals("main", seedRow.get("Branch"));
            assertEquals(1, ((Number) seedRow.get("Nodes")).intValue());

            Map<String, Object> headMeta = client.run("CALL db.head('main')");
            List<String> headFields = client.lastFields(headMeta);
            List<Map<String, Object>> headRows = client.pull(client.lastQid(headMeta), -1, headFields);
            assertEquals(1, headRows.size());
            assertEquals("main", headRows.get(0).get("Branch"));
            assertEquals(1, ((Number) headRows.get(0).get("Nodes")).intValue());
        }
    }

    @Test
    void callDbStatsReportsNodeAndEdgeCounts() throws Exception {
        try (BoltTestClient client = new BoltTestClient("127.0.0.1", server.port())) {
            client.hello();
            Map<String, Object> meta = client.run("CALL db.stats()");
            List<String> fields = client.lastFields(meta);
            List<Map<String, Object>> rows = client.pull(client.lastQid(meta), -1, fields);
            assertEquals(1, rows.size());
            Map<String, Object> stats = rows.get(0);
            assertEquals(1, ((Number) stats.get("nodeCount")).intValue());
            assertEquals(0, ((Number) stats.get("edgeCount")).intValue());
        }
    }

    @Test
    void reverseEdgeMatchFollowsIncomingDirection() throws Exception {
        // 通过 Cypher 写入 Person-Friend->Person 的边,然后反向 MATCH
        try (BoltTestClient writer = new BoltTestClient("127.0.0.1", server.port())) {
            writer.hello();
            writer.begin();
            Map<String, Object> meta = writer.run(
                    "CREATE (a:Person {name: 'Anna'}), (b:Person {name: 'Bert'}), (a)-[:KNOWS]->(b)");
            writer.pull(writer.lastQid(meta), -1, writer.lastFields(meta));
            writer.commit();
        }
        try (BoltTestClient reader = new BoltTestClient("127.0.0.1", server.port())) {
            reader.hello();
            // 反向 MATCH (b)<-[:KNOWS]-(a) — Bert 是被 Anna 指向的,反向应查回 Anna
            Map<String, Object> meta = reader.run(
                    "MATCH (b:Person)<-[:KNOWS]-(a:Person) "
                            + "WHERE b.name = 'Bert' RETURN a.name AS friendOf");
            List<String> fields = reader.lastFields(meta);
            List<Map<String, Object>> rows = reader.pull(reader.lastQid(meta), -1, fields);
            assertEquals(1, rows.size());
            assertEquals("Anna", rows.get(0).get("friendOf"));
        }
    }

    @Test
    void variableLengthPathReturnsTransitiveFriends() throws Exception {
        try (BoltTestClient writer = new BoltTestClient("127.0.0.1", server.port())) {
            writer.hello();
            writer.begin();
            // 三人成链: Anna -> Bert -> Carol
            Map<String, Object> meta = writer.run(
                    "CREATE (a:Person {name: 'Anna'}), (b:Person {name: 'Bert'}),"
                            + " (c:Person {name: 'Carol'}),"
                            + " (a)-[:KNOWS]->(b), (b)-[:KNOWS]->(c)");
            writer.pull(writer.lastQid(meta), -1, writer.lastFields(meta));
            writer.commit();
        }
        try (BoltTestClient reader = new BoltTestClient("127.0.0.1", server.port())) {
            reader.hello();
            // 变长路径: Anna -[:KNOWS*1..2]-> friend
            Map<String, Object> meta = reader.run(
                    "MATCH (a:Person {name: 'Anna'})-[:KNOWS*1..2]->(friend:Person) "
                            + "RETURN friend.name AS name");
            List<String> fields = reader.lastFields(meta);
            List<Map<String, Object>> rows = reader.pull(reader.lastQid(meta), -1, fields);
            assertEquals(2, rows.size());
            java.util.Set<String> names = new java.util.HashSet<>();
            for (Map<String, Object> row : rows) names.add((String) row.get("name"));
            assertTrue(names.contains("Bert"));
            assertTrue(names.contains("Carol"));
        }
    }

    @Test
    void aggregationGroupsByBranch() throws Exception {
        try (BoltTestClient client = new BoltTestClient("127.0.0.1", server.port())) {
            client.hello();
            // 先扩一些数据
            client.begin();
            Map<String, Object> meta = client.run(
                    "CREATE (n:Person {name: 'GroupA', dept: 'eng'})");
            client.pull(client.lastQid(meta), -1, client.lastFields(meta));
            client.commit();

            Map<String, Object> aggMeta = client.run(
                    "MATCH (n:Person) RETURN n.dept AS dept, count(n) AS total");
            List<String> fields = client.lastFields(aggMeta);
            List<Map<String, Object>> rows = client.pull(client.lastQid(aggMeta), -1, fields);
            // 当前只有 GroupA 是 dept=eng,原 seed 没 dept
            assertTrue(rows.size() >= 1);
            boolean foundEng = rows.stream().anyMatch(r -> "eng".equals(r.get("dept"))
                    && ((Number) r.get("total")).intValue() == 1);
            assertTrue(foundEng, "expected aggregated row for dept=eng total=1, got " + rows);
        }
    }
}
