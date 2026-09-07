package com.zifang.z.graph.api;

import java.util.*;

/**
 * NebulaGraph 风格 Tag schema：声明节点的合法属性名、数据类型和是否可空。
 * <p>
 * 当 GraphStore 中存在 TagSchema 时，写入对应标签的节点会被校验：
 * <ul>
 *     <li>属性名必须在 schema 中声明；</li>
 *     <li>属性值类型必须匹配或可空；</li>
 *     <li>非 nullable 的属性必须提供。</li>
 * </ul>
 * 未声明 schema 的标签继续按自由属性写入，保持向后兼容。
 */
public class TagSchema {

    /** NebulaGraph 兼容的最小类型集合。 */
    public enum DataType {
        STRING, INT, BIGINT, DOUBLE, BOOL, DATE, DATETIME, NULL
    }

    public static class Field {
        private final String name;
        private final DataType type;
        private final boolean nullable;

        public Field(String name, DataType type, boolean nullable) {
            this.name = Objects.requireNonNull(name, "name");
            this.type = Objects.requireNonNull(type, "type");
            this.nullable = nullable;
        }

        public Field(String name, DataType type) {
            this(name, type, true);
        }

        public String getName() { return name; }
        public DataType getType() { return type; }
        public boolean isNullable() { return nullable; }

        @Override
        public String toString() {
            return name + " " + type.name() + (nullable ? "" : " NOT NULL");
        }
    }

    private final String name;
    private final List<Field> fields;

    public TagSchema(String name, List<Field> fields) {
        this.name = Objects.requireNonNull(name, "tag name");
        Set<String> seen = new LinkedHashSet<>();
        for (Field field : fields) {
            if (!seen.add(field.getName())) {
                throw new IllegalArgumentException("Duplicate field on tag " + name + ": " + field.getName());
            }
        }
        this.fields = List.copyOf(fields);
    }

    public TagSchema(String name) {
        this(name, List.of());
    }

    public String getName() { return name; }

    public List<Field> getFields() { return fields; }

    public Field getField(String name) {
        for (Field field : fields) {
            if (field.getName().equals(name)) return field;
        }
        return null;
    }

    public boolean hasField(String name) { return getField(name) != null; }

    /**
     * 校验一组属性是否符合 schema。未声明 schema 的标签应跳过本校验。
     *
     * @throws SchemaViolationException 当属性集不合法时抛出
     */
    public void validate(Map<String, Object> properties) {
        if (properties == null) properties = Map.of();
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String key = entry.getKey();
            Field field = getField(key);
            if (field == null) {
                throw new SchemaViolationException(
                        "Tag " + name + " has no field '" + key + "'");
            }
            Object value = entry.getValue();
            if (value == null) {
                if (!field.isNullable()) {
                    throw new SchemaViolationException(
                            "Tag " + name + " field '" + key + "' is NOT NULL");
                }
                continue;
            }
            if (!typeMatches(value, field.getType())) {
                throw new SchemaViolationException(
                        "Tag " + name + " field '" + key + "' expects " + field.getType()
                                + " but got " + value.getClass().getSimpleName());
            }
        }
        for (Field field : fields) {
            if (!field.isNullable()
                    && (properties.get(field.getName()) == null)) {
                throw new SchemaViolationException(
                        "Tag " + name + " requires NOT NULL field '" + field.getName() + "'");
            }
        }
    }

    private static boolean typeMatches(Object value, DataType expected) {
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
        return "Tag " + name + "(" + fields + ")";
    }

    public static class SchemaViolationException extends RuntimeException {
        public SchemaViolationException(String message) { super(message); }
    }
}
