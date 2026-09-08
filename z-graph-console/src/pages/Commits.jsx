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
  const [view, setView] = useState('graph'); // 'graph' | 'list'
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
        || (c.id || '').startsWith(search))
      .slice()
      .reverse(),
    [commits, branchFilter, search]
  );

  return (
    <>
      <div className="card">
        <div className="toolbar" style={{ marginBottom: 12 }}>
          <div className="tabs" style={{ margin: 0, borderBottom: 'none' }}>
            <button className={view === 'graph' ? 'active' : ''} onClick={() => setView('graph')}>
              🌿 图视图
            </button>
            <button className={view === 'list' ? 'active' : ''} onClick={() => setView('list')}>
              📋 列表
            </button>
          </div>
          <span style={{ flex: 1 }} />
          <select value={branchFilter} onChange={e => setBranchFilter(e.target.value)}>
            <option value="">所有分支</option>
            {branches.map(b => <option key={b} value={b}>{b}</option>)}
          </select>
          <input
            placeholder="搜索 commit message / author / id"
            value={search}
            onChange={e => setSearch(e.target.value)}
            style={{ width: 260 }}
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
        {!loading && filtered.length > 0 && view === 'graph' && (
          <CommitGraph
            commits={filtered}
            branches={branches}
            selected={selectedCommit}
            onSelect={setSelectedCommit}
          />
        )}
        {!loading && filtered.length > 0 && view === 'list' && (
          <table>
            <thead>
              <tr>
                <th>Commit</th><th>分支</th><th>父节点</th>
                <th>作者</th><th>说明</th><th>节点</th><th>边</th><th>时间</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map(c => (
                <tr
                  key={c.id}
                  onClick={() => setSelectedCommit(c)}
                  style={{ cursor: 'pointer', background: selectedCommit?.id === c.id ? '#1e293b' : 'transparent' }}
                >
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

      {/* 选中 commit 的详情面板 */}
      {selectedCommit && (
        <div className="card">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
            <div style={{ flex: 1 }}>
              <h2 style={{ margin: '0 0 8px' }}>
                <span className="mono" style={{ color: BRANCH_COLORS[branches.indexOf(selectedCommit.branch) % BRANCH_COLORS.length] }}>
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

/**
 * Git 风格的提交图可视化
 * 类似 git log --graph 的渲染，每行显示一个 commit，
 * 分支用不同颜色的竖线表示，merge commit 用菱形表示。
 */
function CommitGraph({ commits, branches, selected, onSelect }) {
  const LANE_WIDTH = 18;
  const ROW_HEIGHT = 40;
  const NODE_RADIUS = 5;
  const LEFT_PAD = 20;

  // 1. 给每个 commit 分配 lane
  const layout = useMemo(() => layoutCommits(commits, branches), [commits, branches]);

  // 2. 计算需要显示的总 lane 数
  const totalLanes = layout.maxLane + 1;
  const graphWidth = LEFT_PAD * 2 + totalLanes * LANE_WIDTH;
  const graphHeight = commits.length * ROW_HEIGHT + 20;

  // 3. 分支颜色映射
  const branchColor = (branch) => {
    const idx = branches.indexOf(branch);
    return BRANCH_COLORS[idx % BRANCH_COLORS.length];
  };

  // 4. 构建连接线段
  const lines = useMemo(() => {
    const segs = [];
    const idToIndex = new Map();
    commits.forEach((c, i) => idToIndex.set(c.id, i));

    for (let i = 0; i < commits.length; i++) {
      const c = commits[i];
      const lane = layout.lanes.get(c.id) || 0;
      const x = LEFT_PAD + lane * LANE_WIDTH + LANE_WIDTH / 2;
      const cy = i * ROW_HEIGHT + ROW_HEIGHT / 2 + 10;

      // 向上连接到父节点
      const parents = c.parents || [];
      if (parents.length === 0 && i < commits.length - 1) {
        // 根节点，向下画虚线
        segs.push({
          type: 'continue',
          x1: x, y1: cy,
          x2: x, y2: cy + ROW_HEIGHT / 2,
          color: branchColor(c.branch)
        });
      }

      parents.forEach((pid) => {
        const parentIdx = idToIndex.get(pid);
        if (parentIdx === undefined) return;
        const parent = commits[parentIdx];
        const parentLane = layout.lanes.get(pid) || 0;
        const px = LEFT_PAD + parentLane * LANE_WIDTH + LANE_WIDTH / 2;
        const py = parentIdx * ROW_HEIGHT + ROW_HEIGHT / 2 + 10;

        if (lane === parentLane) {
          // 同 lane 直连
          segs.push({
            type: 'straight',
            x1: x, y1: cy,
            x2: px, y2: py,
            color: branchColor(c.branch)
          });
        } else {
          // 跨 lane merge/branch：先竖直后水平再竖直
          const midY = (cy + py) / 2;
          segs.push({
            type: 'turn',
            points: [
              { x, y: cy },
              { x, y: midY },
              { x: px, y: midY },
              { x: px, y: py },
            ],
            color: branchColor(c.branch)
          });
        }
      });
    }

    return segs;
  }, [commits, layout, branches]);

  return (
    <div className="commit-graph" style={{ overflowX: 'auto', maxWidth: '100%' }}>
      <div style={{ position: 'relative', minWidth: graphWidth + 300 }}>
        <svg
          width={graphWidth}
          height={graphHeight}
          style={{ position: 'absolute', top: 0, left: 0, pointerEvents: 'none' }}
        >
          {lines.map((line, i) => {
            if (line.type === 'straight') {
              return (
                <line
                  key={i}
                  x1={line.x1} y1={line.y1} x2={line.x2} y2={line.y2}
                  stroke={line.color} strokeWidth={2}
                />
              );
            }
            if (line.type === 'turn') {
              const d = `M ${line.points[0].x},${line.points[0].y} ` +
                `L ${line.points[1].x},${line.points[1].y} ` +
                `L ${line.points[2].x},${line.points[2].y} ` +
                `L ${line.points[3].x},${line.points[3].y}`;
              return (
                <path
                  key={i}
                  d={d}
                  stroke={line.color}
                  strokeWidth={2}
                  fill="none"
                />
              );
            }
            if (line.type === 'continue') {
              return (
                <line
                  key={i}
                  x1={line.x1} y1={line.y1} x2={line.x2} y2={line.y2}
                  stroke={line.color} strokeWidth={2} strokeDasharray="3,3"
                />
              );
            }
            return null;
          })}
        </svg>

        {/* commit 行 */}
        <div>
          {commits.map((c, i) => {
            const lane = layout.lanes.get(c.id) || 0;
            const isMerge = (c.parents || []).length > 1;
            const isSelected = selected?.id === c.id;
            const cy = i * ROW_HEIGHT + ROW_HEIGHT / 2 + 10;
            const cx = LEFT_PAD + lane * LANE_WIDTH + LANE_WIDTH / 2;

            return (
              <div
                key={c.id}
                onClick={() => onSelect(isSelected ? null : c)}
                style={{
                  position: 'relative',
                  display: 'flex',
                  alignItems: 'center',
                  height: ROW_HEIGHT,
                  cursor: 'pointer',
                  background: isSelected ? '#1e293b' : 'transparent',
                  borderRadius: 4,
                  padding: '0 8px',
                  transition: 'background 0.1s',
                }}
              >
                {/* commit 节点圆圈 */}
                <svg
                  width={graphWidth}
                  height={ROW_HEIGHT}
                  style={{ position: 'absolute', top: 0, left: 0, pointerEvents: 'none' }}
                >
                  {/* 节点 */}
                  {isMerge ? (
                    <polygon
                      points={`${cx},${cy - NODE_RADIUS} ${cx + NODE_RADIUS},${cy} ${cx},${cy + NODE_RADIUS} ${cx - NODE_RADIUS},${cy}`}
                      fill={branchColor(c.branch)}
                      stroke="#0b1220"
                      strokeWidth={2}
                    />
                  ) : (
                    <circle
                      cx={cx} cy={cy} r={NODE_RADIUS}
                      fill={branchColor(c.branch)}
                      stroke="#0b1220"
                      strokeWidth={2}
                    />
                  )}
                  {/* 分支标签（hover 显示） */}
                  <title>{c.branch}</title>
                </svg>

                {/* commit 信息 */}
                <div
                  style={{
                    marginLeft: graphWidth - cx + 8,
                    display: 'flex',
                    alignItems: 'center',
                    gap: 8,
                    fontSize: 13,
                    overflow: 'hidden',
                    whiteSpace: 'nowrap',
                  }}
                >
                  <code
                    className="mono"
                    style={{ color: branchColor(c.branch), fontWeight: 600 }}
                    title={c.id}
                  >
                    {shortHash(c.id, 8)}
                  </code>
                  <span style={{
                    color: isMerge ? '#fbbf24' : '#e2e8f0',
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    flex: 1,
                  }}>
                    {c.message}
                  </span>
                  <span className="muted" style={{ fontSize: 11 }}>
                    {formatTimestamp(c.timestamp)}
                  </span>
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
              display: 'inline-block', width: 12, height: 12,
              borderRadius: '50%', background: branchColor(b),
            }} />
            <span className="mono">{b}</span>
            <span className="muted">({commits.filter(c => c.branch === b).length} commits)</span>
          </div>
        ))}
      </div>
    </div>
  );
}

/**
 * 为每个 commit 分配 lane（类似 git log 的 lane 分配算法）
 * 优先复用现有 lane，否则分配新 lane。
 */
function layoutCommits(commits, branches) {
  // commits 已是时间倒序（最新在前）
  const lanes = new Map(); // commitId -> lane index
  const activeLanes = []; // 当前活跃的 lane 列表
  let maxLane = 0;

  for (const c of commits) {
    const parents = c.parents || [];

    // 优先在已有 lane 上找（如果这个 commit 的 ID 是某个 lane 的 head）
    let assigned = false;
    for (let i = 0; i < activeLanes.length; i++) {
      if (activeLanes[i] && activeLanes[i].commitId === c.id) {
        lanes.set(c.id, i);
        maxLane = Math.max(maxLane, i);
        // 处理 parents
        if (parents.length === 0) {
          activeLanes[i] = null;
        } else if (parents.length === 1) {
          activeLanes[i] = { commitId: parents[0], branch: c.branch };
        } else {
          // merge commit：第一个 parent 留在当前 lane，其余记录到第一个 lane
          activeLanes[i] = { commitId: parents[0], branch: c.branch };
        }
        assigned = true;
        break;
      }
    }

    if (!assigned) {
      // 找空闲 lane
      let freeLane = activeLanes.findIndex(l => l === null);
      if (freeLane === -1) {
        freeLane = activeLanes.length;
        activeLanes.push(null);
      }
      lanes.set(c.id, freeLane);
      maxLane = Math.max(maxLane, freeLane);
      if (parents.length === 0) {
        activeLanes[freeLane] = null;
      } else if (parents.length === 1) {
        activeLanes[freeLane] = { commitId: parents[0], branch: c.branch };
      } else {
        activeLanes[freeLane] = { commitId: parents[0], branch: c.branch };
      }
    }
  }

  return { lanes, maxLane };
}
