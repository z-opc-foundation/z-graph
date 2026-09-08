import React, { useState } from 'react';
import { api } from '../api.js';

const ENDPOINTS = [
  {
    method: 'GET', path: '/health', name: '健康检查',
    desc: '返回服务状态、当前 head、节点数和边数。',
    params: [],
    response: '{ status, head, nodeCount, edgeCount }'
  },
  {
    method: 'POST', path: '/query', name: '执行 Cypher',
    desc: '执行单条 Cypher 查询。写查询会在分支上产生新 commit。',
    params: [
      { name: 'cypher', type: 'string', required: true, example: "MATCH (n) RETURN n LIMIT 10" },
      { name: 'branch', type: 'string', required: false, example: 'main' },
      { name: 'commit', type: 'string', required: false, example: '' }
    ],
    response: '[{ ... }, ...]'
  },
  {
    method: 'POST', path: '/query/batch', name: '批量查询',
    desc: '一次执行多条 Cypher 语句，返回每条的结果和总耗时。',
    params: [
      { name: 'statements', type: 'array', required: true, example: '[{"cypher":"MATCH (n) RETURN n"}]' }
    ],
    response: '{ results: [...], elapsedMs: 123 }'
  },
  {
    method: 'POST', path: '/query/explain', name: '查询计划',
    desc: '返回查询的执行计划（不实际执行写操作），包括操作步骤和复杂度估算。',
    params: [
      { name: 'cypher', type: 'string', required: true, example: "MATCH (n:Person) WHERE n.age > 20 RETURN n" },
      { name: 'branch', type: 'string', required: false, example: 'main' }
    ],
    response: '{ plan: { queryType, steps, estimatedComplexity }, cypher, branch }'
  },
  {
    method: 'GET', path: '/meta/branches', name: '分支列表',
    desc: '返回所有分支的名称和当前 head。',
    params: [],
    response: '[{ Name: "main", Head: "..." }]'
  },
  {
    method: 'GET', path: '/meta/commits', name: '提交历史',
    desc: '返回所有 commit 的完整历史。',
    params: [],
    response: '[{ id, parents, branch, author, message, timestamp, nodeCount, edgeCount }]'
  },
  {
    method: 'GET', path: '/meta/schema', name: 'Schema 信息',
    desc: '返回当前分支的 TAG、EDGE 和 INDEX 定义。',
    params: [
      { name: 'branch', type: 'string', required: false, example: 'main' }
    ],
    response: '{ branch, tags, edges, indexes }'
  },
  {
    method: 'GET', path: '/meta/stats', name: '统计摘要',
    desc: '返回节点数、边数和 db.stats() 信息。',
    params: [
      { name: 'branch', type: 'string', required: false, example: 'main' }
    ],
    response: '{ branch, head, nodeCount, edgeCount, stats }'
  },
  {
    method: 'GET', path: '/meta/metrics', name: '运行指标',
    desc: '返回服务运行指标：请求数、错误率、JVM 内存、运行时间等。',
    params: [],
    response: '{ uptimeMs, totalRequests, errorResponses, errorRate, jvmMemory, ... }'
  },
  {
    method: 'GET', path: '/meta/export', name: '导出图数据',
    desc: '导出当前分支的完整图数据（节点 + 边 + schema）。',
    params: [
      { name: 'branch', type: 'string', required: false, example: 'main' }
    ],
    response: '{ version, branch, head, schema, nodes, edges, exportedAt }'
  },
  {
    method: 'POST', path: '/meta/import', name: '导入节点',
    desc: '通过 JSON 数组导入节点数据。',
    params: [
      { name: 'nodes', type: 'array', required: true, example: '[{"label":"Person","name":"Alice","age":30}]' },
      { name: 'branch', type: 'string', required: false, example: 'main' }
    ],
    response: '{ imported, branch, elapsedMs }'
  }
];

export default function ApiDocs() {
  const [expanded, setExpanded] = useState(null);
  const [testResult, setTestResult] = useState(null);
  const [testLoading, setTestLoading] = useState(false);
  const [testError, setTestError] = useState(null);

  async function testEndpoint(ep) {
    setTestLoading(true);
    setTestResult(null);
    setTestError(null);
    try {
      let response;
      if (ep.method === 'GET') {
        response = await request(`http://${getHost()}${ep.path}`);
      } else {
        const body = {};
        for (const p of ep.params) {
          if (p.example) body[p.name] = p.example;
        }
        response = await request(`http://${getHost()}${ep.path}`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body)
        });
      }
      setTestResult(response);
    } catch (e) {
      setTestError(e.message);
    } finally {
      setTestLoading(false);
    }
  }

  return (
    <>
      <div className="card">
        <h2>API 文档</h2>
        <div className="muted">z-graph HTTP 控制面共 {ENDPOINTS.length} 个端点。点击端点展开详情并可交互测试。</div>
      </div>

      {ENDPOINTS.map((ep, i) => (
        <div key={i} className="card api-endpoint-card">
          <div className="api-endpoint-header" onClick={() => setExpanded(expanded === i ? null : i)}>
            <span className={`api-method ${ep.method.toLowerCase()}`}>{ep.method}</span>
            <code className="api-path">{ep.path}</code>
            <span className="api-name">{ep.name}</span>
            <span style={{ flex: 1 }} />
            <span className="api-expand">{expanded === i ? '▲' : '▼'}</span>
          </div>

          {expanded === i && (
            <div className="api-endpoint-detail">
              <p>{ep.desc}</p>

              {ep.params.length > 0 && (
                <>
                  <h3>参数</h3>
                  <table>
                    <thead>
                      <tr><th>名称</th><th>类型</th><th>必填</th><th>示例</th></tr>
                    </thead>
                    <tbody>
                      {ep.params.map((p, j) => (
                        <tr key={j}>
                          <td className="mono">{p.name}</td>
                          <td>{p.type}</td>
                          <td>{p.required ? <span className="tag ok">必填</span> : '可选'}</td>
                          <td className="mono" style={{ fontSize: 11, color: '#94a3b8' }}>{p.example || '-'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </>
              )}

              <h3>返回值</h3>
              <pre style={{ fontSize: 12, color: '#86efac' }}>{ep.response}</pre>

              <div style={{ marginTop: 12 }}>
                <button className="primary" onClick={() => testEndpoint(ep)} disabled={testLoading}>
                  {testLoading ? '测试中…' : '交互测试'}
                </button>
              </div>

              {testResult && (
                <div style={{ marginTop: 8 }}>
                  <h3>响应</h3>
                  <pre className="results" style={{ fontSize: 11, maxHeight: 300 }}>
                    {typeof testResult === 'string' ? testResult : JSON.stringify(testResult, null, 2)}
                  </pre>
                </div>
              )}
              {testError && (
                <div className="error" style={{ marginTop: 8 }}>{testError}</div>
              )}
            </div>
          )}
        </div>
      ))}
    </>
  );
}

function getHost() {
  const base = (typeof window !== 'undefined' && window.__Z_GRAPH_API__) || '/api';
  if (base === '/api') {
    return window.location.host;
  }
  return base.replace(/^https?:\/\//, '');
}

async function request(url, options = {}) {
  const response = await fetch(url, {
    headers: { 'Accept': 'application/json', ...(options.headers || {}) },
    ...options
  });
  const text = await response.text();
  if (!text) return null;
  try { return JSON.parse(text); } catch { return text; }
}
