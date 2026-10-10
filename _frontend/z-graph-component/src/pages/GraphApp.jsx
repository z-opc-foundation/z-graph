import {Navigate, Route, Routes} from 'react-router-dom'
import SchemaPage from './SchemaPage'
import QueryConsole from './QueryConsole'
import BranchCommitPage from './BranchCommitPage'
import InstanceStatus from './InstanceStatus'

/**
 * z-graph（图数据库）管理面 — OpsWorkbench 以 /graph/* 通配挂进来。
 *
 * 数据一律经 z-opc 的 GraphProxyController（/api/graph/**），它只转发给**本 JVM 内嵌
 * bind 成功的那个端口**：bind 没成功就直接 503，不会退化成"连 8090 试试看"——
 * 那样会把外部常驻进程（实测 PID 1794 的 z-graph-bolt-server）的应答读成自己的。
 * 所以这四个页面的"空数据"都有两种含义，页面用 /api/graph/__instance 把它们分开。
 */
export default function GraphApp() {
    return (
        <Routes>
            <Route index element={<Navigate to="schema" replace/>}/>
            <Route path="schema" element={<SchemaPage/>}/>
            <Route path="query" element={<QueryConsole/>}/>
            <Route path="branches" element={<BranchCommitPage/>}/>
            <Route path="instance" element={<InstanceStatus/>}/>
        </Routes>
    )
}
