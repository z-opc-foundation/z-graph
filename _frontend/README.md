# _frontend

z-graph 现有前端是 `../z-graph-console/`（Maven 模块外的 Vite 工程），按 lead 005
§9 规范应迁入 `_frontend/z-graph-suit/`。本目录先建一个最小空壳（Hello + AppLayout），
保证仓可独立 `npm run dev` 跑起来。

迁移路径：把 `z-graph-console/src` 的页面拆为 `z-graph-component/src/pages/*` + 配置
`pages-manifest.jsx` + `pages.js`，`z-graph-suit` 通过 `import {routeTable} from
'@yuku123/z-graph-component/pages'` 组装。模板与 z-meta / z-mist 第五批次同。
