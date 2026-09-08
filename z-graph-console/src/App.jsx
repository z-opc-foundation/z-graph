import React, { useEffect, useState } from 'react';
import { api } from './api.js';
import Dashboard from './pages/Dashboard.jsx';
import Branches from './pages/Branches.jsx';
import Commits from './pages/Commits.jsx';
import QueryPage from './pages/QueryPage.jsx';
import Schema from './pages/Schema.jsx';
import GraphView from './pages/GraphView.jsx';
import ApiDocs from './pages/ApiDocs.jsx';

const TABS = [
  { id: 'dashboard', label: '总览', component: Dashboard },
  { id: 'graph', label: '图视图', component: GraphView },
  { id: 'branches', label: '分支', component: Branches },
  { id: 'commits', label: '提交历史', component: Commits },
  { id: 'query', label: 'Cypher 查询', component: QueryPage },
  { id: 'schema', label: 'Schema', component: Schema },
  { id: 'api', label: 'API 文档', component: ApiDocs }
];

export default function App() {
  const [tab, setTab] = useState('dashboard');
  const [server, setServer] = useState({
    status: 'unknown',
    head: null,
    nodeCount: 0,
    edgeCount: 0,
    error: null
  });
  const [metrics, setMetrics] = useState(null);
  const [apiBase, setApiBase] = useState(api.baseUrl());

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        const [health, metricsData] = await Promise.all([
          api.health(),
          api.metrics().catch(() => null)
        ]);
        if (cancelled) return;
        setServer({
          status: 'up',
          head: health.head,
          nodeCount: health.nodeCount || 0,
          edgeCount: health.edgeCount || 0,
          error: null
        });
        if (metricsData) setMetrics(metricsData);
      } catch (err) {
        if (cancelled) return;
        setServer({ status: 'down', head: null, nodeCount: 0, edgeCount: 0, error: err.message });
        setMetrics(null);
      }
    }
    load();
    const timer = setInterval(load, 5000);
    return () => { cancelled = true; clearInterval(timer); };
  }, [apiBase]);

  function updateApiBase(value) {
    setApiBase(value);
    window.__Z_GRAPH_API__ = value;
  }

  const Active = TABS.find(t => t.id === tab).component;

  return (
    <div className="layout">
      <aside className="sidebar">
        <div className="brand">
          <img src="/favicon.svg" alt="z-graph" />
          <span>z-graph</span>
        </div>
        <nav>
          {TABS.map(t => (
            <button
              key={t.id}
              className={tab === t.id ? 'active' : ''}
              onClick={() => setTab(t.id)}
            >{t.label}</button>
          ))}
        </nav>

        {/* 连接状态 */}
        <div className="server-box">
          <label>连接状态</label>
          <div style={{ marginTop: 6, display: 'flex', alignItems: 'center', gap: 6 }}>
            <span className={`status-dot ${server.status === 'up' ? 'ok' : 'down'}`} />
            <span style={{ fontSize: 13, color: server.status === 'up' ? '#a7f3d0' : '#fca5a5' }}>
              {server.status === 'up' ? '在线' : server.status === 'unknown' ? '检查中…' : '离线'}
            </span>
          </div>
          {server.error && (
            <div className="help" style={{ marginTop: 4, color: '#fca5a5', fontSize: 11 }}>
              {server.error.length > 50 ? server.error.substring(0, 50) + '…' : server.error}
            </div>
          )}
        </div>

        {/* 实时指标 */}
        {metrics && server.status === 'up' && (
          <div className="server-box metrics-box">
            <label>服务器指标</label>
            <div className="metrics-grid">
              <div className="metric-item">
                <span className="metric-value">{metrics.uptimeFormatted}</span>
                <span className="metric-label">运行时间</span>
              </div>
              <div className="metric-item">
                <span className="metric-value">{metrics.totalRequests}</span>
                <span className="metric-label">请求总数</span>
              </div>
              <div className="metric-item">
                <span className={`metric-value ${metrics.errorResponses > 0 ? 'error' : ''}`}>
                  {metrics.errorRate}
                </span>
                <span className="metric-label">错误率</span>
              </div>
              <div className="metric-item">
                <span className="metric-value">
                  {metrics.jvmMemory ? Math.round(metrics.jvmMemory.usedBytes / 1024 / 1024) + 'MB' : '-'}
                </span>
                <span className="metric-label">JVM 内存</span>
              </div>
            </div>
            <div className="metrics-footer">
              <span>节点 {server.nodeCount} · 边 {server.edgeCount}</span>
              <span>head {String(server.head || '').substring(0, 8)}</span>
            </div>
          </div>
        )}

        {/* API 地址 */}
        <div className="server-box" style={{ marginTop: 'auto' }}>
          <label>API 地址</label>
          <input
            value={apiBase}
            onChange={e => updateApiBase(e.target.value)}
            placeholder="/api 或 http://host:8090"
            spellCheck={false}
          />
        </div>
      </aside>

      <main className="main">
        <header>
          <h1>{TABS.find(t => t.id === tab).label}</h1>
          <div className="status">
            {metrics && (
              <span style={{ fontSize: 12, color: '#64748b' }}>
                {metrics.uptimeFormatted} · {metrics.totalRequests} 请求 · {metrics.errorRate} 错误率
              </span>
            )}
          </div>
        </header>
        <div className="content">
          {server.status === 'down'
            ? <div className="error-banner">
                控制面不可达:{server.error}。请确认 z-graph 服务的 HTTP 端口已启动,并在左侧填入正确的 API 地址。
              </div>
            : null}
          <Active server={server} />
        </div>
      </main>
    </div>
  );
}
