import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
    plugins: [react()],
    resolve: {
        alias: {
            '@': path.resolve(__dirname, 'src'),
        },
    },
    build: {
        lib: {entry: {index: path.resolve(__dirname, 'src/index.js'), pages: path.resolve(__dirname, 'src/pages.js')}, formats: ['es']},
        outDir: 'dist', emptyOutDir: true,
        // 基座的 /ui、/ui/echarts 子路径也要 external —— rollup 的 external 是精确字符串
        // 匹配，不是前缀匹配。只写主桶时基座 UI 组件会被复制进本包 dist（破坏单一真源）。
        rollupOptions: {external: ['react', 'react-dom', 'react-dom/client', 'react-router-dom', 'antd', '@ant-design/icons', 'axios',
                                   '@yuku123/z-frontend-common',
                                   '@yuku123/z-frontend-common/ui', '@yuku123/z-frontend-common/ui/echarts']},
    },
})
