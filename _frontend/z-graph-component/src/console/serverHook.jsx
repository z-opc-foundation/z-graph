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
