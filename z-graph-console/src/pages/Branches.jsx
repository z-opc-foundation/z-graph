import React, { useEffect, useState } from 'react';
import { api, shortHash, formatTimestamp } from '../api.js';

/**
 * 分支管理页 — 当前控制面暴露 /meta/branches(只读列表)。
 * 创建/合并分支通过 Cypher 在 Query 页执行:CALL db.branches() / db.commits() / db.head('<branch>')
 * 也可以用 BEGIN+COMMIT 与 repository.merge Java API;此页聚焦展示。
 */
export default function Branches({ server }) {
  const [branches, setBranches] = useState([]);
  const [commits, setCommits] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        setLoading(true);
        const [b, c] = await Promise.all([api.branches(), api.commits()]);
        if (cancelled) return;
        setBranches(Array.isArray(b) ? b : (b && b.Name ? [b] : []));
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

  const headByBranch = new Map(branches.map(b => [b.Name, b.Head]));
  const commitsByBranch = commits.reduce((acc, c) => {
    if (!acc.has(c.branch)) acc.set(c.branch, []);
    acc.get(c.branch).push(c);
    return acc;
  }, new Map());

  return (
    <>
      <div className="card">
        <h2>分支总览</h2>
        <div className="muted">每个分支指向一个 commit head;切换分支不会丢历史。</div>
        {loading && <div className="muted">加载中…</div>}
        {error && <div className="error">{error}</div>}
        {!loading && branches.length === 0 && <div className="empty">尚无分支</div>}
        {!loading && branches.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>分支</th><th>Head commit</th><th>提交数</th><th>节点</th><th>边</th>
              </tr>
            </thead>
            <tbody>
              {branches.map(b => {
                const headId = b.Head;
                const headCommit = commits.find(c => c.id === headId);
                return (
                  <tr key={b.Name}>
                    <td><span className="tag">{b.Name}</span></td>
                    <td className="mono">{shortHash(headId, 12)}</td>
                    <td>{(commitsByBranch.get(b.Name) || []).length}</td>
                    <td>{headCommit ? headCommit.nodeCount : '-'}</td>
                    <td>{headCommit ? headCommit.edgeCount : '-'}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>

      <div className="card">
        <h2>操作提示</h2>
        <ul className="muted" style={{ lineHeight: 1.8 }}>
          <li>在 Cypher 查询页执行 <code className="mono">CALL db.branches()</code> / <code className="mono">CALL db.commits()</code> 可以拿到结构化版本元数据。</li>
          <li>创建/合并/切换分支通过 Java API 或扩展控制面 endpoint(后续版本)。</li>
          <li>HEAD 提交 ID 可在"提交历史"页找到,搭配 <code className="mono">?commit=&lt;id&gt;</code> 即可对历史快照做只读查询。</li>
        </ul>
      </div>
    </>
  );
}
