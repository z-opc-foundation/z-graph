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
  if (!text) return null;
  try { return JSON.parse(text); } catch (e) { return text; }
}

export const api = {
  baseUrl: () => _baseUrl,
  setBaseUrl: (value) => { _baseUrl = value; if (typeof window !== 'undefined') window.__Z_GRAPH_API__ = value; },

  // 健康检查
  health: () => request('/health'),

  // 元数据
  branches: () => request('/meta/branches'),
  commits: () => request('/meta/commits'),
  schema: (branch = 'main') => request('/meta/schema?branch=' + encodeURIComponent(branch)),
  stats: (branch = 'main') => request('/meta/stats?branch=' + encodeURIComponent(branch)),

  // 查询 — 优先 POST (无 URL 长度限制),回退到 GET
  query: async (cypher, branch = 'main', commit = null) => {
    try {
      return await request('/query', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ cypher, branch, commit })
      });
    } catch (e) {
      // 如果服务端不支持 POST,回退 GET
      if (e.message && e.message.includes('405')) {
        const params = new URLSearchParams();
        params.set('cypher', cypher);
        if (commit) params.set('commit', commit); else params.set('branch', branch);
        return request('/query?' + params.toString());
      }
      throw e;
    }
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
