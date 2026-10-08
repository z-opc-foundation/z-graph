package com.zifang.z.graph.core;

import com.zifang.z.graph.api.Colls;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 防物化回潮的结构闸：GraphVersionStore 的对象图里不允许出现任何「整图状态」
 * ——InMemoryGraphStore 实例、物化视图集合。物化必须只发生在冷路径
 * （export/import/merge 装配的局部方法内），仓库字段层面永远只有引擎句柄、
 * commit 索引和 ref 存储。
 */
class NoMirrorTest {

    /** 对象图一阶可达的 GraphStore 实现实例，全部不得是 InMemoryGraphStore。 */
    @Test
    void repositoryHoldsNoMaterializedGraphState() throws IllegalAccessException {
        GraphVersionStore repository = new GraphVersionStore();
        GraphWriteTransaction write = repository.beginWrite("main");
        write.addNode("Person", Colls.mapOf("name", "Alice"));
        write.commit("t", "make some state");

        Set<Object> visited = new HashSet<>();
        Deque<Object> queue = new ArrayDeque<>();
        queue.add(repository);
        while (!queue.isEmpty()) {
            Object current = queue.poll();
            if (current == null || !visited.add(current)) continue;
            Class<?> type = current.getClass();
            while (type != null && type != Object.class) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    try {
                        field.setAccessible(true);
                    } catch (Exception inaccessible) {
                        // JDK17 模块封闭下个别 JVM 内部字段不可达；跳过即可，主断言面在 GVS 自有字段。
                        continue;
                    }
                    Object value = field.get(current);
                    if (value == null) continue;
                    if (value instanceof InMemoryGraphStore) {
                        throw new AssertionError("GraphVersionStore field "
                                + type.getSimpleName() + "." + field.getName()
                                + " holds an InMemoryGraphStore — 物化回潮");
                    }
                    // 集合元素也要查：List<InMemoryGraphStore> / Map<.., InMemoryGraphStore> 同样算物化。
                    if (value instanceof java.util.Collection) {
                        for (Object element : (java.util.Collection<?>) value) {
                            if (element instanceof InMemoryGraphStore) {
                                throw new AssertionError("GraphVersionStore field "
                                        + type.getSimpleName() + "." + field.getName()
                                        + " collection holds an InMemoryGraphStore — 物化回潮");
                            }
                            if (element != null && !element.getClass().getName().startsWith("java.")
                                    && visited.size() < 256) {
                                queue.add(element);
                            }
                        }
                        continue;
                    }
                    if (value instanceof java.util.Map) {
                        for (Object element : ((java.util.Map<?, ?>) value).values()) {
                            if (element instanceof InMemoryGraphStore) {
                                throw new AssertionError("GraphVersionStore field "
                                        + type.getSimpleName() + "." + field.getName()
                                        + " map holds an InMemoryGraphStore — 物化回潮");
                            }
                            if (element != null && !element.getClass().getName().startsWith("java.")
                                    && visited.size() < 256) {
                                queue.add(element);
                            }
                        }
                        continue;
                    }
                    if (value.getClass().getName().startsWith("java.")
                            || value.getClass().isPrimitive()
                            || value instanceof String
                            || value instanceof Number
                            || value instanceof Boolean
                            || value instanceof Path) {
                        continue;
                    }
                    if (visited.size() < 256) {
                        queue.add(value);
                    }
                }
                type = type.getSuperclass();
            }
        }
        assertTrue(visited.size() > 3, "对象图遍历不能是空转（要真的走进仓库内部）");
    }
}
