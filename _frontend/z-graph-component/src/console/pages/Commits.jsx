import React, { useEffect, useState, useMemo } from 'react';
import { api, shortHash, formatTimestamp } from '../api.js';

const BRANCH_COLORS = [
  '#60a5fa', // blue
  '#34d399', // green
  '#fbbf24', // amber
  '#a78bfa', // purple
  '#f87171', // red
  '#67e8f9', // cyan
  '#fb923c', // orange
  '#f472b6', // pink
];

export default function Commits({ server }) {
  const [commits, setCommits] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [branchFilter, setBranchFilter] = useState('');
  const [search, setSearch] = useState('');
  const [selectedCommit, setSelectedCommit] = useState(null);

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

  const branches = useMemo(() =>
    Array.from(new Set(commits.map(c => c.branch))), [commits]
  );

  const filtered = useMemo(() =>
    commits
      .filter(c => !branchFilter || c.branch === branchFilter)
      .filter(c => !search || (c.message || '').toLowerCase().includes(search.toLowerCase())
        || (c.author || '').toLowerCase().includes(search.toLowerCase())
        || (c.id || '').startsWith(search)),
    [commits, branchFilter, search]
  );

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
          <button onClick={() => { setBranchFilter(''); setSearch(''); setSelectedCommit(null); }}>
            清空
          </button>
        </div>

        {loading && <div className="muted">加载中…</div>}
        {error && <div className="error-banner">{error}</div>}
        {!loading && filtered.length === 0 && (
          <div className="empty">没有匹配的 commit</div>
        )}
        {!loading && filtered.length > 0 && (
          <CommitFlowGraph
            commits={filtered}
            branches={branches}
            selected={selectedCommit}
            onSelect={setSelectedCommit}
          />
        )}
      </div>

      {/* 选中 commit 的详情面板 */}
      {selectedCommit && (
        <div className="card">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
            <div style={{ flex: 1 }}>
              <h2 style={{ margin: '0 0 8px' }}>
                <span className="mono" style={{ color: branchColor(branches, selectedCommit.branch) }}>
                  {shortHash(selectedCommit.id, 12)}
                </span>
                {' '}提交详情
              </h2>
              <div style={{ marginBottom: 8 }}>
                <span className="tag" style={{ marginRight: 8 }}>{selectedCommit.branch}</span>
                <span className="muted">{formatTimestamp(selectedCommit.timestamp)}</span>
              </div>
              <div style={{ marginBottom: 4 }}>
                <strong>作者：</strong> {selectedCommit.author}
              </div>
              <div style={{ marginBottom: 4 }}>
                <strong>说明：</strong> {selectedCommit.message}
              </div>
              <div style={{ marginBottom: 4 }}>
                <strong>父节点：</strong>{' '}
                {(selectedCommit.parents || []).map((p, i) => (
                  <span key={i} className="mono" style={{ marginRight: 8 }}>
                    {shortHash(p, 12)}
                  </span>
                )) || '-'}
              </div>
              <div style={{ marginBottom: 8 }}>
                <strong>变更：</strong>
                <span className="tag ok" style={{ marginLeft: 4 }}>{selectedCommit.nodeCount} 节点</span>
                <span className="tag alt" style={{ marginLeft: 4 }}>{selectedCommit.edgeCount} 边</span>
              </div>
              <div style={{ marginBottom: 8 }}>
                <strong>完整 ID：</strong>
                <code className="mono" style={{ fontSize: 11, color: '#94a3b8', wordBreak: 'break-all' }}>
                  {selectedCommit.id}
                </code>
              </div>
            </div>
            <button onClick={() => setSelectedCommit(null)}>✕</button>
          </div>
          <div style={{ marginTop: 12 }}>
            <strong>按此 commit 查询：</strong>
            <pre className="results mono" style={{ marginTop: 4, fontSize: 12 }}>
{`GET /query?commit=${selectedCommit.id}&cypher=MATCH%20(n)%20RETURN%20n%20LIMIT%2010
-- 或者在 Cypher 查询页切到 "指定 commit" 模式`}
            </pre>
          </div>
        </div>
      )}

      <div className="card">
        <h2>如何按 commit 查询</h2>
        <pre className="results">{`GET /query?commit=<commitId>&cypher=MATCH%20(n)%20RETURN%20n%20LIMIT%2010
-- 或者在 Cypher 查询页切到 "指定 commit" 模式`}</pre>
      </div>
    </>
  );
}

function branchColor(branches, branch) {
  const idx = branches.indexOf(branch);
  return BRANCH_COLORS[idx % BRANCH_COLORS.length];
}

/**
 * 横向流程图式提交图（GitKraken / GitHub 网络图风格）
 *
 *  - 时间轴从左到右（最新在最右）
 *  - 每个分支是水平 lane
 *  - commit 节点是圆形/菱形
 *  - merge commit 用曲线箭头连接到主分支
 *  - 分支名称显示在最右侧
 */
function CommitFlowGraph({ commits, branches, selected, onSelect }) {
  // 时间倒序：最新在最左
  const ordered = useMemo(() =>
    [...commits].sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0)),
    [commits]
  );

  const COL_WIDTH = 130;       // 每个 commit 列宽
  const LANE_HEIGHT = 70;      // 每个分支行高
  const NODE_RADIUS = 7;       // commit 节点半径
  const LEFT_PAD = 30;
  const TOP_PAD = 50;          // 给分支标签留空间
  const RIGHT_PAD = 140;       // 给分支名留空间

  // 1. 计算 lane 布局：从主分支开始，其他分支独立一行
  const branchLanes = useMemo(() => {
    const lanes = new Map(); // branch -> lane index
    // main 总是第一个 lane（如果有）
    const sortedBranches = [...branches].sort((a, b) => {
      if (a === 'main') return -1;
      if (b === 'main') return 1;
      return branches.indexOf(a) - branches.indexOf(b);
    });
    sortedBranches.forEach((b, i) => lanes.set(b, i));
    return { lanes, totalLanes: sortedBranches.length };
  }, [branches]);

  // 2. 每个 commit 的坐标
  const positions = useMemo(() => {
    const map = new Map();
    ordered.forEach((c, i) => {
      const lane = branchLanes.lanes.get(c.branch) || 0;
      map.set(c.id, {
        col: i,
        x: LEFT_PAD + i * COL_WIDTH + COL_WIDTH / 2,
        y: TOP_PAD + lane * LANE_HEIGHT + LANE_HEIGHT / 2,
        lane,
        branch: c.branch
      });
    });
    return map;
  }, [ordered, branchLanes]);

  // 3. 构建连线：commit -> parent（箭头从 child 指向 parent）
  const connections = useMemo(() => {
    const lines = [];
    for (const c of ordered) {
      const from = positions.get(c.id);
      if (!from) continue;
      const parents = c.parents || [];

      parents.forEach((pid, idx) => {
        const to = positions.get(pid);
        if (!to) return;
        // 箭头方向：从 child 指向 parent
        // child 在左边（时间晚），parent 在右边（时间早）? 反过来 — 最新在最左，parent 应在右边
        lines.push({
          from, to, color: branchColor(branches, c.branch),
          isMainParent: idx === 0,
          isMerge: parents.length > 1
        });
      });
    }
    return lines;
  }, [ordered, positions, branchLanes, branches]);

  const graphWidth = LEFT_PAD + ordered.length * COL_WIDTH + RIGHT_PAD;
  const graphHeight = TOP_PAD + branchLanes.totalLanes * LANE_HEIGHT + 30;

  return (
    <div className="commit-flow-graph" style={{ overflowX: 'auto', overflowY: 'hidden', padding: '12px 0' }}>
      <div style={{ minWidth: graphWidth, position: 'relative' }}>
        {/* 时间轴箭头 + 分支轨道 */}
        <svg
          width={graphWidth}
          height={graphHeight}
          style={{ position: 'absolute', top: 0, left: 0 }}
        >
          <defs>
            <marker
              id="arrowhead"
              markerWidth="10" markerHeight="10"
              refX="8" refY="3"
              orient="auto"
              markerUnits="strokeWidth"
            >
              <path d="M0,0 L0,6 L9,3 z" fill="#475569" />
            </marker>
            <marker
              id="arrowhead-active"
              markerWidth="10" markerHeight="10"
              refX="8" refY="3"
              orient="auto"
              markerUnits="strokeWidth"
            >
              <path d="M0,0 L0,6 L9,3 z" fill="#60a5fa" />
            </marker>
          </defs>

          {/* 分支轨道线（贯穿整个时间轴） */}
          {[...branchLanes.lanes.entries()].map(([branch, laneIdx]) => {
            const y = TOP_PAD + laneIdx * LANE_HEIGHT + LANE_HEIGHT / 2;
            return (
              <g key={branch}>
                <line
                  x1={LEFT_PAD - 10}
                  y1={y}
                  x2={LEFT_PAD + ordered.length * COL_WIDTH + 10}
                  y2={y}
                  stroke={branchColor(branches, branch)}
                  strokeWidth={1.5}
                  strokeDasharray="6,4"
                  opacity={0.4}
                />
              </g>
            );
          })}

          {/* 时间轴 */}
          <line
            x1={LEFT_PAD - 10}
            y1={TOP_PAD - 20}
            x2={LEFT_PAD + ordered.length * COL_WIDTH - 10}
            y2={TOP_PAD - 20}
            stroke="#475569"
            strokeWidth={1}
            markerEnd="url(#arrowhead)"
          />
          <text
            x={LEFT_PAD - 5}
            y={TOP_PAD - 25}
            fontSize={11}
            fill="#64748b"
          >
            旧
          </text>

          {/* 连线（child → parent） */}
          {connections.map((conn, i) => {
            const { from, to, color, isMerge, isMainParent } = conn;
            const isActive = selected?.id === from.commit?.id || selected?.id === to.commit?.id;

            // child 在左，parent 在右（最新在最左，时间向←）
            const x1 = from.x - NODE_RADIUS - 2;
            const y1 = from.y;
            const x2 = to.x + NODE_RADIUS + 2;
            const y2 = to.y;

            // 同 lane: 直线
            if (from.lane === to.lane) {
              return (
                <line
                  key={i}
                  x1={x1} y1={y1} x2={x2} y2={y2}
                  stroke={isActive ? '#60a5fa' : color}
                  strokeWidth={isActive ? 2.5 : 2}
                  markerEnd={isActive ? 'url(#arrowhead-active)' : 'url(#arrowhead)'}
                />
              );
            }

            // 跨 lane: 贝塞尔曲线
            const dx = x2 - x1;
            const cp1x = x1 + dx * 0.3;
            const cp1y = y1;
            const cp2x = x2 - dx * 0.3;
            const cp2y = y2;
            const d = `M ${x1},${y1} C ${cp1x},${cp1y} ${cp2x},${cp2y} ${x2},${y2}`;

            return (
              <path
                key={i}
                d={d}
                stroke={isActive ? '#60a5fa' : color}
                strokeWidth={isActive ? 2.5 : (isMerge && !isMainParent ? 1.5 : 2)}
                strokeDasharray={isMerge && !isMainParent ? '4,3' : '0'}
                fill="none"
                opacity={isMerge && !isMainParent ? 0.6 : 1}
                markerEnd={isActive ? 'url(#arrowhead-active)' : 'url(#arrowhead)'}
              />
            );
          })}

          {/* commit 节点 */}
          {ordered.map((c) => {
            const pos = positions.get(c.id);
            if (!pos) return null;
            const isMerge = (c.parents || []).length > 1;
            const isSelected = selected?.id === c.id;
            const color = branchColor(branches, c.branch);
            const r = isSelected ? NODE_RADIUS + 2 : NODE_RADIUS;

            if (isMerge) {
              // merge commit: 菱形
              return (
                <g key={c.id}>
                  <polygon
                    points={`${pos.x},${pos.y - r} ${pos.x + r},${pos.y} ${pos.x},${pos.y + r} ${pos.x - r},${pos.y}`}
                    fill={color}
                    stroke={isSelected ? '#fff' : '#0b1220'}
                    strokeWidth={isSelected ? 2 : 1.5}
                    style={{ cursor: 'pointer' }}
                    onClick={() => onSelect(isSelected ? null : c)}
                  />
                  {isSelected && (
                    <circle cx={pos.x} cy={pos.y} r={r + 4}
                      fill="none" stroke={color} strokeWidth={1} opacity={0.5} />
                  )}
                </g>
              );
            }
            return (
              <g key={c.id}>
                <circle
                  cx={pos.x} cy={pos.y} r={r}
                  fill={color}
                  stroke={isSelected ? '#fff' : '#0b1220'}
                  strokeWidth={isSelected ? 2 : 1.5}
                  style={{ cursor: 'pointer' }}
                  onClick={() => onSelect(isSelected ? null : c)}
                />
                {isSelected && (
                  <circle cx={pos.x} cy={pos.y} r={r + 4}
                    fill="none" stroke={color} strokeWidth={1} opacity={0.5} />
                )}
              </g>
            );
          })}

          {/* 分支标签（在最右侧） */}
          {[...branchLanes.lanes.entries()].map(([branch, laneIdx]) => {
            const y = TOP_PAD + laneIdx * LANE_HEIGHT + LANE_HEIGHT / 2;
            const x = LEFT_PAD + ordered.length * COL_WIDTH + 10;
            const color = branchColor(branches, branch);
            const branchCommits = ordered.filter(c => c.branch === branch);
            const head = branchCommits[0]; // 最新

            return (
              <g key={branch}>
                <line
                  x1={x - 10} y1={y}
                  x2={x + 10} y2={y}
                  stroke={color} strokeWidth={2}
                />
                <rect
                  x={x + 12} y={y - 11}
                  width={Math.max(60, branch.length * 8 + 16)}
                  height={22}
                  rx={4}
                  fill={color + '20'}
                  stroke={color}
                  strokeWidth={1.5}
                  style={{ cursor: 'pointer' }}
                  onClick={() => head && onSelect(head)}
                />
                <text
                  x={x + 22} y={y + 4}
                  fontSize={12}
                  fill={color}
                  fontFamily="'SFMono-Regular', Menlo, Consolas, monospace"
                  fontWeight={600}
                  style={{ cursor: 'pointer' }}
                  onClick={() => head && onSelect(head)}
                >
                  {branch}
                </text>
              </g>
            );
          })}

          {/* 分支名 lane 标签（在最左侧） */}
          {[...branchLanes.lanes.entries()].map(([branch, laneIdx]) => {
            const y = TOP_PAD + laneIdx * LANE_HEIGHT + LANE_HEIGHT / 2;
            const color = branchColor(branches, branch);
            return (
              <text
                key={`${branch}-label`}
                x={5}
                y={y + 4}
                fontSize={11}
                fill={color}
                fontFamily="'SFMono-Regular', Menlo, Consolas, monospace"
              >
                {branch}
              </text>
            );
          })}
        </svg>

        {/* commit 信息覆盖层（绝对定位在节点上方） */}
        <div style={{ position: 'relative', pointerEvents: 'none' }}>
          {ordered.map((c, i) => {
            const pos = positions.get(c.id);
            if (!pos) return null;
            const isSelected = selected?.id === c.id;
            return (
              <div
                key={c.id}
                onClick={() => onSelect(isSelected ? null : c)}
                style={{
                  position: 'absolute',
                  left: pos.x - COL_WIDTH / 2 + 12,
                  top: pos.y - 26,
                  width: COL_WIDTH - 14,
                  fontSize: 11,
                  color: isSelected ? '#f8fafc' : '#cbd5e1',
                  pointerEvents: 'auto',
                  cursor: 'pointer',
                  textAlign: 'left',
                }}
              >
                <div
                  className="mono"
                  style={{
                    fontSize: 10,
                    color: branchColor(branches, c.branch),
                    fontWeight: 600,
                    whiteSpace: 'nowrap',
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                  }}
                  title={c.id}
                >
                  {shortHash(c.id, 7)}
                </div>
                <div
                  style={{
                    whiteSpace: 'nowrap',
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                  }}
                  title={c.message}
                >
                  {c.message}
                </div>
                <div style={{ fontSize: 9, color: '#64748b', whiteSpace: 'nowrap' }}>
                  {formatTimestamp(c.timestamp)}
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* 分支图例 */}
      <div style={{ marginTop: 16, display: 'flex', flexWrap: 'wrap', gap: 12, padding: '8px 0', borderTop: '1px solid #1e293b' }}>
        {branches.map(b => (
          <div key={b} style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
            <span style={{
              display: 'inline-block', width: 14, height: 3,
              background: branchColor(branches, b),
            }} />
            <span className="mono">{b}</span>
            <span className="muted">({ordered.filter(c => c.branch === b).length} commits)</span>
          </div>
        ))}
        <div style={{ marginLeft: 'auto', fontSize: 11, color: '#64748b' }}>
          💡 点击 commit 或分支标签查看详情 · 时间轴 ←
        </div>
      </div>
    </div>
  );
}
