package com.zifang.z.graph.core;

import com.zifang.z.graph.api.Edge;
import com.zifang.z.graph.api.EdgeTypeSchema;
import com.zifang.z.graph.api.Node;
import com.zifang.z.graph.api.TagSchema;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一个 commit 相对其第一父提交的净变更量 —— MVCC 存储的最小单元。
 *
 * <p>版本仓库不再为每个 commit 保存整图副本，只保存这条增量；某个 commit 的视图由
 * 最近的检查点视图回放一串增量物化得到。因此写入成本是 O(变更量)，与图的大小无关。</p>
 *
 * <p>事务执行期间它是可变的暂存区，提交后通过 {@link #freeze()} 变成只读记录。
 * 同一实体反复修改只保留最终态，符合"节点多版本"里每个 commit 一个版本的粒度。</p>
 */
public final class GraphDelta {

    /** 属性索引定义键。 */
    public record IndexKey(String label, String propertyKey) {
    }

    private final LinkedHashMap<Long, Node> nodeUpserts = new LinkedHashMap<>();
    private final LinkedHashSet<Long> nodeDeletes = new LinkedHashSet<>();
    private final LinkedHashMap<Long, Edge> edgeUpserts = new LinkedHashMap<>();
    private final LinkedHashSet<Long> edgeDeletes = new LinkedHashSet<>();
    private final LinkedHashMap<String, TagSchema> tagUpserts = new LinkedHashMap<>();
    private final LinkedHashSet<String> tagDeletes = new LinkedHashSet<>();
    private final LinkedHashMap<String, EdgeTypeSchema> edgeTypeUpserts = new LinkedHashMap<>();
    private final LinkedHashSet<String> edgeTypeDeletes = new LinkedHashSet<>();
    private final LinkedHashSet<IndexKey> indexUpserts = new LinkedHashSet<>();
    private final LinkedHashSet<IndexKey> indexDeletes = new LinkedHashSet<>();
    private boolean frozen;

    public GraphDelta() {
    }

    /** 冻结后返回自身，作为不可变提交记录使用；冻结后任何写方法都会抛异常。 */
    public GraphDelta freeze() {
        frozen = true;
        return this;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public boolean isEmpty() {
        return nodeUpserts.isEmpty() && nodeDeletes.isEmpty()
                && edgeUpserts.isEmpty() && edgeDeletes.isEmpty()
                && tagUpserts.isEmpty() && tagDeletes.isEmpty()
                && edgeTypeUpserts.isEmpty() && edgeTypeDeletes.isEmpty()
                && indexUpserts.isEmpty() && indexDeletes.isEmpty();
    }

    // ==================== 记录变更 ====================

    public GraphDelta putNode(Node node) {
        ensureMutable();
        nodeDeletes.remove(node.getId());
        nodeUpserts.put(node.getId(), node);
        return this;
    }

    public GraphDelta deleteNode(long id) {
        ensureMutable();
        nodeUpserts.remove(id);
        nodeDeletes.add(id);
        return this;
    }

    public GraphDelta putEdge(Edge edge) {
        ensureMutable();
        edgeDeletes.remove(edge.getId());
        edgeUpserts.put(edge.getId(), edge);
        return this;
    }

    public GraphDelta deleteEdge(long id) {
        ensureMutable();
        edgeUpserts.remove(id);
        edgeDeletes.add(id);
        return this;
    }

    public GraphDelta putTag(TagSchema schema) {
        ensureMutable();
        tagDeletes.remove(schema.getName());
        tagUpserts.put(schema.getName(), schema);
        return this;
    }

    public GraphDelta deleteTag(String name) {
        ensureMutable();
        tagUpserts.remove(name);
        tagDeletes.add(name);
        return this;
    }

    public GraphDelta putEdgeType(EdgeTypeSchema schema) {
        ensureMutable();
        edgeTypeDeletes.remove(schema.getName());
        edgeTypeUpserts.put(schema.getName(), schema);
        return this;
    }

    public GraphDelta deleteEdgeType(String name) {
        ensureMutable();
        edgeTypeUpserts.remove(name);
        edgeTypeDeletes.add(name);
        return this;
    }

    public GraphDelta putIndex(IndexKey key) {
        ensureMutable();
        indexDeletes.remove(key);
        indexUpserts.add(key);
        return this;
    }

    public GraphDelta deleteIndex(IndexKey key) {
        ensureMutable();
        indexUpserts.remove(key);
        indexDeletes.add(key);
        return this;
    }

    private void ensureMutable() {
        if (frozen) {
            throw new IllegalStateException("GraphDelta is already frozen");
        }
    }

    // ==================== 查询暂存状态 ====================

    public boolean hasNode(long id) { return nodeUpserts.containsKey(id); }

    public boolean hasEdge(long id) { return edgeUpserts.containsKey(id); }

    Node nodeUpsert(long id) { return nodeUpserts.get(id); }

    Edge edgeUpsert(long id) { return edgeUpserts.get(id); }

    TagSchema tagUpsert(String name) { return tagUpserts.get(name); }

    EdgeTypeSchema edgeTypeUpsert(String name) { return edgeTypeUpserts.get(name); }

    public Collection<Node> nodeUpserts() { return Collections.unmodifiableCollection(nodeUpserts.values()); }

    public Set<Long> nodeDeletes() { return Collections.unmodifiableSet(nodeDeletes); }

    public Collection<Edge> edgeUpserts() { return Collections.unmodifiableCollection(edgeUpserts.values()); }

    public Set<Long> edgeDeletes() { return Collections.unmodifiableSet(edgeDeletes); }

    public Collection<TagSchema> tagUpserts() { return Collections.unmodifiableCollection(tagUpserts.values()); }

    public Set<String> tagDeletes() { return Collections.unmodifiableSet(tagDeletes); }

    public Collection<EdgeTypeSchema> edgeTypeUpserts() {
        return Collections.unmodifiableCollection(edgeTypeUpserts.values());
    }

    public Set<String> edgeTypeDeletes() { return Collections.unmodifiableSet(edgeTypeDeletes); }

    public Set<IndexKey> indexUpserts() { return Collections.unmodifiableSet(indexUpserts); }

    public Set<IndexKey> indexDeletes() { return Collections.unmodifiableSet(indexDeletes); }

    /** 本增量触碰过的实体总数，用于版本链和统计。 */
    public int touchedEntityCount() {
        return nodeUpserts.size() + nodeDeletes.size()
                + edgeUpserts.size() + edgeDeletes.size();
    }

    /**
     * 物化后的节点/边净增减，供 commit 记账而无需回放整图。
     */
    long nodeCountDelta(InMemoryGraphStore base) {
        long delta = 0;
        for (long id : nodeUpserts.keySet()) {
            if (base.getNode(id) == null) delta++;
        }
        for (long id : nodeDeletes) {
            if (base.getNode(id) != null) delta--;
        }
        return delta;
    }

    long edgeCountDelta(InMemoryGraphStore base) {
        long delta = 0;
        for (long id : edgeUpserts.keySet()) {
            if (base.getEdge(id) == null) delta++;
        }
        for (long id : edgeDeletes) {
            if (base.getEdge(id) != null) delta--;
        }
        return delta;
    }

    // ==================== 差异计算 ====================

    /**
     * 逐实体对比两个已物化的视图，产出从 base 到 updated 的增量。
     * 只用于旧版全量快照仓库的迁移和测试断言，正常写路径由事务暂存区直接给出增量。
     */
    static GraphDelta between(InMemoryGraphStore base, InMemoryGraphStore updated) {
        GraphDelta delta = new GraphDelta();
        for (Map.Entry<Long, Node> entry : updated.nodeMap().entrySet()) {
            Node before = base.getNode(entry.getKey());
            Node after = entry.getValue();
            if (before == null || !sameNode(before, after)) {
                delta.putNode(copyNode(after));
            }
        }
        for (long id : base.nodeMap().keySet()) {
            if (updated.getNode(id) == null) {
                delta.deleteNode(id);
            }
        }
        for (Map.Entry<Long, Edge> entry : updated.edgeMap().entrySet()) {
            Edge before = base.getEdge(entry.getKey());
            Edge after = entry.getValue();
            if (before == null || !sameEdge(before, after)) {
                delta.putEdge(copyEdge(after));
            }
        }
        for (long id : base.edgeMap().keySet()) {
            if (updated.getEdge(id) == null) {
                delta.deleteEdge(id);
            }
        }
        for (Map.Entry<String, TagSchema> entry : updated.tagSchemaView().entrySet()) {
            if (!sameTag(base.getTagSchema(entry.getKey()), entry.getValue())) {
                delta.putTag(entry.getValue());
            }
        }
        for (String name : base.tagSchemaView().keySet()) {
            if (updated.getTagSchema(name) == null) {
                delta.deleteTag(name);
            }
        }
        for (Map.Entry<String, EdgeTypeSchema> entry : updated.edgeTypeSchemaView().entrySet()) {
            if (!sameEdgeType(base.getEdgeTypeSchema(entry.getKey()), entry.getValue())) {
                delta.putEdgeType(entry.getValue());
            }
        }
        for (String name : base.edgeTypeSchemaView().keySet()) {
            if (updated.getEdgeTypeSchema(name) == null) {
                delta.deleteEdgeType(name);
            }
        }
        for (InMemoryGraphStore.IndexDefinition definition : updated.indexDefinitions()) {
            if (!base.indexDefinitions().contains(definition)) {
                delta.putIndex(new IndexKey(definition.label(), definition.propertyKey()));
            }
        }
        for (InMemoryGraphStore.IndexDefinition definition : base.indexDefinitions()) {
            if (!updated.indexDefinitions().contains(definition)) {
                delta.deleteIndex(new IndexKey(definition.label(), definition.propertyKey()));
            }
        }
        return delta;
    }

    static boolean sameNode(Node left, Node right) {
        return left == right
                || (left != null && right != null
                && Objects.equals(left.getLabels(), right.getLabels())
                && Objects.equals(left.getProperties(), right.getProperties()));
    }

    static boolean sameEdge(Edge left, Edge right) {
        return left == right
                || (left != null && right != null
                && Objects.equals(left.getType(), right.getType())
                && left.getStartNodeId() == right.getStartNodeId()
                && left.getEndNodeId() == right.getEndNodeId()
                && Objects.equals(left.getProperties(), right.getProperties()));
    }

    /**
     * TagSchema / EdgeTypeSchema 没有实现 equals，直接 Objects.equals 会把内容相同的
     * 两份 schema 判成不同，从而在每次回放里凭空多出一条 schema 变更。
     */
    static boolean sameTag(TagSchema left, TagSchema right) {
        if (left == right) return true;
        return left != null && right != null && sameFields(left.getName(), left.getFields(),
                right.getName(), right.getFields());
    }

    static boolean sameEdgeType(EdgeTypeSchema left, EdgeTypeSchema right) {
        if (left == right) return true;
        return left != null && right != null && sameFields(left.getName(), left.getFields(),
                right.getName(), right.getFields());
    }

    private static boolean sameFields(String leftName,
                                      List<TagSchema.Field> leftFields,
                                      String rightName,
                                      List<TagSchema.Field> rightFields) {
        if (!Objects.equals(leftName, rightName) || leftFields.size() != rightFields.size()) return false;
        for (int i = 0; i < leftFields.size(); i++) {
            TagSchema.Field mine = leftFields.get(i);
            TagSchema.Field other = rightFields.get(i);
            if (!Objects.equals(mine.getName(), other.getName())
                    || mine.getType() != other.getType()
                    || mine.isNullable() != other.isNullable()) {
                return false;
            }
        }
        return true;
    }

    static Node copyNode(Node node) {
        return new Node(node.getId(), node.getLabels(), InMemoryGraphStore.deepCopyMap(node.getProperties()));
    }

    static Edge copyEdge(Edge edge) {
        return new Edge(edge.getId(), edge.getType(), edge.getStartNodeId(), edge.getEndNodeId(),
                InMemoryGraphStore.deepCopyMap(edge.getProperties()));
    }

    // ==================== 编解码 ====================

    public void writeTo(DataOutputStream out) throws IOException {
        writeEntities(out);
    }

    void writeEntities(DataOutputStream out) throws IOException {
        out.writeInt(nodeUpserts.size());
        for (Node node : nodeUpserts.values()) {
            out.writeLong(node.getId());
            writeStringCollection(out, node.getLabels());
            GraphCodec.writeValue(out, node.getProperties());
        }
        writeLongSet(out, nodeDeletes);

        out.writeInt(edgeUpserts.size());
        for (Edge edge : edgeUpserts.values()) {
            out.writeLong(edge.getId());
            GraphCodec.writeString(out, edge.getType());
            out.writeLong(edge.getStartNodeId());
            out.writeLong(edge.getEndNodeId());
            GraphCodec.writeValue(out, edge.getProperties());
        }
        writeLongSet(out, edgeDeletes);

        writeSchemaFields(out, tagUpserts.size());
        for (TagSchema schema : tagUpserts.values()) writeTagSchema(out, schema);
        writeStringSet(out, tagDeletes);

        writeSchemaFields(out, edgeTypeUpserts.size());
        for (EdgeTypeSchema schema : edgeTypeUpserts.values()) writeEdgeTypeSchema(out, schema);
        writeStringSet(out, edgeTypeDeletes);

        out.writeInt(indexUpserts.size());
        for (IndexKey key : indexUpserts) {
            GraphCodec.writeString(out, key.label());
            GraphCodec.writeString(out, key.propertyKey());
        }
        out.writeInt(indexDeletes.size());
        for (IndexKey key : indexDeletes) {
            GraphCodec.writeString(out, key.label());
            GraphCodec.writeString(out, key.propertyKey());
        }
    }

    private static void writeSchemaFields(DataOutputStream out, int count) throws IOException {
        out.writeInt(count);
    }

    private static void writeTagSchema(DataOutputStream out, TagSchema schema) throws IOException {
        GraphCodec.writeString(out, schema.getName());
        out.writeInt(schema.getFields().size());
        for (TagSchema.Field field : schema.getFields()) {
            GraphCodec.writeString(out, field.getName());
            GraphCodec.writeString(out, field.getType().name());
            out.writeBoolean(field.isNullable());
        }
    }

    private static void writeEdgeTypeSchema(DataOutputStream out, EdgeTypeSchema schema) throws IOException {
        GraphCodec.writeString(out, schema.getName());
        out.writeInt(schema.getFields().size());
        for (TagSchema.Field field : schema.getFields()) {
            GraphCodec.writeString(out, field.getName());
            GraphCodec.writeString(out, field.getType().name());
            out.writeBoolean(field.isNullable());
        }
    }

    private static void writeLongSet(DataOutputStream out, Collection<Long> values) throws IOException {
        out.writeInt(values.size());
        for (Long value : values) out.writeLong(value);
    }

    private static void writeStringSet(DataOutputStream out, Collection<String> values) throws IOException {
        out.writeInt(values.size());
        for (String value : values) GraphCodec.writeString(out, value);
    }

    private static void writeStringCollection(DataOutputStream out, Collection<String> values) throws IOException {
        out.writeInt(values.size());
        for (String value : values) GraphCodec.writeString(out, value);
    }

    static GraphDelta readFrom(DataInputStream in) throws IOException {
        GraphDelta delta = new GraphDelta();
        int nodeUpsertCount = in.readInt();
        for (int i = 0; i < nodeUpsertCount; i++) {
            long id = in.readLong();
            List<String> labels = readStringList(in);
            delta.putNode(new Node(id, labels, GraphCodec.readMap(in)));
        }
        delta.nodeDeletes.addAll(readLongSet(in));

        int edgeUpsertCount = in.readInt();
        for (int i = 0; i < edgeUpsertCount; i++) {
            long id = in.readLong();
            String type = GraphCodec.readString(in);
            long start = in.readLong();
            long end = in.readLong();
            delta.putEdge(new Edge(id, type, start, end, GraphCodec.readMap(in)));
        }
        delta.edgeDeletes.addAll(readLongSet(in));

        int tagCount = in.readInt();
        for (int i = 0; i < tagCount; i++) {
            delta.putTag(readTagSchema(in));
        }
        delta.tagDeletes.addAll(readStringSet(in));

        int edgeTypeCount = in.readInt();
        for (int i = 0; i < edgeTypeCount; i++) {
            delta.putEdgeType(readEdgeTypeSchema(in));
        }
        delta.edgeTypeDeletes.addAll(readStringSet(in));

        int indexUpsertCount = in.readInt();
        for (int i = 0; i < indexUpsertCount; i++) {
            delta.indexUpserts.add(new IndexKey(GraphCodec.readString(in), GraphCodec.readString(in)));
        }
        int indexDeleteCount = in.readInt();
        for (int i = 0; i < indexDeleteCount; i++) {
            delta.indexDeletes.add(new IndexKey(GraphCodec.readString(in), GraphCodec.readString(in)));
        }
        return delta.freeze();
    }

    static List<String> readStringList(DataInputStream in) throws IOException {
        int count = in.readInt();
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(GraphCodec.readString(in));
        return values;
    }

    private static Set<Long> readLongSet(DataInputStream in) throws IOException {
        int count = in.readInt();
        Set<Long> values = new LinkedHashSet<>(Math.max(16, count * 2));
        for (int i = 0; i < count; i++) values.add(in.readLong());
        return values;
    }

    private static Set<String> readStringSet(DataInputStream in) throws IOException {
        int count = in.readInt();
        Set<String> values = new LinkedHashSet<>(Math.max(16, count * 2));
        for (int i = 0; i < count; i++) values.add(GraphCodec.readString(in));
        return values;
    }

    static TagSchema readTagSchema(DataInputStream in) throws IOException {
        String name = GraphCodec.readString(in);
        int fieldCount = in.readInt();
        List<TagSchema.Field> fields = new ArrayList<>(fieldCount);
        for (int i = 0; i < fieldCount; i++) {
            fields.add(new TagSchema.Field(GraphCodec.readString(in),
                    TagSchema.DataType.valueOf(GraphCodec.readString(in)), in.readBoolean()));
        }
        return new TagSchema(name, fields);
    }

    static EdgeTypeSchema readEdgeTypeSchema(DataInputStream in) throws IOException {
        String name = GraphCodec.readString(in);
        int fieldCount = in.readInt();
        List<TagSchema.Field> fields = new ArrayList<>(fieldCount);
        for (int i = 0; i < fieldCount; i++) {
            fields.add(new TagSchema.Field(GraphCodec.readString(in),
                    TagSchema.DataType.valueOf(GraphCodec.readString(in)), in.readBoolean()));
        }
        return new EdgeTypeSchema(name, fields);
    }

    @Override
    public String toString() {
        return "GraphDelta{node+~" + nodeUpserts.size() + ", node-" + nodeDeletes.size()
                + ", edge+~" + edgeUpserts.size() + ", edge-" + edgeDeletes.size()
                + ", schema" + (tagUpserts.size() + tagDeletes.size()
                + edgeTypeUpserts.size() + edgeTypeDeletes.size()) + "}";
    }
}
