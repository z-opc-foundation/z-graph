import React, { useEffect, useState } from 'react';
import { api } from '../api.js';

/**
 * Schema 页:运行 SHOW TAGS / SHOW EDGES / SHOW INDEXES,展示当前 head 上的 schema。
 * 这些命令本身不会写 commit,只读模式。
 */
export default function Schema({ server }) {
  const [kind, setKind] = useState('TAGS');
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      setLoading(true);
      setError(null);
      try {
        const data = await api.query(kindToCypher(kind), 'main', null);
        if (cancelled) return;
        setRows(Array.isArray(data) ? data : []);
      } catch (e) {
        if (!cancelled) { setError(e.message); setRows([]); }
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    load();
    return () => { cancelled = true; };
  }, [kind, server.head]);

  return (
    <>
      <div className="card">
        <div className="toolbar">
          {['TAGS', 'EDGES', 'INDEXES'].map(k => (
            <button key={k}
              className={kind === k ? 'primary' : ''}
              onClick={() => setKind(k)}>
              SHOW {k}
            </button>
          ))}
          <button onClick={() => setKind(kind)} disabled={loading}>
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
  if (kind === 'TAGS') return 'CALL db.tags()';
  if (kind === 'EDGES') return 'CALL db.edges()';
  return 'CALL db.indexes()';
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
