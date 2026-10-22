export {api} from './console/api'
export { configureGraph, graphApi, graphErrorText, isNotBoundError, isMutatingCypher, DEFAULT_BRANCH } from './services/api.js'

// §8.7 域目录清退：graph 域 App 挂载点
export { default as GraphApp } from './pages/GraphApp.jsx'
// 组件文档页（lead 008 §7）：主壳按统一名字取，各仓一致
export { default as ComponentDocPage } from './pages/ComponentDoc.jsx'
