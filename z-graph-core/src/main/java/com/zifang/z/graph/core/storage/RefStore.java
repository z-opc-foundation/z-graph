package com.zifang.z.graph.core.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * refs 存储：{@code refs/heads/<branch>}（一个分支一个纯文本文件，内容是 40 位
 * commit id）+ {@code HEAD}（{@code ref: refs/heads/<default>}）。移动 ref =
 * 临时文件 + 原子替换，是 commit 的提交点。
 */
public final class RefStore {

    private static final String REF_PREFIX = "ref: refs/heads/";

    private final Path refsDir;
    private final Path headFile;

    public RefStore(Path dataDir) {
        this.refsDir = dataDir.resolve("refs").resolve("heads");
        this.headFile = dataDir.resolve("HEAD");
    }

    public synchronized void init(String defaultBranch) throws IOException {
        Files.createDirectories(refsDir);
        if (!Files.exists(headFile)) {
            writeHead(defaultBranch);
        }
    }

    public synchronized String headRef() throws IOException {
        if (!Files.exists(headFile)) {
            return "main";
        }
        String line = new String(Files.readAllBytes(headFile), StandardCharsets.UTF_8).trim();
        return line.startsWith(REF_PREFIX) ? line.substring(REF_PREFIX.length()) : line;
    }

    public synchronized void writeHead(String branch) throws IOException {
        atomicWrite(headFile, REF_PREFIX + branch + "\n");
    }

    public synchronized void put(String branch, String commitId) throws IOException {
        Path file = refFile(branch);
        Files.createDirectories(file.getParent());
        atomicWrite(file, commitId + "\n");
    }

    public synchronized String get(String branch) throws IOException {
        Path file = refFile(branch);
        if (!Files.exists(file)) {
            return null;
        }
        String value = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
        return value.isEmpty() ? null : value;
    }

    public synchronized boolean remove(String branch) throws IOException {
        return Files.deleteIfExists(refFile(branch));
    }

    /** 全部分支名及其指向的 commit id，按分支名排序。 */
    public synchronized Map<String, String> allRefs() throws IOException {
        Map<String, String> result = new TreeMap<>();
        collect(refsDir, "", result);
        return result;
    }

    public synchronized List<String> branches() throws IOException {
        return new ArrayList<>(allRefs().keySet());
    }

    private void collect(Path dir, String prefix, Map<String, String> result) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> children = Files.list(dir)) {
            List<Path> sorted = new ArrayList<>();
            children.sorted(java.util.Comparator.comparing(path -> path.getFileName().toString())).forEach(sorted::add);
            for (Path child : sorted) {
                String name = prefix.isEmpty() ? child.getFileName().toString()
                        : prefix + "/" + child.getFileName().toString();
                if (Files.isDirectory(child)) {
                    collect(child, name, result);
                } else {
                    String value = new String(Files.readAllBytes(child), StandardCharsets.UTF_8).trim();
                    result.put(name, value);
                }
            }
        }
    }

    private Path refFile(String branch) {
        if (branch == null || branch.trim().isEmpty() || !branch.matches("[A-Za-z0-9._/-]+")) {
            throw new IllegalArgumentException("Invalid branch name: " + branch);
        }
        return refsDir.resolve(branch);
    }

    private static void atomicWrite(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.write(temp, content.getBytes(StandardCharsets.UTF_8));
        force(temp);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void force(Path file) throws IOException {
        java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ);
        try {
            ch.force(true);
        } finally {
            ch.close();
        }
    }
}
