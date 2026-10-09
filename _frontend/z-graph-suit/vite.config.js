import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {port: 3023, fs: {allow: ['..']}, proxy: {'/api': {target: 'http://localhost:8090', changeOrigin: true}, '/actuator': {target: 'http://localhost:8888', changeOrigin: true}}},
})
