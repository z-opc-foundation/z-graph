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
        this.fields = Colls.copyOfList(fields);
    }

    public EdgeTypeSchema(String name) {
        this(name, Colls.listOf());
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

    /** 返回一个追加了新字段的新 schema（不可变）。 */
    public EdgeTypeSchema withAddedField(TagSchema.Field field) {
        if (hasField(field.getName())) {
            throw new IllegalArgumentException(
                    "EdgeType " + name + " already has field '" + field.getName() + "'");
        }
        List<TagSchema.Field> next = new ArrayList<>(fields);
        next.add(field);
        return new EdgeTypeSchema(name, next);
    }

    public void validate(Map<String, Object> properties) {
        if (properties == null) properties = Colls.mapOf();
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
        switch (expected) {
            case STRING:
                return value instanceof String;
            case INT:
                return value instanceof Integer;
            case BIGINT:
                return value instanceof Integer || value instanceof Long;
            case DOUBLE:
                return value instanceof Number;
            case BOOL:
                return value instanceof Boolean;
            case NULL:
                return value == null;
            case DATE:
            case DATETIME:
                return value instanceof String || value instanceof java.util.Date;
            default:
                throw new IllegalArgumentException("Unsupported data type: " + expected);
        }
    }

    @Override
    public String toString() {
        return "EdgeType " + name + "(" + fields + ")";
    }
}
