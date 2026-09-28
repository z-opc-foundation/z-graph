package com.zifang.z.graph.api;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code List.of / Map.of / Set.of / List.copyOf}（JDK 9/10 API）的 Java 8 等价实现。
 *
 * <p>语义刻意与 JDK 9 工厂保持一致，而不是 {@code Arrays.asList} 那种宽松替代：返回的集合
 * 真不可变、元素与键值拒绝 {@code null}、{@code Set}/{@code Map} 拒绝重复。若退化成
 * {@code Arrays.asList}，调用方对返回集合 {@code add}/{@code set} 的行为会从
 * {@code UnsupportedOperationException} 变成静默改写共享数组，这类差异在图引擎的
 * 视图与 MVCC 快照路径上是不可接受的。
 *
 * <p>遍历顺序用插入序（{@code LinkedHash*}）而非 JDK 工厂的"未指定顺序"——未指定顺序的
 * 超集，任何不依赖乱序的代码都不受影响。
 */
public final class Colls {

    private Colls() {
    }

    @SafeVarargs
    public static <T> List<T> listOf(T... elements) {
        Objects.requireNonNull(elements, "elements");
        java.util.ArrayList<T> copy = new java.util.ArrayList<T>();
        for (T element : elements) {
            Objects.requireNonNull(element, "element");
            copy.add(element);
        }
        return Collections.unmodifiableList(copy);
    }

    @SafeVarargs
    public static <T> Set<T> setOf(T... elements) {
        Objects.requireNonNull(elements, "elements");
        Set<T> set = new LinkedHashSet<>();
        for (T element : elements) {
            Objects.requireNonNull(element, "element");
            if (!set.add(element)) {
                throw new IllegalArgumentException("duplicate element: " + element);
            }
        }
        return Collections.unmodifiableSet(set);
    }

    public static <K, V> Map<K, V> mapOf() {
        return Collections.emptyMap();
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1) {
        LinkedHashMap<K, V> map = new LinkedHashMap<>();
        put(map, k1, v1);
        return Collections.unmodifiableMap(map);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2) {
        LinkedHashMap<K, V> map = new LinkedHashMap<>();
        put(map, k1, v1);
        put(map, k2, v2);
        return Collections.unmodifiableMap(map);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3) {
        LinkedHashMap<K, V> map = new LinkedHashMap<>();
        put(map, k1, v1);
        put(map, k2, v2);
        put(map, k3, v3);
        return Collections.unmodifiableMap(map);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) {
        LinkedHashMap<K, V> map = new LinkedHashMap<>();
        put(map, k1, v1);
        put(map, k2, v2);
        put(map, k3, v3);
        put(map, k4, v4);
        return Collections.unmodifiableMap(map);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4,
                                         K k5, V v5) {
        LinkedHashMap<K, V> map = new LinkedHashMap<>();
        put(map, k1, v1);
        put(map, k2, v2);
        put(map, k3, v3);
        put(map, k4, v4);
        put(map, k5, v5);
        return Collections.unmodifiableMap(map);
    }

    public static <T> List<T> copyOfList(Collection<? extends T> source) {
        Objects.requireNonNull(source, "source");
        List<T> copy = new java.util.ArrayList<>(source);
        for (T element : copy) {
            Objects.requireNonNull(element, "element");
        }
        return Collections.unmodifiableList(copy);
    }

    public static <T> Set<T> copyOfSet(Collection<? extends T> source) {
        Objects.requireNonNull(source, "source");
        Set<T> set = new LinkedHashSet<>();
        for (T element : source) {
            Objects.requireNonNull(element, "element");
            set.add(element);
        }
        return Collections.unmodifiableSet(set);
    }

    /**
     * {@code Stream.toList()}（JDK 16）的替代 Collector。保留它"允许 null 但返回不可变
     * 列表"的组合语义——直接换成 {@code Collectors.toList()} 会静默把不可变退化成可变。
     */
    public static <T> java.util.stream.Collector<T, ?, List<T>> toUnmodifiableList() {
        return java.util.stream.Collectors.collectingAndThen(
                java.util.stream.Collectors.toList(), Collections::unmodifiableList);
    }

    private static <K, V> void put(Map<K, V> map, K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (map.containsKey(key)) {
            throw new IllegalArgumentException("duplicate key: " + key);
        }
        map.put(key, value);
    }
}
