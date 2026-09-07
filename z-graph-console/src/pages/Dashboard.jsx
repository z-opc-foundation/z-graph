import React, { useEffect, useState } from 'react';
import { api, shortHash, formatTimestamp } from '../api.js';

export default function Dashboard({ server }) {
  const [branches, setBranches] = useState([]);
  const [commits, setCommits] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        setLoading(true);
        const [b, c] = await Promise.all([api.branches(), api.commits()]);
        if (cancelled) return;
        setBranches(Array.isArray(b) ? b : (b && b.Name ? [b] : []));
        setCommits(Array.isArray(c) ? c : []);
        setError(null);
      } catch (e) {
        if (!cancelled) setError(e.message);
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    load();
  }, [server.head]);

  const recent = commits.slice(-5).reverse();

  return (
    <>
      <div className="kpi-grid">
        <KPI label="节点数" value={server.nodeCount} delta={`head ${shortHash(server.head)}`} />
        <KPI label="边数" value={server.edgeCount} />
        <KPI label="分支数" value={branches.length} delta={branches.join(', ') || '-'} />
        <KPI label="总提交" value={commits.length} />
      </div>

      <div className="card">
        <h2>最近 5 次提交</h2>
        {loading && <div className="muted">加载中…</div>}
        {error && <div className="error">{error}</div>}
        {!loading && recent.length === 0 && <div className="empty">还没有任何 commit</div>}
        {!loading && recent.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Commit</th><th>分支</th><th>作者</th><th>说明</th><th>时间</th>
              </tr>
            </thead>
            <tbody>
              {recent.map(c => (
                <tr key={c.id}>
                  <td className="mono">{shortHash(c.id, 10)}</td>
                  <td><span className="tag">{c.branch}</span></td>
                  <td>{c.author}</td>
                  <td>{c.message}</td>
                  <td>{formatTimestamp(c.timestamp)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="card">
        <h2>快速查询</h2>
        <div className="muted">试试这些示例,直接在 Cypher 查询页执行:</div>
        <pre className="results" style={{ marginTop: 8 }}>{`-- 查看所有 tag / edge type
SHOW TAGS;
SHOW EDGES;
SHOW STATS;

-- 取出所有 Person
MATCH (n:Person) RETURN n.name AS name, n.age AS age LIMIT 50;

-- 反向关系
MATCH (a)<-[:KNOWS]-(b) RETURN a.name AS friendOf, b.name AS who LIMIT 50;

-- 变长路径
MATCH (a:Person)-[:KNOWS*1..3]->(b:Person) RETURN a.name AS from, b.name AS to;
`}</pre>
      </div>
    </>
  );
}

function KPI({ label, value, delta }) {
  return (
    <div className="kpi">
      <div className="label">{label}</div>
      <div className="value">{value}</div>
      {delta ? <div className="delta">{delta}</div> : null}
    </div>
  );
}
