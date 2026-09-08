import React, { useEffect, useState, useRef, useCallback } from 'react';
import { api } from '../api.js';

const COLORS = ['#3b82f6', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#06b6d4', '#ec4899', '#84cc16'];
const NODE_RADIUS = 24;

export default function GraphView({ server }) {
  const [graphData, setGraphData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [selectedNode, setSelectedNode] = useState(null);
  const [layout, setLayout] = useState(null);
  const svgRef = useRef(null);
  const [dimensions, setDimensions] = useState({ width: 800, height: 500 });

  useEffect(() => {
    const el = svgRef.current?.parentElement;
    if (el) {
      const ro = new ResizeObserver(entries => {
        for (const entry of entries) {
          setDimensions({ width: entry.contentRect.width, height: Math.max(400, entry.contentRect.height) });
        }
      });
      ro.observe(el);
      return () => ro.disconnect();
    }
  }, []);

  const fetchGraph = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await api.query('MATCH (n) RETURN n', 'main');
      const nodes = (result.data || []).map((row, i) => {
        const n = row.n || row;
        const props = typeof n === 'string' ? parseNodeString(n) : n;
        return { id: props._id || i, label: props._label || 'Node', props, x: 0, y: 0 };
      });

      // 获取边:先查 SHOW EDGES 获取所有边类型,逐个查询
      let edges = [];
      try {
        const edgeTypesResult = await api.query('SHOW EDGES', 'main');
        const edgeTypes = Array.isArray(edgeTypesResult.data) ? edgeTypesResult.data : [];
        for (const et of edgeTypes) {
          const typeName = et.Name || et.name || '';
          if (!typeName) continue;
          try {
            const edgeResult = await api.query(`MATCH (a)-[r:${typeName}]->(b) RETURN a, r, b`, 'main');
            for (const row of (edgeResult.data || [])) {
              const srcProps = typeof row.a === 'string' ? parseNodeString(row.a) : row.a;
              const tgtProps = typeof row.b === 'string' ? parseNodeString(row.b) : row.b;
              const edgeProps = typeof row.r === 'string' ? parseEdgeString(row.r) : row.r;
              edges.push({
                source: srcProps._id,
                target: tgtProps._id,
                type: typeName,
                props: edgeProps
              });
            }
          } catch { /* 单个类型查询失败继续 */ }
        }
        // 如果 SHOW EDGES 为空但有数据,尝试常见边类型
        if (edgeTypes.length === 0 && nodes.length > 0) {
          for (const typeName of ['KNOWS', 'WORKS_AT', 'LIKES', 'FOLLOWS']) {
            try {
              const edgeResult = await api.query(`MATCH (a)-[r:${typeName}]->(b) RETURN a, r, b`, 'main');
              if (Array.isArray(edgeResult.data) && edgeResult.data.length > 0) {
                for (const row of edgeResult.data) {
                  const srcProps = typeof row.a === 'string' ? parseNodeString(row.a) : row.a;
                  const tgtProps = typeof row.b === 'string' ? parseNodeString(row.b) : row.b;
                  edges.push({ source: srcProps._id, target: tgtProps._id, type: typeName, props: {} });
                }
              }
            } catch { /* ignore */ }
          }
        }
      } catch { /* 边查询失败,显示纯节点图 */ }

      setGraphData({ nodes, edges });
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { fetchGraph(); }, [server.head]);

  // 力导向布局
  useEffect(() => {
    if (!graphData || graphData.nodes.length === 0) { setLayout(null); return; }
    const nodes = graphData.nodes.map((n, i) => ({
      ...n,
      x: dimensions.width / 2 + (Math.random() - 0.5) * 200,
      y: dimensions.height / 2 + (Math.random() - 0.5) * 200,
      vx: 0, vy: 0
    }));
    const edges = graphData.edges.map(e => ({
      ...e,
      sourceIdx: nodes.findIndex(n => n.id === e.source),
      targetIdx: nodes.findIndex(n => n.id === e.target)
    })).filter(e => e.sourceIdx >= 0 && e.targetIdx >= 0);

    // 简易力导向模拟
    const cx = dimensions.width / 2, cy = dimensions.height / 2;
    for (let iter = 0; iter < 120; iter++) {
      const alpha = 1 - iter / 120;
      // 斥力（节点间）
      for (let i = 0; i < nodes.length; i++) {
        for (let j = i + 1; j < nodes.length; j++) {
          let dx = nodes[j].x - nodes[i].x;
          let dy = nodes[j].y - nodes[i].y;
          let dist = Math.sqrt(dx * dx + dy * dy) || 1;
          let force = (800 * alpha) / dist;
          nodes[i].vx -= (dx / dist) * force;
          nodes[i].vy -= (dy / dist) * force;
          nodes[j].vx += (dx / dist) * force;
          nodes[j].vy += (dy / dist) * force;
        }
      }
      // 引力（边的弹簧）
      for (const edge of edges) {
        const s = nodes[edge.sourceIdx], t = nodes[edge.targetIdx];
        let dx = t.x - s.x, dy = t.y - s.y;
        let dist = Math.sqrt(dx * dx + dy * dy) || 1;
        let force = (dist - 120) * 0.05 * alpha;
        s.vx += (dx / dist) * force;
        s.vy += (dy / dist) * force;
        t.vx -= (dx / dist) * force;
        t.vy -= (dy / dist) * force;
      }
      // 中心引力
      for (const node of nodes) {
        node.vx += (cx - node.x) * 0.01 * alpha;
        node.vy += (cy - node.y) * 0.01 * alpha;
      }
      // 更新位置
      for (const node of nodes) {
        node.vx *= 0.6;
        node.vy *= 0.6;
        node.x += node.vx;
        node.y += node.vy;
        node.x = Math.max(NODE_RADIUS, Math.min(dimensions.width - NODE_RADIUS, node.x));
        node.y = Math.max(NODE_RADIUS, Math.min(dimensions.height - NODE_RADIUS, node.y));
      }
    }
    setLayout({ nodes, edges });
  }, [graphData, dimensions]);

  const colorMap = {};
  let colorIdx = 0;
  function getColor(label) {
    if (!colorMap[label]) colorMap[label] = COLORS[colorIdx++ % COLORS.length];
    return colorMap[label];
  }

  function renderNode(node, idx) {
    const color = getColor(node.label);
    const isSelected = selectedNode === idx;
    const connectedEdges = layout?.edges.filter(e => e.sourceIdx === idx || e.targetIdx === idx) || [];
    const isDimmed = selectedNode !== null && !isSelected && !connectedEdges.some(e => e.sourceIdx === selectedNode || e.targetIdx === selectedNode);

    return (
      <g key={idx} transform={`translate(${node.x},${node.y})`}
         style={{ cursor: 'pointer', opacity: isDimmed ? 0.25 : 1 }}
         onClick={() => setSelectedNode(isSelected ? null : idx)}>
        <circle r={NODE_RADIUS} fill={color} stroke={isSelected ? '#fff' : '#0f172a'} strokeWidth={isSelected ? 3 : 2} />
        <text textAnchor="middle" dy="0.35em" fill="#fff" fontSize="11" fontWeight="600" style={{ pointerEvents: 'none' }}>
          {node.label.substring(0, 4)}
        </text>
        <text textAnchor="middle" dy={NODE_RADIUS + 14} fill="#94a3b8" fontSize="10" style={{ pointerEvents: 'none' }}>
          {node.props.name || node.props._id || idx}
        </text>
      </g>
    );
  }

  function renderEdge(edge, idx) {
    const s = layout.nodes[edge.sourceIdx], t = layout.nodes[edge.targetIdx];
    if (!s || !t) return null;
    const dx = t.x - s.x, dy = t.y - s.y;
    const dist = Math.sqrt(dx * dx + dy * dy) || 1;
    const nx = dx / dist, ny = dy / dist;
    const sx = s.x + nx * NODE_RADIUS, sy = s.y + ny * NODE_RADIUS;
    const tx = t.x - nx * NODE_RADIUS, ty = t.y - ny * NODE_RADIUS;

    const isHighlighted = selectedNode !== null && (edge.sourceIdx === selectedNode || edge.targetIdx === selectedNode);
    const isDimmed = selectedNode !== null && !isHighlighted;

    // 箭头
    const arrowLen = 10;
    const arrowAngle = Math.atan2(ty - sy, tx - sx);
    const ax1 = tx - arrowLen * Math.cos(arrowAngle - 0.35);
    const ay1 = ty - arrowLen * Math.sin(arrowAngle - 0.35);
    const ax2 = tx - arrowLen * Math.cos(arrowAngle + 0.35);
    const ay2 = ty - arrowLen * Math.sin(arrowAngle + 0.35);

    return (
      <g key={idx} style={{ opacity: isDimmed ? 0.15 : 1 }}>
        <line x1={sx} y1={sy} x2={tx} y2={ty} stroke={isHighlighted ? '#60a5fa' : '#475569'} strokeWidth={isHighlighted ? 2.5 : 1.5} />
        <polygon points={`${tx},${ty} ${ax1},${ay1} ${ax2},${ay2}`} fill={isHighlighted ? '#60a5fa' : '#475569'} />
        {edge.type && (
          <text x={(sx + tx) / 2} y={(sy + ty) / 2 - 6} textAnchor="middle" fill="#64748b" fontSize="9" style={{ pointerEvents: 'none' }}>
            {edge.type}
          </text>
        )}
      </g>
    );
  }

  return (
    <>
      <div className="card">
        <div className="toolbar">
          <button className="primary" onClick={fetchGraph} disabled={loading}>
            {loading ? '加载中…' : '刷新图'}
          </button>
          <span className="muted">
            {layout ? `${layout.nodes.length} 节点 · ${layout.edges.length} 边` : '无数据'}
          </span>
          {selectedNode !== null && layout?.nodes[selectedNode] && (
            <span className="tag" style={{ marginLeft: 8 }}>
              选中: {layout.nodes[selectedNode].label} ({layout.nodes[selectedNode].props.name || layout.nodes[selectedNode].props._id})
            </span>
          )}
        </div>
        {error && <div className="error" style={{ marginTop: 8 }}>{error}</div>}
      </div>

      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        {(!layout || layout.nodes.length === 0) && !loading && (
          <div className="empty">暂无图数据。请先在 Cypher 查询页创建节点和边。</div>
        )}
        <svg ref={svgRef} width="100%" height={dimensions.height}
             style={{ background: '#0f172a', display: layout?.nodes.length ? 'block' : 'none' }}>
          {/* 网格背景 */}
          <defs>
            <pattern id="grid" width="40" height="40" patternUnits="userSpaceOnUse">
              <path d="M 40 0 L 0 0 0 40" fill="none" stroke="#1e293b" strokeWidth="0.5" />
            </pattern>
          </defs>
          <rect width="100%" height="100%" fill="url(#grid)" />

          {/* 边 */}
          <g>{layout?.edges.map((e, i) => renderEdge(e, i))}</g>
          {/* 节点 */}
          <g>{layout?.nodes.map((n, i) => renderNode(n, i))}</g>
        </svg>
      </div>

      {/* 图例 */}
      {layout && layout.nodes.length > 0 && (
        <div className="card">
          <h2>图例</h2>
          <div className="chip-list">
            {[...new Set(layout.nodes.map(n => n.label))].map(label => (
              <span key={label} className="chip" style={{ borderColor: getColor(label), color: getColor(label) }}>
                <span style={{ width: 8, height: 8, borderRadius: '50%', background: getColor(label), display: 'inline-block', marginRight: 4 }} />
                {label}
              </span>
            ))}
          </div>
        </div>
      )}
    </>
  );
}

function parseNodeString(str) {
  // 解析 "(0:[Person] {name=Alice, age=30})" 格式
  const idMatch = str.match(/\((\d+):/);
  const labelMatch = str.match(/:\[(\w+)\]/);
  const propsMatch = str.match(/\{(.+)\}/);
  const props = { _id: idMatch ? parseInt(idMatch[1]) : 0, _label: labelMatch ? labelMatch[1] : 'Node' };
  if (propsMatch) {
    propsMatch[1].split(',').forEach(p => {
      const [k, v] = p.split('=').map(s => s.trim());
      if (k && v) props[k] = isNaN(v) ? v.replace(/^'|'$/g, '') : Number(v);
    });
  }
  return props;
}

function parseEdgeString(str) {
  // 解析 "(1)-[:KNOWS]->(2) {}" 格式
  const propsMatch = str.match(/\}\s*\{(.*?)\}/) || str.match(/\{(.*)\}/);
  const props = {};
  if (propsMatch && propsMatch[1]) {
    propsMatch[1].split(',').forEach(p => {
      const [k, v] = p.split('=').map(s => s.trim());
      if (k && v) props[k] = isNaN(v) ? v.replace(/^'|'$/g, '') : Number(v);
    });
  }
  return props;
}
