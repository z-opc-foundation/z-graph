/** 控制面健康/指标轮询 hook（原 console App.jsx 侧栏逻辑抽离，供页面包装器共用）。 */
import {useEffect, useState} from 'react'
import {api} from './api'

export function useGraphServer(apiBase) {
    const [server, setServer] = useState({
        status: 'unknown', head: null, nodeCount: 0, edgeCount: 0, error: null,
    })
    const [metrics, setMetrics] = useState(null)

    useEffect(() => {
        let cancelled = false
        async function load() {
            try {
                const [health, metricsData] = await Promise.all([
                    api.health(),
                    api.metrics().catch(() => null),
                ])
                if (cancelled) return
                setServer({
                    status: 'up', head: health.head,
                    nodeCount: health.nodeCount || 0, edgeCount: health.edgeCount || 0,
                    error: null,
                })
                setMetrics(metricsData || null)
            } catch (err) {
                if (cancelled) return
                setServer({status: 'down', head: null, nodeCount: 0, edgeCount: 0, error: err.message})
                setMetrics(null)
            }
        }
        load()
        const timer = setInterval(load, 5000)
        return () => { cancelled = true; clearInterval(timer) }
    }, [apiBase])

    return {server, metrics}
}

/** 页面包装器：console 页面都吃 {server} prop，这里统一供给。 */
export function withGraphServer(Page) {
    return function GraphServerPage() {
        const {server, metrics} = useGraphServer()
        return <Page server={server} metrics={metrics}/>
    }
}

/**
 * 旧版 console 页面包装器：在 withGraphServer 之外再套一层 .zgc 作用域。
 *
 * ⚠ 为什么必须有这层（2026-10-22 实测，勿删）：
 *   旧版 console 页面用的是 src/console/styles.css —— 那份样式表原本是
 *   「独立 SPA 全局样式」（裸元素选择器 button / input / a / table）。
 *   本组件的 CSS 会被主壳 z-opc 当**全局样式**注入
 *   （z-opc/.../frontend/src/styles/vendorStyles.js 里 `?url` 引入 dist CSS），
 *   于是 `button:hover{background:#334155}` 泄漏到整个主壳：
 *   z-team 工作台标签栏 .tab{background:none}（特异度 0,1,0）打不过
 *   button:hover（0,1,1）⇒ 悬停时标签变深色块，而文字仍是深色 ⇒ 看不见。
 *
 *   ⇒ 旧版页面统一挂到 .zgc 下；样式表已整体加作用域（见 styles.css 头注释）。
 *   ⇒ 新版页面（src/pages/*，antd 实现）不加作用域：它们不吃这份样式。
 *
 * display:contents ⇒ 作用域容器不生成盒子，不改变宿主容器的 flex/grid 布局。
 */
export function withGraphConsole(Page) {
    const Inner = withGraphServer(Page)
    return function GraphConsolePage() {
        return <div className="zgc"><Inner/></div>
    }
}
