import { Component, useEffect, useState } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AppLayout } from '@yuku123/z-frontend-common'
import { menuItems, routes, HomePage, LoginPage } from '@yuku123/z-graph-component/pages'
import { Result, Button } from 'antd'

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

// lead 008 §16：短期 hard-coded 登录（admin / 123456），CTC/SSO 接入后换共享 LoginPage loginApi。
// 当前 LoginPage 来自 @yuku123/z-frontend-common 0.1.1 —— 它只走 loginApi 调真接口，没有
// mockAuth 钩子；后端又没 /auth/login。所以 suit 侧先把用户名/密码校验在本地做掉，
// 通过校验就直接 setItem 跳走，等 CTC 接 SSO 时把这层换回 LoginPage 原生路径。
const MOCK_AUTH = { username: 'admin', password: '123456', user: { name: 'admin', role: '管理员' } }

// LoginPage 把登录响应 res 透给 onSuccess，但 res 是后端返回的（这里没真后端所以是 undefined）。
// 校验逻辑需要从 form 当前输入里读，强制每次从 DOM 取最新值。
function readLoginForm() {
    const inputs = document.querySelectorAll('input[placeholder]')
    return {
        username: inputs[0]?.value?.trim() || '',
        password: inputs[1]?.value || '',
    }
}

function LoginRoute() {
    const onMockLogin = () => {
        const {username, password} = readLoginForm()
        if (username !== MOCK_AUTH.username || password !== MOCK_AUTH.password) {
            alert('用户名或密码错误（Mock：admin / 123456）')
            return
        }
        localStorage.setItem('token', 'mock-' + Date.now())
        localStorage.setItem('userInfo', JSON.stringify(MOCK_AUTH.user))
        window.location.href = '/z-graph/home'
    }
    return <LoginPage onSuccess={onMockLogin} redirectUrl="/z-graph/home" />
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
            appVersion="0.1.0"
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