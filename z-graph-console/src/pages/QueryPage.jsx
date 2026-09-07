import React, { useEffect, useState } from 'react';
import { api } from '../api.js';

const PRESETS = {
  '节点与边': `CREATE (a:Person {name: 'Alice', age: 30}),
       (b:Person {name: 'Bob', age: 25}),
       (c:Person {name: 'Carol', age: 28}),
       (a)-[:KNOWS]->(b),
       (b)-[:KNOWS]->(c)`,
  '查询所有 Person': `MATCH (n:Person) RETURN n.name AS name, n.age AS age ORDER BY n.age DESC`,
  '变长路径': `MATCH (a:Person {name: 'Alice'})-[:KNOWS*1..3]->(b:Person) RETURN b.name AS friend`,
  '反向关系': `MATCH (b:Person)<-[:KNOWS]-(a:Person) RETURN a.name AS friendOf, b.name AS who`,
  '聚合分组': `MATCH (n:Person) RETURN n.age AS age, count(n) AS total ORDER BY age`,
  'CALL db.version': `CALL db.version()`,
  'CALL db.branches': `CALL db.branches()`,
  'CALL db.commits': `CALL db.commits() LIMIT 10`,
  'CALL db.head': `CALL db.head('main')`,
  'SHOW TAGS': `SHOW TAGS`,
  'SHOW EDGES': `SHOW EDGES`,
  'SHOW STATS': `CALL db.stats()`,
  'EXPLAIN': `EXPLAIN MATCH (n:Person) WHERE n.age > 20 RETURN n`
};

export default function QueryPage({ server }) {
  const [cypher, setCypher] = useState(PRESETS['节点与边']);
  const [branch, setBranch] = useState('main');
  const [commit, setCommit] = useState('');
  const [branches, setBranches] = useState([]);
  const [commits, setCommits] = useState([]);
  const [mode, setMode] = useState('branch'); // 'branch' | 'commit'
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        const [b, c] = await Promise.all([api.branches(), api.commits()]);
        if (cancelled) return;
        const bs = Array.isArray(b) ? b : (b && b.Name ? [b] : []);
        const cs = Array.isArray(c) ? c : [];
        setBranches(bs);
        setCommits(cs);
        if (bs.length && !bs.find(x => x.Name === branch)) {
          setBranch(bs[0].Name);
        }
      } catch (e) { /* 静默:连接可能还没就绪 */ }
    }
    load();
  }, [server.head]);

  async function run() {
    if (!cypher.trim()) return;
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      const data = await api.query(cypher,
        mode === 'branch' ? branch : null,
        mode === 'commit' ? commit : null);
      setResult(data);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }

  function applyPreset(key) {
    setCypher(PRESETS[key]);
  }

  function loadResult(data) {
    if (data == null) return <div className="empty">无返回</div>;
    if (typeof data === 'string') return <pre>{data}</pre>;
    if (Array.isArray(data)) {
      if (data.length === 0) return <div className="empty">0 行结果</div>;
      const columns = Array.from(data.reduce((set, row) => {
        if (row && typeof row === 'object') Object.keys(row).forEach(k => set.add(k));
        return set;
      }, new Set()));
      return (
        <table>
          <thead>
            <tr>{columns.map(c => <th key={c}>{c}</th>)}</tr>
          </thead>
          <tbody>
            {data.map((row, i) => (
              <tr key={i}>
                {columns.map(c => <td key={c} className="mono">{formatCell(row[c])}</td>)}
              </tr>
            ))}
          </tbody>
        </table>
      );
    }
    return <pre>{JSON.stringify(data, null, 2)}</pre>;
  }

  return (
    <>
      <div className="card">
        <div className="toolbar">
          <button className="primary" disabled={loading || !cypher.trim()} onClick={run}>
            {loading ? '执行中…' : '执行'}
          </button>
          <button onClick={() => setCypher('')} disabled={loading}>清空</button>
          <select onChange={e => applyPreset(e.target.value)} value="">
            <option value="" disabled>载入示例…</option>
            {Object.keys(PRESETS).map(k => <option key={k} value={k}>{k}</option>)}
          </select>
          <span style={{ flex: 1 }} />
          <div className="tabs" style={{ margin: 0, borderBottom: 'none' }}>
            <button className={mode === 'branch' ? 'active' : ''} onClick={() => setMode('branch')}>
              分支 head
            </button>
            <button className={mode === 'commit' ? 'active' : ''} onClick={() => setMode('commit')}>
              指定 commit
            </button>
          </div>
        </div>

        <div style={{ marginTop: 12 }}>
          {mode === 'branch'
            ? <select value={branch} onChange={e => setBranch(e.target.value)}>
                {branches.map(b => <option key={b.Name} value={b.Name}>{b.Name}</option>)}
              </select>
            : <select value={commit} onChange={e => setCommit(e.target.value)}>
                <option value="">选择一个 commit</option>
                {commits.slice().reverse().map(c => (
                  <option key={c.id} value={c.id}>
                    {c.id.substring(0, 12)} · {c.branch} · {c.message}
                  </option>
                ))}
              </select>}
        </div>

        <textarea
          value={cypher}
          onChange={e => setCypher(e.target.value)}
          spellCheck={false}
          style={{ width: '100%', marginTop: 12 }}
          rows={10}
          placeholder="MATCH (n:Person) RETURN n LIMIT 10"
        />
        <div className="help">支持 ; 分隔多语句;只读查询绑定当前 head,写查询在 main 上产生新 commit。</div>
      </div>

      <div className="card">
        <h2>结果</h2>
        {error && <div className="results"><pre className="error">{error}</pre></div>}
        {!error && !result && <div className="empty">尚未执行</div>}
        {!error && result && <div className="results">{loadResult(result)}</div>}
      </div>
    </>
  );
}

function formatCell(v) {
  if (v == null) return '';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}
