package com.zifang.z.graph.core.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ref 祖先闭包索引：一个 commit 的可见性 = 「其 commitSeq 落在该 ref 的祖先
 * 闭包内」。闭包按 commit generation 升序增量构建（closure = 双亲闭包的并集 +
 * 自身），commit 不可变所以闭包永不失效，只有 GC 整表作废。
 *
 * <p>merge 双父天然支持：闭包沿全部 parents 递归。</p>
 */
public final class AncestryIndex {

    /** commit 图的不可变视图：id → 元数据（parents + commitSeq + generation）。 */
    public interface CommitGraph {

        List<String> parentsOf(String commitId);

        long commitSeqOf(String commitId);
    }

    private final CommitGraph graph;
    private final Map<String, Set<Long>> closures = new HashMap<>();

    public AncestryIndex(CommitGraph graph) {
        this.graph = graph;
    }

    /** 指定 commit 的祖先闭包（含自身），懒建 + 记忆化。 */
    public synchronized Set<Long> closureOf(String commitId) {
        Set<Long> cached = closures.get(commitId);
        if (cached != null) {
            return cached;
        }
        Map<String, Set<Long>> computed = new LinkedHashMap<>();
        closureDfs(commitId, computed, new HashSet<String>());
        closures.putAll(computed);
        Set<Long> closure = computed.get(commitId);
        return closure == null ? new HashSet<Long>() : closure;
    }

    /** 指定 commit 的 Visibility。 */
    public Visibility visibilityOf(final String commitId) {
        return new Visibility() {
            @Override
            public boolean isVisible(long commitSeq) {
                return closureOf(commitId).contains(commitSeq);
            }
        };
    }

    private void closureDfs(String commitId, Map<String, Set<Long>> computed, Set<String> inProgress) {
        if (computed.containsKey(commitId) || !inProgress.add(commitId)) {
            return;
        }
        List<String> parents = graph.parentsOf(commitId);
        for (String parent : parents) {
            closureDfs(parent, computed, inProgress);
        }
        Set<Long> closure = new HashSet<>();
        closure.add(graph.commitSeqOf(commitId));
        for (String parent : parents) {
            Set<Long> parentClosure = computed.get(parent);
            if (parentClosure != null) {
                closure.addAll(parentClosure);
            }
        }
        computed.put(commitId, closure);
        inProgress.remove(commitId);
    }

    /** GC 后整表作废（不可达 commit 已删，闭包基座变了）。 */
    public synchronized void invalidateAll() {
        closures.clear();
    }

    /** 已缓存的闭包份数，观测用。 */
    public synchronized int cachedClosureCount() {
        return closures.size();
    }

    /** 两个 commit 的最近公共祖先：沿 generation 最深的双指针同步上溯。 */
    public static String lowestCommonAncestor(CommitGraph graph,
                                              java.util.function.Function<String, Integer> generationOf,
                                              String a,
                                              String b) {
        List<String> lineageA = lineageToRoot(graph, a);
        List<String> lineageB = lineageToRoot(graph, b);
        Set<String> setB = new HashSet<>(lineageB);
        for (String candidate : lineageA) {
            if (setB.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 沿第一父链到根的祖先序（自身在前）。 */
    public static List<String> lineageToRoot(CommitGraph graph, String start) {
        List<String> lineage = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cursor = start;
        while (cursor != null && seen.add(cursor)) {
            lineage.add(cursor);
            List<String> parents = graph.parentsOf(cursor);
            cursor = parents.isEmpty() ? null : parents.get(0);
        }
        return lineage;
    }
}
