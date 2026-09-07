/**
 * z-graph 控制面 HTTP 客户端 — 直接打到 /health /meta/* /query。
 * 默认同源走 /api/* 代理前缀(开发模式由 Vite 代理到 8090),
 * 生产部署时可改成绝对地址,例如 http://z-graph-server:8090。
 */

const DEFAULT_BASE = (typeof window !== 'undefined' && window.__Z_GRAPH_API__)
    || (import.meta.env && import.meta.env.VITE_API_BASE)
    || '/api';

function joinUrl(base, path) {
  if (!base || base === '/api') return '/api' + path;
  return base.replace(/\/$/, '') + path;
}

async function request(path, options = {}) {
  const url = joinUrl(DEFAULT_BASE, path);
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
  baseUrl: () => DEFAULT_BASE,
  health: () => request('/health'),
  branches: () => request('/meta/branches'),
  commits: () => request('/meta/commits'),
  query: (cypher, branch = 'main', commit = null) => {
    const params = new URLSearchParams();
    params.set('cypher', cypher);
    if (commit) params.set('commit', commit); else params.set('branch', branch);
    return request('/query?' + params.toString());
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
