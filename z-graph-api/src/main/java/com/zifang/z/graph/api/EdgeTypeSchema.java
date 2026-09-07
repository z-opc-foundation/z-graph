package com.zifang.z.graph.api;

import java.util.*;

/**
 * NebulaGraph 风格 EdgeType schema：声明有向边类型的合法属性名、数据类型和是否可空。
 * 行为语义同 {@link TagSchema}：未声明 schema 的边类型继续按自由属性写入。
 */
public class EdgeTypeSchema {

    private final String name;
    private final List<TagSchema.Field> fields;

    public EdgeTypeSchema(String name, List<TagSchema.Field> fields) {
        this.name = Objects.requireNonNull(name, "edge type name");
        Set<String> seen = new LinkedHashSet<>();
        for (TagSchema.Field field : fields) {
            if (!seen.add(field.getName())) {
                throw new IllegalArgumentException("Duplicate field on edge type " + name + ": " + field.getName());
            }
        }
        this.fields = List.copyOf(fields);
    }

    public EdgeTypeSchema(String name) {
        this(name, List.of());
    }

    public String getName() { return name; }

    public List<TagSchema.Field> getFields() { return fields; }

    public TagSchema.Field getField(String name) {
        for (TagSchema.Field field : fields) {
            if (field.getName().equals(name)) return field;
        }
        return null;
    }

    public boolean hasField(String name) { return getField(name) != null; }

    public void validate(Map<String, Object> properties) {
        if (properties == null) properties = Map.of();
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String key = entry.getKey();
            TagSchema.Field field = getField(key);
            if (field == null) {
                throw new TagSchema.SchemaViolationException(
                        "EdgeType " + name + " has no field '" + key + "'");
            }
            Object value = entry.getValue();
            if (value == null) {
                if (!field.isNullable()) {
                    throw new TagSchema.SchemaViolationException(
                            "EdgeType " + name + " field '" + key + "' is NOT NULL");
                }
                continue;
            }
            if (!typeMatches(value, field.getType())) {
                throw new TagSchema.SchemaViolationException(
                        "EdgeType " + name + " field '" + key + "' expects " + field.getType()
                                + " but got " + value.getClass().getSimpleName());
            }
        }
        for (TagSchema.Field field : fields) {
            if (!field.isNullable()
                    && (properties.get(field.getName()) == null)) {
                throw new TagSchema.SchemaViolationException(
                        "EdgeType " + name + " requires NOT NULL field '" + field.getName() + "'");
            }
        }
    }

    private static boolean typeMatches(Object value, TagSchema.DataType expected) {
        return switch (expected) {
            case STRING -> value instanceof String;
            case INT -> value instanceof Integer;
            case BIGINT -> value instanceof Integer || value instanceof Long;
            case DOUBLE -> value instanceof Number;
            case BOOL -> value instanceof Boolean;
            case NULL -> value == null;
            case DATE, DATETIME -> value instanceof String || value instanceof java.util.Date;
        };
    }

    @Override
    public String toString() {
        return "EdgeType " + name + "(" + fields + ")";
    }
}
