import { Component, useEffect, useState } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AppLayout } from '@yuku123/z-frontend-common'
import { menuItems, routes, HomePage, LoginPage } from '@yuku123/z-graph-component/pages'
import { Result, Button } from 'antd'
// 版本号只认 package.json，避免顶栏版本与发版号两处各写一遍慢慢漂移。
import { version as appVersion } from '../package.json'

class ErrorBoundary extends Component {
    constructor(props) { super(props); this.state = { err: null } }
    static getDerivedStateFromError(err) { return { err } }
    componentDidCatch(err, info) { console.error('[App ErrorBoundary]', err, info) }
    render() {
        if (this.state.err) return <pre style={{ padding: 24, color: 'red', whiteSpace: 'pre-wrap' }}>{String(this.state.err?.stack || this.state.err)}</pre>
        return this.props.children
    }
}

function NotFound() {
    return (
        <Result
            status="404"
            title="页面不存在"
            subTitle="路由表里没有这条路径。"
            extra={<Button type="primary" onClick={() => { window.location.href = '/z-graph/home' }}>去首页</Button>}
        />
    )
}

// lead 008 §16：CTC/SSO 接入前的 mock 登录。
// mock 账号不写死在仓里（001 凭证规范）：构建期注入 VITE_MOCK_USER / VITE_MOCK_PASSWORD，
// 未注入 ⇒ 不传 mockAuth，登录页提示「未配置 mock 账号」。
const MOCK_AUTH = import.meta.env.VITE_MOCK_USER && import.meta.env.VITE_MOCK_PASSWORD
    ? {
        username: import.meta.env.VITE_MOCK_USER,
        password: import.meta.env.VITE_MOCK_PASSWORD,
        user: { name: import.meta.env.VITE_MOCK_USER, role: '管理员' },
    }
    : null

function LoginRoute() {
    return <LoginPage mockAuth={MOCK_AUTH} />
}

function ProtectedShell() {
    const [user, setUser] = useState(null)
    const [ready, setReady] = useState(false)

    useEffect(() => {
        if (!localStorage.getItem('token')) {
            window.location.replace('/z-graph/login')
            return
        }
        const raw = localStorage.getItem('userInfo')
        if (raw) { try { setUser(JSON.parse(raw)) } catch { setUser({ name: raw }) } }
        setReady(true)
    }, [])

    if (!ready) return null

    return (
        <AppLayout
            menuItems={menuItems}
            appTitle="z-graph 图存储控制台"
            appVersion={appVersion}
            appUser={user}
            appIcon={{ icon: <img src="/icon.png" alt="z-graph 图存储控制台" style={{ width: '100%', height: '100%', objectFit: 'cover', borderRadius: 8 }} />, color: '#f97316', label: 'z-graph 图存储控制台' }}
        />
    )
}

/** lead 008 §16 suit 一次整合：登录路由 + 鉴权壳 + URL 即状态（§11/§14）。 */
export default function App() {
    return (
        <ErrorBoundary>
            <BrowserRouter>
                <Routes>
                    <Route path="/z-graph/login" element={<LoginRoute />} />
                    <Route element={<ProtectedShell />}>
                        <Route path="/" element={<Navigate to={menuItems[0].key} replace />} />
                        {routes.map((r) => (
                            <Route key={r.path} path={r.path} element={<r.Component />} />
                        ))}
                        <Route path="*" element={<NotFound />} />
                    </Route>
                </Routes>
            </BrowserRouter>
        </ErrorBoundary>
    )
}