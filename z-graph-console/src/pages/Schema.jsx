import React, { useEffect, useState, useCallback } from 'react';
import { api } from '../api.js';

const DDL_TEMPLATES = [
  { label: '创建 TAG', cypher: "CREATE TAG IF NOT EXISTS Person (name string, age int)", category: 'DDL' },
  { label: '创建 TAG (Company)', cypher: "CREATE TAG IF NOT EXISTS Company (name string, founded int)", category: 'DDL' },
  { label: '创建 EDGE', cypher: "CREATE EDGE IF NOT EXISTS KNOWS (since int)", category: 'DDL' },
  { label: '创建 EDGE (WORKS_AT)', cypher: "CREATE EDGE IF NOT EXISTS WORKS_AT (role string)", category: 'DDL' },
  { label: '创建索引', cypher: "CREATE TAG INDEX IF NOT EXISTS idx_person_name ON Person(name)", category: 'DDL' },
  { label: '添加属性', cypher: "ALTER TAG Person ADD (email string)", category: 'DDL' },
  { label: '创建节点', cypher: "CREATE (n:Person {name: 'Alice', age: 30})", category: '写入' },
  { label: '批量创建', cypher: "CREATE (a:Person {name: 'Alice', age: 30}),\n(b:Person {name: 'Bob', age: 25}),\n(a)-[:KNOWS {since: 2020}]->(b)", category: '写入' },
  { label: '查询所有节点', cypher: "MATCH (n) RETURN labels(n) AS labels, n.name AS name LIMIT 50", category: '查询' },
  { label: '变长路径', cypher: "MATCH (a:Person)-[:KNOWS*1..3]->(b:Person)\nRETURN a.name AS from, b.name AS to LIMIT 100", category: '查询' },
  { label: '聚合统计', cypher: "MATCH (n:Person)\nRETURN n.age AS age, count(n) AS count ORDER BY age", category: '查询' },
  { label: 'OPTIONAL MATCH', cypher: "MATCH (a:Person)-[r?:KNOWS]->(b:Person)\nRETURN a.name AS from, b.name AS to", category: '查询' },
];

/**
 * Schema 页:展示当前 head 上的 schema + 提供 DDL 快捷操作。
 */
export default function Schema({ server }) {
  const [kind, setKind] = useState('TAGS');
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [ddlResult, setDdlResult] = useState(null);
  const [ddlError, setDdlError] = useState(null);
  const [ddlLoading, setDdlLoading] = useState(false);

  const loadSchema = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.query(kindToCypher(kind), 'main', null);
      setRows(Array.isArray(data) ? data : []);
    } catch (e) {
      setError(e.message);
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [kind]);

  useEffect(() => {
    loadSchema();
  }, [kind, server.head]);

  async function executeDdl(cypher) {
    setDdlLoading(true);
    setDdlError(null);
    setDdlResult(null);
    try {
      const result = await api.query(cypher);
      setDdlResult(result);
      // 刷新 schema
      setTimeout(loadSchema, 500);
    } catch (e) {
      setDdlError(e.message);
    } finally {
      setDdlLoading(false);
    }
  }

  const categories = [...new Set(DDL_TEMPLATES.map(t => t.category))];

  return (
    <>
      {/* DDL 快捷操作 */}
      <div className="card">
        <h2>DDL 快捷操作</h2>
        <div className="muted" style={{ marginBottom: 12 }}>点击按钮直接执行 DDL 语句,写入操作会在 main 分支产生新 commit。</div>
        {categories.map(cat => (
          <div key={cat}>
            <h3 style={{ marginTop: 12, marginBottom: 6 }}>{cat}</h3>
            <div className="chip-list">
              {DDL_TEMPLATES.filter(t => t.category === cat).map((t, i) => (
                <button key={i} className="chip ddl-chip" onClick={() => executeDdl(t.cypher)} disabled={ddlLoading}>
                  {t.label}
                </button>
              ))}
            </div>
          </div>
        ))}
        {ddlLoading && <div className="muted" style={{ marginTop: 8 }}>执行中…</div>}
        {ddlError && <pre className="error" style={{ marginTop: 8 }}>{ddlError}</pre>}
        {ddlResult && <pre className="results" style={{ marginTop: 8 }}>{JSON.stringify(ddlResult, null, 2)}</pre>}
      </div>

      {/* Schema 浏览 */}
      <div className="card">
        <div className="toolbar">
          {['TAGS', 'EDGES', 'INDEXES'].map(k => (
            <button key={k}
              className={kind === k ? 'primary' : ''}
              onClick={() => setKind(k)}>
              SHOW {k}
            </button>
          ))}
          <button onClick={loadSchema} disabled={loading}>
            {loading ? '刷新中…' : '刷新'}
          </button>
        </div>
        <div className="muted" style={{ marginTop: 8 }}>执行:{kindToCypher(kind)}</div>
      </div>

      <div className="card">
        {error && <div className="error">{error}</div>}
        {!error && rows.length === 0 && <div className="empty">尚无 {kind.toLowerCase()}</div>}
        {!error && rows.length > 0 && <ResultTable rows={rows} />}
      </div>
    </>
  );
}

function kindToCypher(kind) {
  if (kind === 'TAGS') return 'SHOW TAGS';
  if (kind === 'EDGES') return 'SHOW EDGES';
  return 'SHOW INDEXES';
}

function ResultTable({ rows }) {
  const columns = Array.from(rows.reduce((set, row) => {
    Object.keys(row || {}).forEach(k => set.add(k));
    return set;
  }, new Set()));
  return (
    <table>
      <thead>
        <tr>{columns.map(c => <th key={c}>{c}</th>)}</tr>
      </thead>
      <tbody>
        {rows.map((r, i) => (
          <tr key={i}>{columns.map(c => <td key={c} className="mono">{format(r[c])}</td>)}</tr>
        ))}
      </tbody>
    </table>
  );
}

function format(v) {
  if (v == null) return '';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}
