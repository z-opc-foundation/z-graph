import React, { useEffect, useState, useRef } from 'react';
import { api, shortHash, formatTimestamp } from '../api.js';

export default function Dashboard({ server }) {
  const [branches, setBranches] = useState([]);
  const [commits, setCommits] = useState([]);
  const [schema, setSchema] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [metricsHistory, setMetricsHistory] = useState([]);
  const prevMetrics = useRef(null);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        setLoading(true);
        const [b, c, s] = await Promise.all([
          api.branches().catch(() => []),
          api.commits().catch(() => []),
          api.schema().catch(() => null)
        ]);
        if (cancelled) return;
        setBranches(Array.isArray(b) ? b : (b && b.Name ? [b] : []));
        setCommits(Array.isArray(c) ? c : []);
        setSchema(s);
        setError(null);
      } catch (e) {
        if (!cancelled) setError(e.message);
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    load();
  }, [server.head]);

  // 定时采集指标用于图表
  useEffect(() => {
    let cancelled = false;
    async function poll() {
      try {
        const m = await api.metrics().catch(() => null);
        if (cancelled || !m) return;
        const now = Date.now();
        let rps = 0;
        if (prevMetrics.current) {
          const dt = (now - prevMetrics.current.ts) / 1000;
          if (dt > 0) {
            rps = Math.round(((m.totalRequests - prevMetrics.current.total) / dt) * 10) / 10;
          }
        }
        prevMetrics.current = { ts: now, total: m.totalRequests };
        setMetricsHistory(prev => {
          const next = [...prev, { ts: now, rps, requests: m.totalRequests, errors: m.errorResponses, memory: m.jvmMemory?.usedBytes || 0 }];
          return next.slice(-60); // 保留最近 60 个采样点（5 分钟）
        });
      } catch { /* ignore */ }
    }
    poll();
    const timer = setInterval(poll, 5000);
    return () => { cancelled = true; clearInterval(timer); };
  }, []);

  const recent = commits.slice(-5).reverse();
  const tags = schema?.tags || [];
  const edges = schema?.edges || [];
  const indexes = schema?.indexes || [];

  return (
    <>
      <div className="kpi-grid">
        <KPI label="节点数" value={server.nodeCount} icon="N" color="#60a5fa" />
        <KPI label="边数" value={server.edgeCount} icon="E" color="#34d399" />
        <KPI label="分支数" value={branches.length} icon="B" color="#a78bfa" />
        <KPI label="总提交" value={commits.length} icon="C" color="#fbbf24" />
      </div>

      {/* 实时指标图表 */}
      {metricsHistory.length > 1 && (
        <div className="card">
          <h2>实时指标</h2>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
            <MetricsChart
              data={metricsHistory}
              dataKey="rps"
              title="请求速率 (req/s)"
              color="#60a5fa"
              unit="req/s"
            />
            <MetricsChart
              data={metricsHistory}
              dataKey="memory"
              title="JVM 内存"
              color="#a78bfa"
              unit="MB"
              format={v => Math.round(v / 1024 / 1024)}
            />
          </div>
        </div>
      )}

      <div className="row-2">
        {/* 左: Schema 概览 */}
        <div className="card">
          <h2>Schema 概览</h2>
          {loading && <div className="muted">加载中…</div>}
          {!loading && tags.length === 0 && edges.length === 0 && (
            <div className="empty">暂无 Schema,请先 CREATE TAG / CREATE EDGE</div>
          )}
          {tags.length > 0 && (
            <>
              <h3 style={{ marginTop: 12 }}>TAG ({tags.length})</h3>
              <div className="chip-list">
                {tags.map((t, i) => (
                  <span key={i} className="chip tag-chip">{t.Name || t.name || JSON.stringify(t)}</span>
                ))}
              </div>
            </>
          )}
          {edges.length > 0 && (
            <>
              <h3 style={{ marginTop: 12 }}>EDGE ({edges.length})</h3>
              <div className="chip-list">
                {edges.map((e, i) => (
                  <span key={i} className="chip edge-chip">{e.Name || e.name || JSON.stringify(e)}</span>
                ))}
              </div>
            </>
          )}
          {indexes.length > 0 && (
            <>
              <h3 style={{ marginTop: 12 }}>INDEX ({indexes.length})</h3>
              <table>
                <thead><tr><th>名称</th><th>类型</th><th>标签</th></tr></thead>
                <tbody>
                  {indexes.map((idx, i) => (
                    <tr key={i}>
                      <td className="mono">{idx.Name || idx.name || '-'}</td>
                      <td>{idx.Type || idx.type || '-'}</td>
                      <td>{idx.Label || idx.label || '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </div>

        {/* 右: 快捷操作 */}
        <div className="card">
          <h2>快捷操作</h2>
          <div className="action-grid">
            <ActionCard
              title="创建节点"
              desc="CREATE (n:Person {name:'Tom'})"
              color="#60a5fa"
            />
            <ActionCard
              title="查询数据"
              desc="MATCH (n) RETURN n LIMIT 10"
              color="#34d399"
            />
            <ActionCard
              title="Schema 管理"
              desc="CREATE TAG / CREATE EDGE"
              color="#a78bfa"
            />
            <ActionCard
              title="版本管理"
              desc="CALL db.branches() / commits"
              color="#fbbf24"
            />
          </div>
        </div>
      </div>

      {/* 最近提交 */}
      <div className="card">
        <h2>最近提交</h2>
        {loading && <div className="muted">加载中…</div>}
        {error && <div className="error">{error}</div>}
        {!loading && recent.length === 0 && <div className="empty">还没有任何 commit</div>}
        {!loading && recent.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Commit</th><th>分支</th><th>作者</th><th>说明</th><th>节点</th><th>边</th><th>时间</th>
              </tr>
            </thead>
            <tbody>
              {recent.map(c => (
                <tr key={c.id}>
                  <td className="mono">{shortHash(c.id, 10)}</td>
                  <td><span className="tag">{c.branch}</span></td>
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
    </>
  );
}

function KPI({ label, value, icon, color }) {
  return (
    <div className="kpi">
      <div className="kpi-icon" style={{ background: color + '20', color }}>{icon}</div>
      <div className="kpi-content">
        <div className="kpi-value">{value ?? '-'}</div>
        <div className="kpi-label">{label}</div>
      </div>
    </div>
  );
}

function ActionCard({ title, desc, color }) {
  return (
    <div className="action-card" style={{ borderTopColor: color }}>
      <div className="action-title">{title}</div>
      <div className="action-desc mono">{desc}</div>
    </div>
  );
}

function MetricsChart({ data, dataKey, title, color, unit, format }) {
  if (!data || data.length < 2) return null;

  const W = 400, H = 120, PAD = 30;
  const values = data.map(d => format ? format(d[dataKey]) : d[dataKey]);
  const maxVal = Math.max(...values, 1);
  const minVal = 0;
  const range = maxVal - minVal || 1;

  const points = values.map((v, i) => {
    const x = PAD + (i / (values.length - 1)) * (W - PAD * 2);
    const y = H - PAD - ((v - minVal) / range) * (H - PAD * 2);
    return { x, y, v };
  });

  const pathD = points.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x},${p.y}`).join(' ');
  const areaD = pathD + ` L${points[points.length - 1].x},${H - PAD} L${points[0].x},${H - PAD} Z`;
  const current = values[values.length - 1];

  // Y 轴刻度（3 个）
  const yTicks = [0, 0.5, 1].map(f => ({
    value: Math.round(minVal + f * range),
    y: H - PAD - f * (H - PAD * 2)
  }));

  return (
    <div className="metrics-chart">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 8 }}>
        <span style={{ fontSize: 13, color: '#94a3b8' }}>{title}</span>
        <span className="mono" style={{ fontSize: 18, fontWeight: 600, color }}>
          {current}{unit ? ' ' + unit : ''}
        </span>
      </div>
      <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', height: H }}>
        {/* 网格线 */}
        {yTicks.map((t, i) => (
          <g key={i}>
            <line x1={PAD} y1={t.y} x2={W - PAD} y2={t.y} stroke="#1e293b" strokeWidth="1" />
            <text x={PAD - 4} y={t.y + 4} textAnchor="end" fontSize="9" fill="#475569">{t.value}</text>
          </g>
        ))}
        {/* 面积填充 */}
        <path d={areaD} fill={color} fillOpacity="0.1" />
        {/* 折线 */}
        <path d={pathD} fill="none" stroke={color} strokeWidth="2" strokeLinejoin="round" />
        {/* 当前值圆点 */}
        <circle cx={points[points.length - 1].x} cy={points[points.length - 1].y} r="3" fill={color} />
      </svg>
    </div>
  );
}
