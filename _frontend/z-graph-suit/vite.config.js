import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

// LOCAL_SIBLINGS=1 时把 @yuku123/z-graph-component 别名到 ../z-graph-component/src，
// 避免 npm install + file: 协议（lead §8.5 不进 git）。本机 dev 必备。
// 不传环境变量时按 npm registry 版本走，CI 场景。
const LOCAL_SIBLINGS = process.env.LOCAL_SIBLINGS === '1'
const componentSrc = path.resolve(__dirname, '../z-graph-component/src')

export default defineConfig({
  plugins: [react()],
  resolve: {
    dedupe: ['react', 'react-dom', 'react-router-dom', 'antd', '@ant-design/icons', 'axios'],
    alias: LOCAL_SIBLINGS
      ? {
          '@yuku123/z-graph-component': componentSrc,
          // component 内部 @/common 等别名指 component src（component build 时也是同样映射）。
          // 这里 path.resolve 出绝对路径让 vite 直接吃。
          '@': componentSrc,
        }
      : {},
  },
  server: {
    port: 3023,
    fs: { allow: ['..'] },
    proxy: {
      '/api': {
        target: 'http://localhost:8090',
        changeOrigin: true,
        // GraphControlServer 的路径是 /health /meta/* /query,
        // 不带 /api 前缀；浏览器走 /api/* 时把前缀剥掉。
        rewrite: (p) => p.replace(/^\/api/, ''),
      },
      '/actuator': { target: 'http://localhost:8888', changeOrigin: true },
    },
  },
})
