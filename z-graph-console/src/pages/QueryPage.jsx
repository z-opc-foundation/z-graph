import React, { useEffect, useState, useCallback } from 'react';
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
  const [mode, setMode] = useState('branch');
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [elapsed, setElapsed] = useState(null);
  const [history, setHistory] = useState(() => {
    try { return JSON.parse(localStorage.getItem('z-graph-history') || '[]'); } catch { return []; }
  });
  const [showHistory, setShowHistory] = useState(false);

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
      } catch (e) { /* 静默 */ }
    }
    load();
  }, [server.head]);

  function addToHistory(cypher, elapsed) {
    const entry = { cypher: cypher.substring(0, 200), time: Date.now(), elapsed, branch };
    const newHistory = [entry, ...history.filter(h => h.cypher !== entry.cypher)].slice(0, 50);
    setHistory(newHistory);
    try { localStorage.setItem('z-graph-history', JSON.stringify(newHistory)); } catch { /* ignore */ }
  }

  const run = useCallback(async () => {
    if (!cypher.trim()) return;
    setLoading(true);
    setError(null);
    setResult(null);
    setElapsed(null);
    const startTime = performance.now();
    try {
      const response = await api.query(cypher,
        mode === 'branch' ? branch : null,
        mode === 'commit' ? commit : null);
      const ms = response.elapsed || Math.round(performance.now() - startTime) + 'ms';
      setResult(response.data);
      setElapsed(ms);
      addToHistory(cypher, ms);
    } catch (e) {
      setError(e.message);
      addToHistory(cypher, 'error');
    } finally {
      setLoading(false);
    }
  }, [cypher, branch, commit, mode]);

  function applyPreset(key) {
    setCypher(PRESETS[key]);
  }

  function loadFromHistory(entry) {
    setCypher(entry.cypher);
    if (entry.branch) setBranch(entry.branch);
    setShowHistory(false);
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
          <button onClick={() => setShowHistory(!showHistory)} disabled={loading}>
            历史 ({history.length})
          </button>
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

        {showHistory && (
          <div className="history-panel">
            {history.length === 0 && <div className="muted">暂无历史</div>}
            {history.slice(0, 20).map((h, i) => (
              <div key={i} className="history-item" onClick={() => loadFromHistory(h)}>
                <span className="history-cypher mono">{h.cypher.substring(0, 80)}{h.cypher.length > 80 ? '…' : ''}</span>
                <span className="history-meta">
                  {h.elapsed && <span className="tag ok">{h.elapsed}</span>}
                  <span className="muted">{new Date(h.time).toLocaleTimeString()}</span>
                </span>
              </div>
            ))}
          </div>
        )}

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
          onKeyDown={e => {
            if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); run(); }
          }}
        />
        <div className="help">支持 ; 分隔多语句; Ctrl+Enter 快捷执行;写查询在 main 上产生新 commit。</div>
      </div>

      <div className="card">
        <h2>
          结果
          {elapsed && <span className="tag ok" style={{ marginLeft: 8, fontSize: 11 }}>{elapsed}</span>}
          {result && Array.isArray(result) && (
            <span className="muted" style={{ marginLeft: 8, fontSize: 13 }}>{result.length} 行</span>
          )}
        </h2>
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
