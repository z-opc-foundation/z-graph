import React from 'react'
import ReactDOM from 'react-dom/client'
import {ConfigProvider} from 'antd'
import zhCN from 'antd/locale/zh_CN'
import App from './App'
import { configureGraph } from '@yuku123/z-graph-component'
import 'antd/dist/reset.css'

// 注入 axios + console api 双端 baseURL = '/api'，与 vite proxy / 宿主后端同源。
// 组件侧默认就是 '/api'，这里显式调一次以便宿主改前缀时只改这一行。
configureGraph('/api')

// BrowserRouter 放在 App.jsx 里（suit 已经有），main.jsx 不要重复包，
// 否则会触发 "You cannot render a <Router> inside another <Router>"。
ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <ConfigProvider locale={zhCN}>
      <App />
    </ConfigProvider>
  </React.StrictMode>,
)
