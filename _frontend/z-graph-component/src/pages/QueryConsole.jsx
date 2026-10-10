import {useCallback, useMemo, useRef, useState} from 'react'
import {Alert, Button, Card, Col, Input, Row, Select, Space, Statistic, Table, Tag} from 'antd'
import {PlayCircleOutlined, ThunderboltOutlined} from '@ant-design/icons'
import {useSearchParams} from 'react-router-dom'
import {DEFAULT_BRANCH, graphApi, graphErrorText, isMutatingCypher} from '../services/api'
import {EmptyState, PageHeader} from '@/common/components/ui'

const {TextArea} = Input

const SAMPLES = [
    {label: '全量取样：MATCH (n) RETURN n LIMIT 20', value: 'MATCH (n) RETURN n LIMIT 20'},
    {label: 'Schema：SHOW TAGS', value: 'SHOW TAGS'},
    {label: 'Schema：SHOW EDGES', value: 'SHOW EDGES'},
    {label: 'Schema：SHOW INDEXES', value: 'SHOW INDEXES'},
    {label: '统计：CALL db.stats()', value: 'CALL db.stats()'},
    {label: '版本：SHOW BRANCHES', value: 'SHOW BRANCHES'},
    {label: '定长跳：MATCH (a)-[r*1..2]->(b) RETURN a,r,b LIMIT 20', value: 'MATCH (a)-[r*1..2]->(b) RETURN a,r,b LIMIT 20'},
]

function isPrimitive(v) {
    return v === null || ['string', 'number', 'boolean'].includes(typeof v)
}

function renderCell(v) {
    if (v === null || v === undefined) return <span style={{color: '#94a3b8'}}>null</span>
    if (isPrimitive(v)) return String(v)
    return <code style={{fontSize: 12, wordBreak: 'break-all'}}>{JSON.stringify(v)}</code>
}

/**
 * 图查询 — GET /api/graph/query?cypher=…&branch=…[&commit=…]，
 * 以及 POST /api/graph/query/explain 的执行计划。
 *
 * 三处不是随手写的地方：
 * 1. **走 GET 不走 POST**：GraphControlServer 的 POST 分支用手搓的 parseJsonStringMap 解析
 *    JSON body（按引号外逗号切 token、按第一个冒号切 kv），而 GET 分支用 URLDecoder。
 *    含 `:`（如 `MATCH (n:Person)`）或嵌套结构的语句只有前者会被啃坏，所以统一走 GET。
 * 2. **表头是动态的**：返回是 `List<Map<String,Object>>`，列名 = 所有行 key 的并集
 *    （保持首现顺序，因为上游用的是 LinkedHashMap）。写死列名就等于把示例数据焊进页面。
 * 3. **commit 参数是时间旅行**：handleQuery 里 commit 非空时走 repository.checkout(commit)
 *    而**忽略 branch**，所以对写语句传 commit 会被静默当成只读处理 —— 这里在写语句下禁掉它。
 */
export default function QueryConsole() {
    // 「分支与提交」页的「按此版本查」会带 ?branch=&commit= 过来，这里一次性读进初值
    const [sp] = useSearchParams()
    const [cypher, setCypher] = useState(sp.get('cypher') || 'MATCH (n) RETURN n LIMIT 20')
    const [branch, setBranch] = useState(sp.get('branch') || DEFAULT_BRANCH)
    const [commit, setCommit] = useState(sp.get('commit') || '')
    const [rows, setRows] = useState(null)
    const [plan, setPlan] = useState(null)
    const [error, setError] = useState(null)
    const [running, setRunning] = useState(false)
    const [explaining, setExplaining] = useState(false)
    const [elapsedMs, setElapsedMs] = useState(null)
    const [pendingWrite, setPendingWrite] = useState(false)
    const startedAt = useRef(0)

    const mutating = useMemo(() => isMutatingCypher(cypher), [cypher])

    const run = useCallback(async () => {
        setRunning(true)
        startedAt.current = performance.now()
        try {
            const res = await graphApi.query(cypher, branch, commit.trim() || undefined)
            if (!Array.isArray(res)) {
                throw new Error(`期望行数组，拿到 ${JSON.stringify(res)}`)
            }
            setRows(res)
            setPlan(null)
            setError(null)
            setElapsedMs(Math.round(performance.now() - startedAt.current))
            setPendingWrite(false)
        } catch (e) {
            setRows(null)
            setError(e)
            setElapsedMs(null)
        } finally {
            setRunning(false)
        }
    }, [cypher, branch, commit])

    const onRun = () => {
        if (mutating && !pendingWrite) {
            // 上游对写语句会开事务、提交并产生一个新 commit（GraphQueryService.beginWrite + tx.commit），
            // 这一步在图历史里不可撤回，所以第一次点击只出确认条，不直接执行。
            setPendingWrite(true)
            return
        }
        run()
    }

    const onExplain = useCallback(async () => {
        setExplaining(true)
        try {
            const res = await graphApi.explain(cypher, branch)
            setPlan(res)
            setError(null)
        } catch (e) {
            setError(e)
            setPlan(null)
        } finally {
            setExplaining(false)
        }
    }, [cypher, branch])

    const columns = useMemo(() => {
        if (!Array.isArray(rows)) return []
        const keys = []
        const seen = new Set()
        rows.forEach((r) => Object.keys(r || {}).forEach((k) => {
            if (!seen.has(k)) { seen.add(k); keys.push(k) }
        }))
        return keys.map((k, i) => ({
            title: k, dataIndex: k, key: k, ellipsis: false,
            width: keys.length > 4 ? undefined : 240,
            render: (v) => renderCell(v),
            // 上游行对象允许同名 key 冲突吗：不会，Map 的 key 唯一；这里用序号兜底防止空 key
            ...(k === '' ? {title: `(空列名 #${i})`} : {}),
        }))
    }, [rows])

    return (
        <div>
            <PageHeader title="图查询"
                        subtitle="Cypher 执行与执行计划（数据源 GET /api/graph/query、POST /api/graph/query/explain）"/>
            <Row gutter={16}>
                <Col span={16}>
                    <Card title="查询语句">
                        <Space direction="vertical" style={{width: '100%'}} size={12}>
                            <Space wrap>
                                <span style={{fontSize: 12, color: '#64748b'}}>插入示例</span>
                                <Select size="small" style={{width: 340}} placeholder="常用只读查询"
                                        showSearch optionFilterProp="label"
                                        options={SAMPLES.map((s) => ({value: s.value, label: s.label}))}
                                        onChange={(v) => { setCypher(v); setPendingWrite(false) }}/>
                            </Space>
                            <TextArea value={cypher} rows={4} spellCheck={false}
                                      style={{fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace'}}
                                      onChange={(e) => { setCypher(e.target.value); setPendingWrite(false) }}
                                      placeholder="MATCH (n:Person)-[r:KNOWS]->(m) RETURN n.name, r.since, m.name LIMIT 50"/>
                            <Space wrap>
                                <span style={{fontSize: 12, color: '#64748b'}}>分支</span>
                                <Input size="small" style={{width: 140}} value={branch}
                                       onChange={(e) => setBranch(e.target.value || DEFAULT_BRANCH)}/>
                                <span style={{fontSize: 12, color: '#64748b'}}>或按 commit 时间旅行（留空=按分支 head）</span>
                                <Input size="small" style={{width: 260}} value={commit}
                                       disabled={mutating}
                                       placeholder={mutating ? '写语句不能带 commit' : '40 位 commit id'}
                                       onChange={(e) => setCommit(e.target.value.trim())}/>
                            </Space>
                            {mutating && (
                                <Alert type="warning" showIcon
                                       message="这条语句会被上游判定为写操作"
                                       description={
                                           pendingWrite
                                               ? '已确认。再点一次「执行」就会真的改图：GraphControlServer 对写语句开一个事务并提交，产生一个新的不可撤回 commit。'
                                               : '上游按前缀/关键字判定写（CREATE / MERGE / MATCH…SET|DELETE / DETACH DELETE），执行时会开事务并落一个新 commit。第一次点击只出这条确认，不会执行。'
                                       }/>
                            )}
                            <Space>
                                <Button type="primary" icon={<PlayCircleOutlined/>} loading={running}
                                        danger={mutating && pendingWrite}
                                        onClick={onRun}>
                                    {mutating && pendingWrite ? '确认执行（会改图）' : '执行'}
                                </Button>
                                <Button icon={<ThunderboltOutlined/>} loading={explaining} onClick={onExplain}>
                                    执行计划
                                </Button>
                            </Space>
                        </Space>
                    </Card>
                </Col>
                <Col span={8}>
                    <Card title="本次结果">
                        <Row gutter={16}>
                            <Col span={8}><Statistic title="返回行数" value={rows ? rows.length : '-'}/></Col>
                            <Col span={8}><Statistic title="往返耗时" suffix="ms"
                                                     value={elapsedMs == null ? '-' : elapsedMs}/></Col>
                            <Col span={8}><Statistic title="列数" value={columns.length || '-'}/></Col>
                        </Row>
                        <div style={{marginTop: 12, fontSize: 12, color: '#64748b'}}>
                            耗时是前端 axios 往返（含 8888 的代理一跳），不是上游 <code>X-Response-Time</code>；
                            后者是跨域响应头，axios 读不到，不拿它当自己的数。
                        </div>
                    </Card>
                    {plan && (
                        <Card style={{marginTop: 16}} title="执行计划 (POST /query/explain)"
                              extra={<Space size={4}>
                                  <Tag>{plan.plan?.queryType ?? '-'}</Tag>
                                  <Tag color={plan.plan?.estimatedComplexity === 'HIGH' ? 'error'
                                      : plan.plan?.estimatedComplexity === 'MEDIUM' ? 'warning' : 'success'}>
                                      {plan.plan?.estimatedComplexity ?? '-'}
                                  </Tag>
                              </Space>}>
                            <Table size="small" rowKey={(r, i) => `${r.operation}-${i}`}
                                   dataSource={plan.plan?.steps ?? []} pagination={false}
                                   columns={[
                                       {title: '算子', dataIndex: 'operation', key: 'operation', width: 140},
                                       {title: '说明', dataIndex: 'description', key: 'description'},
                                   ]}
                                   locale={{emptyText: <EmptyState title="上游没有给出算子"/>}}/>
                            <div style={{marginTop: 8, fontSize: 12, color: '#64748b'}}>
                                这份计划是 <code>handleExplain</code> 按字符串前缀 + 关键字命中拼出来的
                                （MATCH→Scan、含 WHERE→Filter…），<b>不是代价估算器</b>；
                                复杂度只有 HIGH/MEDIUM/LOW 三档。别按真实执行成本解读。
                            </div>
                        </Card>
                    )}
                </Col>
            </Row>

            <Card style={{marginTop: 16}} title={rows ? `结果集（${rows.length} 行）` : '结果集'}>
                {error ? (
                    <Alert type="error" showIcon message={`查询失败：${graphErrorText(error)}`}
                           description="上游所有 catch 分支都只回 {error: msg}：Cypher 语法错、Tag 不存在、head 冲突（409 Stale head）都在这里。503 则是内嵌实例没 bind。"/>
                ) : (
                    <Table size="small" rowKey={(r, i) => i} loading={running} dataSource={rows ?? []}
                           columns={columns} scroll={{x: 'max-content'}}
                           pagination={{size: 'small', pageSize: 20, showSizeChanger: true}}
                           locale={{
                               emptyText: rows === null
                                   ? <EmptyState title="还没有执行过查询" description="点「执行」或「执行计划」。这里没有预置示例数据。"/>
                                   : <EmptyState title="返回 0 行" description="查询成功执行、上游确实给了空数组（空图或未命中）。"/>
                           }}/>
                )}
            </Card>

            {rows && rows.length > 0 && (
                <Card style={{marginTop: 16}} title="原始响应（第一行，逐字段照上游 JSON 渲染）">
                    <pre style={{margin: 0, fontSize: 12, overflow: 'auto', maxHeight: 260}}>
                        {JSON.stringify(rows[0], null, 2)}
                    </pre>
                </Card>
            )}
        </div>
    )
}
