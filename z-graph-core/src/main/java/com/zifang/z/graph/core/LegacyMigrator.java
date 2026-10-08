package com.zifang.z.graph.core;

import com.zifang.z.graph.core.storage.CommitObjectStore;
import com.zifang.z.graph.core.storage.RefStore;
import com.zifang.z.graph.core.storage.StorageEngine;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v4 → v5 一次性迁移。v4 仓库 = {@code repository.bin}（分支表）+
 * {@code objects/<commitId>.bin}（元数据 + delta），commit id 是应用层拼出来的；
 * v5 是内容哈希 id + 追加式版本链引擎。迁移 = 把 v4 的每个 commit delta 按
 * commitSequence 升序重放进新引擎，commit object 以新规范化字节重写（parents
 * 改写为新 id，旧 id 存进 legacyId 供反查）。
 *
 * <p>崩溃安全：引擎文件先落 {@code store.migrating/}，commit object 落
 * {@code commits/}，refs 落 {@code refs/}，最后一步才把 {@code store.migrating/}
 * 原子换名成 {@code store/}。中途任何崩溃 ⇒ 下次 open 看到「有 repository.bin
 * 而无 store/header.bin」→ 从 v4 源整体重来（内容寻址对象重写幂等）。v4 源归档
 * 成 {@code objects.v4.bak} / {@code repository.v4.bak}，不删。</p>
 */
final class LegacyMigrator {

    private LegacyMigrator() {
    }

    /**
     * 检测并执行迁移。返回 true 表示发生了迁移；目录已是 v5 或没有 v4 仓库时返回 false。
     *
     * @throws IOException v4 仓库损坏或格式不受支持
     */
    static boolean migrateIfNeeded(Path dataDir) throws IOException {
        Path store = dataDir.resolve("store");
        Path repositoryBin = dataDir.resolve("repository.bin");
        if (Files.exists(store.resolve("header.bin")) || !Files.exists(repositoryBin)) {
            return false;
        }
        V4Repository legacy = readV4Repository(dataDir);
        deleteRecursively(dataDir.resolve("store.migrating"));

        Map<String, String> idMap = new LinkedHashMap<>();
        CommitObjectStore commitStore = new CommitObjectStore(dataDir.resolve("commits"));
        Path migrating = dataDir.resolve("store.migrating");
        StorageEngine engine = StorageEngine.create(migrating);
        try {
            for (V4Commit commit : legacy.commits) {
                List<String> newParents = new ArrayList<>(commit.parents.size());
                for (String parent : commit.parents) {
                    String mapped = idMap.get(parent);
                    newParents.add(mapped != null ? mapped : parent);
                }
                byte[] canonical = CommitObjectStore.canonicalBytes(newParents, commit.branch,
                        commit.author, commit.message, commit.timestamp, commit.nodeCount, commit.edgeCount,
                        commit.commitSequence, commit.id, commit.delta);
                byte[] digest = CommitObjectStore.digestOf(canonical);
                engine.applyDelta(commit.commitSequence, Arrays.copyOf(digest, 16), commit.delta);
                commitStore.writeCanonical(canonical);
                idMap.put(commit.id, CommitObjectStore.hashOf(canonical));
            }
            // v4 头里的分配器水位必须保留，否则新分配的 id 可能撞上已删除实体的历史 id。
            engine.reserveNodeId(legacy.nextNodeId - 1);
            engine.reserveEdgeId(legacy.nextEdgeId - 1);
            engine.reserveCommitSeq(legacy.sequence);
            engine.persistAndForce();
        } finally {
            engine.close();
        }

        RefStore refStore = new RefStore(dataDir);
        for (Map.Entry<String, String> branch : legacy.branches.entrySet()) {
            String newHead = idMap.get(branch.getValue());
            refStore.put(branch.getKey(), newHead != null ? newHead : branch.getValue());
        }
        if (!legacy.branches.isEmpty()) {
            refStore.init(legacy.branches.containsKey("main") ? "main" : legacy.branches.keySet().iterator().next());
        }

        Files.move(migrating, store);
        // 归档旧布局（不删）：失败不影响迁移成立，只是留下死文件。
        try {
            Path objects = dataDir.resolve("objects");
            if (Files.exists(objects)) {
                Files.move(objects, dataDir.resolve("objects.v4.bak"),
                        StandardCopyOption.ATOMIC_MOVE);
            }
            Files.move(repositoryBin, dataDir.resolve("repository.v4.bak"),
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            // 非 atomic 兜底：部分文件系统不支持目录 ATOMIC_MOVE。
            try {
                Path objects = dataDir.resolve("objects");
                if (Files.exists(objects)) {
                    Files.move(objects, dataDir.resolve("objects.v4.bak"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                if (Files.exists(repositoryBin)) {
                    Files.move(repositoryBin, dataDir.resolve("repository.v4.bak"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ignoredAgain) {
                // 留下未归档的死文件无害：v5 open 只认 store/header.bin。
            }
        }
        return true;
    }

    // ==================== v4 仓库读取 ====================

    private static final class V4Repository {
        long sequence;
        long nextNodeId;
        long nextEdgeId;
        final Map<String, String> branches = new LinkedHashMap<>();
        final List<V4Commit> commits = new ArrayList<>();
    }

    private static final class V4Commit {
        final String id;
        final List<String> parents;
        final String branch;
        final String author;
        final String message;
        final long timestamp;
        final long nodeCount;
        final long edgeCount;
        final long commitSequence;
        final GraphDelta delta;

        V4Commit(String id, List<String> parents, String branch, String author, String message,
                 long timestamp, long nodeCount, long edgeCount, long commitSequence, GraphDelta delta) {
            this.id = id;
            this.parents = parents;
            this.branch = branch;
            this.author = author;
            this.message = message;
            this.timestamp = timestamp;
            this.nodeCount = nodeCount;
            this.edgeCount = edgeCount;
            this.commitSequence = commitSequence;
            this.delta = delta;
        }
    }

    private static V4Repository readV4Repository(Path dataDir) throws IOException {
        V4Repository repository = new V4Repository();
        List<String> commitIds = new ArrayList<>();
        Path repositoryBin = dataDir.resolve("repository.bin");
        try (InputStream input = Files.newInputStream(repositoryBin);
             DataInputStream in = new DataInputStream(input)) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != GraphCodec.STORAGE_MAGIC) {
                throw new IOException("Unsupported z-graph repository format: " + repositoryBin);
            }
            if (version < 4 || version > 4) {
                throw new IOException("Repository format " + version + " predates the delta layout "
                        + "(v4) and cannot be migrated; expected version 4: " + repositoryBin);
            }
            repository.sequence = in.readLong();
            repository.nextNodeId = in.readLong();
            repository.nextEdgeId = in.readLong();
            int branchCount = in.readInt();
            for (int i = 0; i < branchCount; i++) {
                String name = GraphCodec.readString(in);
                String head = GraphCodec.readString(in);
                repository.branches.put(name, head);
            }
            int commitCount = in.readInt();
            for (int i = 0; i < commitCount; i++) {
                commitIds.add(GraphCodec.readString(in));
            }
        }

        for (String commitId : commitIds) {
            Path object = dataDir.resolve("objects").resolve(commitId + ".bin");
            if (!Files.exists(object)) {
                throw new IOException("Missing v4 commit object: " + object);
            }
            repository.commits.add(readV4Commit(object));
        }
        repository.commits.sort(Comparator.comparingLong(commit -> commit.commitSequence));
        return repository;
    }

    private static V4Commit readV4Commit(Path object) throws IOException {
        try (InputStream input = Files.newInputStream(object);
             DataInputStream in = new DataInputStream(input)) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != GraphCodec.STORAGE_MAGIC || version != 4) {
                throw new IOException("Unexpected v4 commit object header: " + object);
            }
            String id = GraphCodec.readString(in);
            int parentCount = in.readInt();
            List<String> parents = new ArrayList<>(parentCount);
            for (int i = 0; i < parentCount; i++) {
                parents.add(GraphCodec.readString(in));
            }
            String branch = GraphCodec.readString(in);
            String author = GraphCodec.readString(in);
            String message = GraphCodec.readString(in);
            long timestamp = in.readLong();
            long nodeCount = in.readLong();
            long edgeCount = in.readLong();
            long commitSequence = in.readLong();
            GraphDelta delta = GraphDelta.readFrom(in);
            return new V4Commit(id, parents, branch, author, message, timestamp,
                    nodeCount, edgeCount, commitSequence, delta);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot delete " + path, e);
                }
            });
        }
    }
}
