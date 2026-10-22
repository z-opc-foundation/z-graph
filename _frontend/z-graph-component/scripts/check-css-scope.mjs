#!/usr/bin/env node
/**
 * 守卫：z-graph 组件的产物 CSS 里**不允许出现裸元素选择器**。
 *
 * ⚠ 为什么必须有这个守卫（2026-10-22 实测）：
 *   组件 dist 的 CSS 会被主壳 z-opc 当**全局样式**注入
 *   （z-opc/bootstraps/z-opc-main-starter-frontend/src/styles/vendorStyles.js
 *     里 `import zGraphCss from '@yuku123/z-graph-component/dist/z-graph-component.css?url'`，
 *     进工作台时插 <link> 到 <head>）。
 *   一旦产物里出现 `button:hover{background:#334155}` 这类裸元素规则，
 *   就会泄漏到整个主壳：z-team 工作台标签栏 .tab{background:none}（特异度 0,1,0）
 *   打不过 button:hover（0,1,1）⇒ 悬停时标签变深色块、文字（--text-primary:#0d0d0d）
 *   看不见。线上已实锤过一次（2026-10-22，opc.zopc.top）。
 *
 * 判定口径：**每个选择器的最左段必须以 .zgc 开头**。
 *   - `.zgc button`      ✅ 作用域内
 *   - `.zgc .card`       ✅
 *   - `button`           ❌ 泄漏
 *   - `html, body, #root`❌ 泄漏（祖先选择器无法加作用域）
 *
 * 退出码：0 = 通过；1 = 有裸选择器（打印具体选择器 + 修法）。
 */
import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const CSS = path.resolve(__dirname, '../dist/z-graph-component.css')
const SCOPE = '.zgc'

/** 把一段 CSS 拆成顶层规则（@media 递归），返回 [{selector, body}] */
function parseTop(src) {
    const rules = []
    let i = 0
    while (i < src.length) {
        while (i < src.length && /\s/.test(src[i])) i++
        if (src.startsWith('/*', i)) {
            const end = src.indexOf('*/', i)
            i = end === -1 ? src.length : end + 2
            continue
        }
        if (i >= src.length) break
        const braceStart = src.indexOf('{', i)
        if (braceStart === -1) break
        const selector = src.slice(i, braceStart).trim()
        let depth = 1
        let j = braceStart + 1
        while (j < src.length && depth > 0) {
            if (src[j] === '{') depth++
            else if (src[j] === '}') depth--
            j++
        }
        rules.push({selector, body: src.slice(braceStart + 1, j - 1)})
        i = j
    }
    return rules
}

if (!fs.existsSync(CSS)) {
    console.error(`[check-css-scope] 找不到产物：${CSS}\n  先跑 npm run build。`)
    process.exit(1)
}

const css = fs.readFileSync(CSS, 'utf8')
const leaks = []

for (const rule of parseTop(css)) {
    const {selector, body} = rule
    if (selector.startsWith('@')) {
        // @media / @supports 等：继续往里看
        for (const inner of parseTop(body)) {
            for (const sel of inner.selector.split(',').map((s) => s.trim()).filter(Boolean)) {
                if (!sel.startsWith(SCOPE)) leaks.push(sel)
            }
        }
        continue
    }
    for (const sel of selector.split(',').map((s) => s.trim()).filter(Boolean)) {
        if (!sel.startsWith(SCOPE)) leaks.push(sel)
    }
}

if (leaks.length > 0) {
    console.error('[check-css-scope] ❌ 产物 CSS 里存在裸选择器，会泄漏到主壳：')
    for (const sel of leaks) console.error(`    ${sel}`)
    console.error('\n  修法：把 src/console/styles.css 的选择器统一挂到 .zgc 下，')
    console.error('        并用 withGraphConsole 包住旧版 console 页面（见 console/serverHook.jsx）。')
    process.exit(1)
}

console.log(`[check-css-scope] ✅ ${path.basename(CSS)} 里没有裸选择器（全部在 ${SCOPE} 作用域内）。`)
