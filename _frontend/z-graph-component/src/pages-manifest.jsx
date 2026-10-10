import { BranchesOutlined, CodeOutlined, DashboardOutlined, DatabaseOutlined, FileTextOutlined, HistoryOutlined, HomeOutlined, PartitionOutlined, ProfileOutlined } from '@ant-design/icons'
import './console/styles.css'
import {withGraphServer} from './console/serverHook.jsx'
import Dashboard from './console/pages/Dashboard.jsx'
import GraphView from './console/pages/GraphView.jsx'
import Branches from './console/pages/Branches.jsx'
import Commits from './console/pages/Commits.jsx'
import QueryPage from './console/pages/QueryPage.jsx'
import Schema from './console/pages/Schema.jsx'
import LogViewer from './console/pages/LogViewer.jsx'
import ApiDocs from './console/pages/ApiDocs.jsx'


export {withGraphServer, useGraphServer} from './console/serverHook.jsx'
export {api} from './console/api'
import HomePage from './pages/HomePage'

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

export const routeTable = [
    { path: '/z-graph/home', Component: HomePage },
    { path: '/z-graph/dashboard', Component: withGraphServer(Dashboard) },
    { path: '/z-graph/graph', Component: withGraphServer(GraphView) },
    { path: '/z-graph/branches', Component: withGraphServer(Branches) },
    { path: '/z-graph/commits', Component: withGraphServer(Commits) },
    { path: '/z-graph/query', Component: withGraphServer(QueryPage) },
    { path: '/z-graph/schema', Component: withGraphServer(Schema) },
    { path: '/z-graph/logs', Component: withGraphServer(LogViewer) },
    { path: '/z-graph/api-docs', Component: withGraphServer(ApiDocs) },
]

export { default as HomePage } from './pages/HomePage'
export { default as LoginPage } from './pages/LoginPage'
