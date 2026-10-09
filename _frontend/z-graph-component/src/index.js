export {api} from './console/api'
export function configureGraph(config) {
    if (config && config.apiBase !== undefined) {
        api.setBaseUrl(config.apiBase)
    }
}
