import React, { useEffect, useState } from 'react';
import { api, shortHash, formatTimestamp } from '../api.js';

export default function Commits({ server }) {
  const [commits, setCommits] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [branchFilter, setBranchFilter] = useState('');
  const [search, setSearch] = useState('');

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        setLoading(true);
        const c = await api.commits();
        if (cancelled) return;
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

  const branches = Array.from(new Set(commits.map(c => c.branch)));
  const filtered = commits
    .filter(c => !branchFilter || c.branch === branchFilter)
    .filter(c => !search || (c.message || '').toLowerCase().includes(search.toLowerCase())
      || (c.author || '').toLowerCase().includes(search.toLowerCase())
      || (c.id || '').startsWith(search))
    .slice()
    .reverse();

  return (
    <>
      <div className="card">
        <div className="toolbar" style={{ marginBottom: 12 }}>
          <select value={branchFilter} onChange={e => setBranchFilter(e.target.value)}>
            <option value="">所有分支</option>
            {branches.map(b => <option key={b} value={b}>{b}</option>)}
          </select>
          <input
            placeholder="搜索 commit message / author / id"
            value={search}
            onChange={e => setSearch(e.target.value)}
            style={{ flex: 1 }}
          />
          <button onClick={() => { setBranchFilter(''); setSearch(''); }}>清空</button>
        </div>

        {loading && <div className="muted">加载中…</div>}
        {error && <div className="error">{error}</div>}
        {!loading && filtered.length === 0 && <div className="empty">没有匹配的 commit</div>}
        {!loading && filtered.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Commit</th><th>分支</th><th>父节点</th>
                <th>作者</th><th>说明</th><th>节点</th><th>边</th><th>时间</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map(c => (
                <tr key={c.id}>
                  <td className="mono" title={c.id}>{shortHash(c.id, 12)}</td>
                  <td><span className="tag">{c.branch}</span></td>
                  <td className="mono">
                    {(c.parents || []).map(p => shortHash(p, 8)).join(', ') || '-'}
                  </td>
                  <td>{c.author}</td>
                  <td>{c.message}</td>
                  <td>{c.nodeCount}</td>
                  <td>{c.edgeCount}</td>
                  <td>{formatTimestamp(c.timestamp)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="card">
        <h2>如何按 commit 查询</h2>
        <pre className="results">{`GET /query?commit=<commitId>&cypher=MATCH%20(n)%20RETURN%20n%20LIMIT%2010
-- 或者在 Cypher 查询页切到 "指定 commit" 模式`}</pre>
      </div>
    </>
  );
}
