import {
    DashboardOutlined,
    PartitionOutlined,
    BranchesOutlined,
    HistoryOutlined,
    CodeOutlined,
    DatabaseOutlined,
    FileTextOutlined,
    ProfileOutlined,
} from '@ant-design/icons'
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

export const menuItems = [
    {key: '/dashboard', icon: <DashboardOutlined/>, label: '总览'},
    {key: '/graph', icon: <PartitionOutlined/>, label: '图视图'},
    {key: '/branches', icon: <BranchesOutlined/>, label: '分支'},
    {key: '/commits', icon: <HistoryOutlined/>, label: '提交历史'},
    {key: '/query', icon: <CodeOutlined/>, label: 'Cypher 查询'},
    {key: '/schema', icon: <DatabaseOutlined/>, label: 'Schema'},
    {key: '/logs', icon: <ProfileOutlined/>, label: '请求日志'},
    {key: '/api-docs', icon: <FileTextOutlined/>, label: 'API 文档'},
]

const routeTable = [
    {path: 'dashboard', Component: withGraphServer(Dashboard)},
    {path: 'graph', Component: withGraphServer(GraphView)},
    {path: 'branches', Component: withGraphServer(Branches)},
    {path: 'commits', Component: withGraphServer(Commits)},
    {path: 'query', Component: withGraphServer(QueryPage)},
    {path: 'schema', Component: withGraphServer(Schema)},
    {path: 'logs', Component: withGraphServer(LogViewer)},
    {path: 'api-docs', Component: withGraphServer(ApiDocs)},
]
export {routeTable}
export {withGraphServer, useGraphServer} from './console/serverHook.jsx'
export {api} from './console/api'
