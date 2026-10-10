export {api} from './console/api'
export function configureGraph(config) {
    if (config && config.apiBase !== undefined) {
        api.setBaseUrl(config.apiBase)
    }
}

// §8.7 域目录清退：graph 域 App 挂载点
export { default as GraphApp } from './pages/GraphApp.jsx'
