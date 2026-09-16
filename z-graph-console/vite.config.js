import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Vite 配置:同时构建 React 控制台和把 API 地址代理到 z-graph 控制面,
// 容器里通过 VITE_API_BASE 环境变量覆盖默认 base。
export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_API_PROXY_TARGET || 'http://127.0.0.1:8090',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '')
      }
    }
  },
  preview: {
    host: '0.0.0.0',
    port: 4173
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    sourcemap: true
  }
});
