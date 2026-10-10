import {Navigate, Route, Routes} from 'react-router-dom'
import {AppLayout} from '../../../../_shared/z-frontend-common-local/dist/z-frontend-common.es.js'
import {menuItems, routeTable} from '@yuku123/z-graph-component/pages'
import '@yuku123/z-graph-component/style.css'

export default function App() {
    return (
        <Routes>
            <Route path="/" element={<Navigate to="/dashboard" replace/>}/>
            <Route path="/" element={
                <AppLayout menuItems={menuItems} appTitle="z-graph 图存储控制台" appShort="GRF" appIcon={{icon: <img src="/icon.png" alt="GRAPH" style={{width: "100%", height: "100%", objectFit: "cover", borderRadius: 8}}/>, color: '#f97316', label: 'GRAPH'}}/>
            }>
                {routeTable.map((r) => (
                    <Route key={r.path} path={r.path} element={<r.Component/>}/>
                ))}
            </Route>
        </Routes>
    )
}
