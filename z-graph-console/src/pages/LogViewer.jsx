import React, { useState, useEffect, useCallback } from 'react';
import { api, formatTimestamp } from '../api.js';

const STATUS_COLORS = {
  200: '#86efac', 201: '#86efac', 204: '#86efac',
  400: '#fde68a', 401: '#fca5a5', 403: '#fca5a5', 404: '#fde68a',
  429: '#fbbf24',
  500: '#f87171', 502: '#f87171', 503: '#f87171'
};

const METHOD_COLORS = {
  GET: '#86efac', POST: '#93c5fd', OPTIONS: '#94a3b8'
};

export default function LogViewer() {
  const [logs, setLogs] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [filter, setFilter] = useState({ method: '', status: '', path: '', requestId: '' });
  const [selectedEntry, setSelectedEntry] = useState(null);

  const fetchLogs = useCallback(async () => {
    try {
      const params = {};
      if (filter.method) params.method = filter.method;
      if (filter.status) params.status = filter.status;
      if (filter.path) params.path = filter.path;
      if (filter.requestId) params.requestId = filter.requestId;
      params.limit = 200;
      const data = await api.logs(params);
      setLogs(data);
      setError(null);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [filter]);

  useEffect(() => {
    fetchLogs();
    if (!autoRefresh) return;
    const timer = setInterval(fetchLogs, 3000);
    return () => clearInterval(timer);
  }, [fetchLogs, autoRefresh]);

  function formatTime(ts) {
    if (!ts) return '';
    const d = new Date(ts);
    return d.toLocaleTimeString('zh-CN', { hour12: false }) + '.' + String(d.getMilliseconds()).padStart(3, '0');
  }

  function statusClass(status) {
    if (status >= 500) return 'log-status-error';
    if (status >= 400) return 'log-status-warn';
    return 'log-status-ok';
  }

  return (
    <>
      <div className="card">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div>
            <h2>请求日志</h2>
            <div className="muted">环形缓冲区最近 {logs?.bufferSize || 500} 条请求记录，自动刷新。</div>
          </div>
          <div className="toolbar">
            <button
              className={autoRefresh ? 'primary' : ''}
              onClick={() => setAutoRefresh(!autoRefresh)}
            >
              {autoRefresh ? '⏸ 暂停' : '▶ 自动刷新'}
            </button>
            <button onClick={fetchLogs}>🔄 刷新</button>
          </div>
        </div>
      </div>

      {/* 过滤器 */}
      <div className="card">
        <div className="toolbar">
          <select value={filter.method} onChange={e => setFilter(f => ({ ...f, method: e.target.value }))}>
            <option value="">全部方法</option>
            <option value="GET">GET</option>
            <option value="POST">POST</option>
            <option value="OPTIONS">OPTIONS</option>
          </select>
          <select value={filter.status} onChange={e => setFilter(f => ({ ...f, status: e.target.value }))}>
            <option value="">全部状态</option>
            <option value="2xx">2xx 成功</option>
            <option value="4xx">4xx 客户端错误</option>
            <option value="5xx">5xx 服务器错误</option>
            <option value="200">200</option>
            <option value="400">400</option>
            <option value="401">401</option>
            <option value="429">429</option>
            <option value="500">500</option>
          </select>
          <input
            placeholder="路径过滤…"
            value={filter.path}
            onChange={e => setFilter(f => ({ ...f, path: e.target.value }))}
            style={{ width: 200 }}
          />
          <input
            placeholder="Request ID…"
            value={filter.requestId}
            onChange={e => setFilter(f => ({ ...f, requestId: e.target.value }))}
            style={{ width: 160 }}
          />
          {logs && (
            <span style={{ fontSize: 12, color: '#64748b' }}>
              共 {logs.total} 条记录，显示 {logs.entries?.length || 0} 条
            </span>
          )}
        </div>
      </div>

      {loading && <div className="card"><div className="empty">加载中…</div></div>}
      {error && <div className="error-banner">加载失败: {error}</div>}

      {/* 日志表格 */}
      {logs && logs.entries && (
        <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
          <div className="log-table-wrap">
            <table className="log-table">
              <thead>
                <tr>
                  <th style={{ width: 110 }}>时间</th>
                  <th style={{ width: 90 }}>Request ID</th>
                  <th style={{ width: 50 }}>方法</th>
                  <th style={{ width: 45 }}>状态</th>
                  <th>路径</th>
                  <th style={{ width: 60 }}>耗时</th>
                  <th style={{ width: 100 }}>客户端</th>
                </tr>
              </thead>
              <tbody>
                {logs.entries.map((entry, i) => (
                  <tr
                    key={i}
                    className={`log-row ${selectedEntry === i ? 'selected' : ''}`}
                    onClick={() => setSelectedEntry(selectedEntry === i ? null : i)}
                  >
                    <td className="mono" style={{ fontSize: 11, color: '#64748b' }}>
                      {formatTime(entry.timestamp)}
                    </td>
                    <td className="mono" style={{ fontSize: 10, color: '#64748b', cursor: 'pointer' }}
                        title={entry.requestId}
                        onClick={e => { e.stopPropagation(); setFilter(f => ({ ...f, requestId: entry.requestId })); }}>
                      {entry.requestId ? entry.requestId.substring(0, 12) + '…' : '-'}
                    </td>
                    <td>
                      <span
                        className="log-method"
                        style={{ color: METHOD_COLORS[entry.method] || '#94a3b8' }}
                      >
                        {entry.method}
                      </span>
                    </td>
                    <td>
                      <span className={`log-status ${statusClass(entry.status)}`}>
                        {entry.status}
                      </span>
                    </td>
                    <td className="mono" style={{ fontSize: 12, color: '#e2e8f0', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {entry.path}
                    </td>
                    <td className="mono" style={{ fontSize: 12, color: entry.elapsedMs > 100 ? '#fbbf24' : '#94a3b8' }}>
                      {entry.elapsedMs}ms
                    </td>
                    <td className="mono" style={{ fontSize: 11, color: '#64748b' }}>
                      {entry.clientIp}
                    </td>
                  </tr>
                ))}
                {logs.entries.length === 0 && (
                  <tr><td colSpan={7} style={{ textAlign: 'center', padding: 30, color: '#64748b' }}>暂无日志记录</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* 详情面板 */}
      {selectedEntry !== null && logs?.entries?.[selectedEntry] && (
        <div className="card">
          <h3 style={{ margin: '0 0 8px', fontSize: 14, color: '#94a3b8' }}>请求详情</h3>
          <pre className="results" style={{ fontSize: 12, maxHeight: 200 }}>
            {JSON.stringify(logs.entries[selectedEntry], null, 2)}
          </pre>
        </div>
      )}
    </>
  );
}
