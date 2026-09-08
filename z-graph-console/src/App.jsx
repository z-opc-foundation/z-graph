import React, { useEffect, useState } from 'react';
import { api } from './api.js';
import Dashboard from './pages/Dashboard.jsx';
import Branches from './pages/Branches.jsx';
import Commits from './pages/Commits.jsx';
import QueryPage from './pages/QueryPage.jsx';
import Schema from './pages/Schema.jsx';
import GraphView from './pages/GraphView.jsx';

const TABS = [
  { id: 'dashboard', label: '总览', component: Dashboard },
  { id: 'graph', label: '图视图', component: GraphView },
  { id: 'branches', label: '分支', component: Branches },
  { id: 'commits', label: '提交历史', component: Commits },
  { id: 'query', label: 'Cypher 查询', component: QueryPage },
  { id: 'schema', label: 'Schema', component: Schema }
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
  const [apiBase, setApiBase] = useState(api.baseUrl());

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        const health = await api.health();
        if (cancelled) return;
        setServer({
          status: 'up',
          head: health.head,
          nodeCount: health.nodeCount || 0,
          edgeCount: health.edgeCount || 0,
          error: null
        });
      } catch (err) {
        if (cancelled) return;
        setServer({ status: 'down', head: null, nodeCount: 0, edgeCount: 0, error: err.message });
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
          <span>z-graph 控制台</span>
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
        <div className="server-box">
          <label>API 地址</label>
          <input
            value={apiBase}
            onChange={e => updateApiBase(e.target.value)}
            placeholder="/api 或 http://host:8090"
            spellCheck={false}
          />
          <div className="help">支持 /api 同源代理或绝对 URL</div>
        </div>
        <div className="server-box">
          <label>连接状态</label>
          <div style={{ marginTop: 6 }}>
            {server.status === 'up'
              ? <span className="tag ok">UP · head={String(server.head || '').substring(0, 8)}</span>
              : <span className="tag warn">DOWN</span>}
          </div>
          {server.error
            ? <div className="help" style={{ marginTop: 6, color: '#fca5a5' }}>{server.error}</div>
            : null}
        </div>
      </aside>

      <main className="main">
        <header>
          <h1>{TABS.find(t => t.id === tab).label}</h1>
          <div className="status">
            节点 {server.nodeCount} · 边 {server.edgeCount}
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
