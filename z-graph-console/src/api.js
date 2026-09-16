/**
 * z-graph 控制面 HTTP 客户端 — 直接打到 /health /meta/* /query。
 * 默认同源走 /api/* 代理前缀(开发模式由 Vite 代理到 8090),
 * 生产部署时可改成绝对地址,例如 http://z-graph-server:8090。
 */

let _baseUrl = (typeof window !== 'undefined' && window.__Z_GRAPH_API__)
    || (import.meta.env && import.meta.env.VITE_API_BASE)
    || '/api';

function joinUrl(base, path) {
  if (!base || base === '/api') return '/api' + path;
  return base.replace(/\/$/, '') + path;
}

async function request(path, options = {}) {
  const url = joinUrl(_baseUrl, path);
  const response = await fetch(url, {
    headers: { 'Accept': 'application/json', ...(options.headers || {}) },
    ...options
  });
  if (!response.ok) {
    let detail = '';
    try { detail = await response.text(); } catch (e) { /* ignore */ }
    throw new Error(`HTTP ${response.status} ${response.statusText}: ${detail || url}`);
  }
  const text = await response.text();
  const elapsed = response.headers.get('X-Response-Time');
  if (!text) return { data: null, elapsed };
  try {
    return { data: JSON.parse(text), elapsed };
  } catch (e) {
    return { data: text, elapsed };
  }
}

/**
 * 简化版请求 — 直接返回 data（兼容旧调用）。
 */
async function simpleRequest(path, options = {}) {
  const result = await request(path, options);
  return result.data;
}

export const api = {
  baseUrl: () => _baseUrl,
  setBaseUrl: (value) => { _baseUrl = value; if (typeof window !== 'undefined') window.__Z_GRAPH_API__ = value; },

  // 健康检查
  health: () => simpleRequest('/health'),

  // 元数据
  branches: () => simpleRequest('/meta/branches'),
  commits: () => simpleRequest('/meta/commits'),
  schema: (branch = 'main') => simpleRequest('/meta/schema?branch=' + encodeURIComponent(branch)),
  stats: (branch = 'main') => simpleRequest('/meta/stats?branch=' + encodeURIComponent(branch)),
  metrics: () => simpleRequest('/meta/metrics'),

  // 请求日志
  logs: (params = {}) => {
    const qs = new URLSearchParams();
    if (params.limit) qs.set('limit', params.limit);
    if (params.offset) qs.set('offset', params.offset);
    if (params.method) qs.set('method', params.method);
    if (params.status) qs.set('status', params.status);
    if (params.path) qs.set('path', params.path);
    return simpleRequest('/meta/logs' + (qs.toString() ? '?' + qs.toString() : ''));
  },

  // 导出 / 导入
  exportData: (branch = 'main') => request('/meta/export?branch=' + encodeURIComponent(branch)),

  // 批量查询
  batch: async (statements) => {
    return request('/query/batch', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ statements })
    });
  },

  // 查询 — 直接返回数据数组（后端 /query 响应本身就是数组）
  // 注意:后端会把 commit 字段当成字符串处理,"null" 字符串无法区分真正的 null,
  // 所以 commit 为空时直接不发送该字段,避免触发"Unknown commit: null" 错误。
  query: (cypher, branch = 'main', commit = null) => {
    const body = commit ? { cypher, branch, commit } : { cypher, branch };
    return request('/query', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }).then(r => r.data);
  }
};

export function formatTimestamp(epochMillis) {
  if (!epochMillis) return '';
  try {
    const d = new Date(Number(epochMillis));
    return d.toLocaleString();
  } catch (e) {
    return String(epochMillis);
  }
}

export function shortHash(id, length = 8) {
  if (!id) return '';
  return id.length > length ? id.substring(0, length) : id;
}
