import { BranchesOutlined, CodeOutlined, DashboardOutlined, DatabaseOutlined, FileTextOutlined, HistoryOutlined, HomeOutlined, PartitionOutlined, ProfileOutlined } from '@ant-design/icons'
import './console/styles.css'
import {withGraphServer, withGraphConsole} from './console/serverHook.jsx'
import Dashboard from './console/pages/Dashboard.jsx'
import GraphView from './console/pages/GraphView.jsx'
import Branches from './console/pages/Branches.jsx'
import Commits from './console/pages/Commits.jsx'
import QueryPage from './console/pages/QueryPage.jsx'
import Schema from './console/pages/Schema.jsx'
import LogViewer from './console/pages/LogViewer.jsx'
import ApiDocs from './console/pages/ApiDocs.jsx'
import InstanceStatus from './pages/InstanceStatus.jsx'


export {withGraphServer, withGraphConsole, useGraphServer} from './console/serverHook.jsx'
export {api} from './console/api'
import HomePage from './pages/HomePage'
import GraphApp from './pages/GraphApp.jsx'

/** 菜单 + 路由清单（lead 008 §10/§14/§16 批量落地）。App 壳在 suit 侧组装。 */
export const appMeta = { title: 'z-graph 图存储控制台', short: 'z-graph' }

export const menuItems = [
    { key: '/z-graph/home', label: '首页', icon: <HomeOutlined /> },
    { key: '/z-graph/dashboard', label: '总览', icon: <DashboardOutlined /> },
    { key: '/z-graph/graph', label: '图视图', icon: <PartitionOutlined /> },
    { key: '/z-graph/branches', label: '分支', icon: <BranchesOutlined /> },
    { key: '/z-graph/commits', label: '提交历史', icon: <HistoryOutlined /> },
    { key: '/z-graph/query', label: 'Cypher 查询', icon: <CodeOutlined /> },
    { key: '/z-graph/schema', label: 'Schema', icon: <DatabaseOutlined /> },
    { key: '/z-graph/logs', label: '请求日志', icon: <ProfileOutlined /> },
    { key: '/z-graph/api-docs', label: 'API 文档', icon: <FileTextOutlined /> },
]

export const routes = [
    { path: '/z-graph/home', Component: HomePage },
    // 旧版 console 页面（吃 src/console/styles.css）⇒ 必须走 withGraphConsole，
    // 否则那份样式表里的裸元素选择器会泄漏到主壳（详见 console/serverHook.jsx 注释）。
    { path: '/z-graph/dashboard', Component: withGraphConsole(Dashboard) },
    { path: '/z-graph/graph', Component: withGraphConsole(GraphView) },
    { path: '/z-graph/branches', Component: withGraphConsole(Branches) },
    { path: '/z-graph/commits', Component: withGraphConsole(Commits) },
    { path: '/z-graph/query', Component: withGraphConsole(QueryPage) },
    { path: '/z-graph/schema', Component: withGraphConsole(Schema) },
    { path: '/z-graph/logs', Component: withGraphConsole(LogViewer) },
    { path: '/z-graph/api-docs', Component: withGraphConsole(ApiDocs) },
    // 新版页面（antd 实现）不吃 console 样式表 ⇒ 不加作用域
    { path: '/z-graph/instance', Component: withGraphServer(InstanceStatus) },
    // catchall 放最后 —— 否则 `:rest*` 会先于具体路由匹配,导致 /z-graph/query
    // 全部跳进 GraphApp 的内层 /schema 路由(URL 变成 /z-graph/query/schema)。
    { path: '/z-graph/:rest*', Component: GraphApp },
]

export { default as HomePage } from './pages/HomePage'
export { default as LoginPage } from './pages/LoginPage'
