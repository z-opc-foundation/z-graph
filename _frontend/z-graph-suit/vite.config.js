import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  resolve: { dedupe: ['react', 'react-dom', 'react-router-dom', 'antd', '@ant-design/icons', 'axios'] ,
        alias: process.env.LOCAL_SIBLINGS === '1' ? { '@yuku123/z-graph-component': '../z-graph-component/src' } : {}},
  server: {port: 3023, fs: {allow: ['..']}, proxy: {'/api': {target: 'http://localhost:8090', changeOrigin: true}, '/actuator': {target: 'http://localhost:8888', changeOrigin: true}}},
})
