import {useCallback, useEffect, useMemo, useState} from 'react'
import {Alert, Button, Card, Col, Row, Space, Table, Tag, Tooltip} from 'antd'
import {BranchesOutlined, HistoryOutlined, ReloadOutlined} from '@ant-design/icons'
import {useNavigate} from 'react-router-dom'
import {DEFAULT_BRANCH, graphApi, graphErrorText, isNotBoundError} from '../services/api'
import {EmptyState, PageHeader} from '@/common/components/ui'

function fmtTime(ms) {
    if (ms == null) return '-'
    const d = new Date(ms)
    if (Number.isNaN(d.getTime())) return String(ms)
    return d.toLocaleString('zh-CN', {hour12: false})
}

/**
 * 分支与提交 — GET /api/graph/meta/branches + /api/graph/meta/commits，
 * 分支 head / 点数 / 边数在这两份数据上本地聚合（理由见下面 headByBranch 的注释）。
 *
 * 命名要抠一下，否则这页会被读成别的东西：
 *   · commits 走上游 GraphMetaService.commits() → GraphVersionStore.listCommits()，
 *     是**全量提交图**（所有分支的 commit 混在一起，靠每条的 branch 字段区分），不是某分支的历史；
 *     上游没有暴露按分支取历史的端点（GraphVersionStore.log(ref) 存在，但没注册路由）。
 *   · 上游另有一个 /api/graph/meta/logs，那是 **HTTP 访问日志**（环形缓冲 500 条），
 *     与提交历史无关 —— 它在「实例与端口」页。别把两者当一个东西。
 *   · handleBranches 只回名字数组，所以"分支 → head + 点数边数"没有任何一次成型的端点。
 */
export default function BranchCommitPage() {
    const navigate = useNavigate()
    const [branches, setBranches] = useState([])
    const [commits, setCommits] = useState([])
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)
    const [filter, setFilter] = useState(null)

    const fetch = useCallback(async () => {
        setLoading(true)
        try {
            const [bs, cs] = await Promise.all([graphApi.branches(), graphApi.commits()])
            if (!Array.isArray(bs)) throw new Error(`branches 应为字符串数组，拿到 ${JSON.stringify(bs)}`)
            if (!Array.isArray(cs)) throw new Error(`commits 应为对象数组，拿到 ${JSON.stringify(cs)}`)
            setBranches(bs)
            setCommits(cs)
            setError(null)
        } catch (e) {
            setBranches([])
            setCommits([])
            setError(e)
        } finally {
            setLoading(false)
        }
    }, [])

    useEffect(() => {
        fetch()
    }, [fetch])

    /** commit id → commit，用来把 parents 渲染成可 hover 的祖先链接 */
    const byId = useMemo(() => {
        const m = new Map()
        commits.forEach((c) => m.set(c.id, c))
        return m
    }, [commits])

    /**
     * 分支 head 在本地算（每个 branch 取 timestamp 最大的一条）。
     * 上游没有"分支 → head + 点数边数"的列表端点：handleBranches 只回名字数组，
     * 带 head 的是 /meta/stats?branch=<b>（一个分支一次请求）。这里改成一次 /meta/commits
     * 全量拉回本地聚合，请求数是 2 而不是 1+N；代价是 head 的判定依据从"分支指针"变成
     * "该分支最新时间戳的提交"—— 单分支线性历史下两者一致，分叉/合并时以时间戳近似。
     */
    const headByBranch = useMemo(() => {
        const m = new Map()
        commits.forEach((c) => {
            const cur = m.get(c.branch)
            if (!cur || (c.timestamp ?? 0) >= (cur.timestamp ?? 0)) m.set(c.branch, c)
        })
        return m
    }, [commits])

    const visible = useMemo(() => {
        const list = filter ? commits.filter((c) => c.branch === filter) : commits
        // 上游 listCommits() 的顺序是提交产生顺序，页面按时间倒序展示更符合"历史"的读法
        return [...list].sort((a, b) => (b.timestamp ?? 0) - (a.timestamp ?? 0))
    }, [commits, filter])

    if (error) {
        return (
            <div>
                <PageHeader title="分支与提交" subtitle="GET /api/graph/meta/branches + /api/graph/meta/commits"/>
                <Alert type={isNotBoundError(error) ? 'error' : 'warning'} showIcon
                       message={`提交历史读取失败：${graphErrorText(error)}`}
                       description={isNotBoundError(error)
                           ? '503 = 本 JVM 的内嵌 GraphControlServer 没 bind 成功（不是「图里没有提交」）。去「实例与端口」页确认 boundPort 与 foreignListenerSuspected。'
                           : undefined}/>
            </div>
        )
    }

    return (
        <div>
            <PageHeader title="分支与提交"
                        subtitle="z-graph 的 Git 式版本面：分支 head 与全量 commit 图（数据源 /api/graph/meta/branches、/meta/commits）"/>
            <Card title={<Space><BranchesOutlined/>分支（{branches.length}）</Space>}
                  extra={<Button icon={<ReloadOutlined/>} loading={loading} onClick={fetch}>刷新</Button>}>
                <Row gutter={[16, 16]}>
                    {branches.length === 0 && !loading && (
                        <Col span={24}>
                            <EmptyState title="一个分支都没有"
                                        description="GET /api/graph/meta/branches 是通的、返回了空数组。内嵌实例若刚启动，GraphVersionStore 构造函数会先落一个 Initial graph 根提交并把 main 指过去，所以真为空通常说明连的不是你以为的那个实例。"/>
                        </Col>
                    )}
                    {branches.map((b) => {
                        const head = headByBranch.get(b)
                        const count = commits.filter((c) => c.branch === b).length
                        return (
                            <Col key={b} span={8}>
                                <Card size="small"
                                      hoverable
                                      onClick={() => setFilter(filter === b ? null : b)}
                                      title={<Space><BranchesOutlined/><code>{b}</code></Space>}
                                      extra={filter === b ? <Tag color="processing">已筛选</Tag> : null}>
                                    <Space direction="vertical" size={2} style={{width: '100%'}}>
                                        <span style={{fontSize: 12}}>
                                            head：<code>{head ? `${head.id.slice(0, 12)}…` : '未知'}</code>
                                        </span>
                                        <span style={{fontSize: 12, color: '#64748b'}}>
                                            本分支提交 {count} · 点 {head?.nodeCount ?? '-'} · 边 {head?.edgeCount ?? '-'}
                                        </span>
                                        <span style={{fontSize: 12, color: '#64748b'}}>
                                            最近提交：{head ? fmtTime(head.timestamp) : '-'}
                                        </span>
                                    </Space>
                                </Card>
                            </Col>
                        )
                    })}
                </Row>
            </Card>

            <Card style={{marginTop: 16}}
                  title={<Space><HistoryOutlined/>提交历史（全量 commit 图，{visible.length} 条）</Space>}
                  extra={filter
                      ? <Space><Tag color="processing" closable onClose={() => setFilter(null)}>
                          branch = {filter}
                        </Tag></Space>
                      : <span style={{fontSize: 12, color: '#64748b'}}>点分支卡片可筛选</span>}>
                <Table size="small" rowKey="id" loading={loading} dataSource={visible}
                       pagination={{size: 'small', pageSize: 20, showSizeChanger: true}}
                       columns={[
                           {
                               title: 'commit', dataIndex: 'id', key: 'id', width: 170,
                               render: (v) => <Tooltip title={v}><code>{`${String(v).slice(0, 12)}…`}</code></Tooltip>
                           },
                           {
                               title: '分支', dataIndex: 'branch', key: 'branch', width: 110,
                               render: (v) => <Tag color={v === DEFAULT_BRANCH ? 'blue' : 'geekblue'}>{v}</Tag>
                           },
                           {title: '作者', dataIndex: 'author', key: 'author', width: 110},
                           {
                               title: '说明', dataIndex: 'message', key: 'message',
                               ellipsis: {showTitle: false},
                               render: (v) => <Tooltip title={v} placement="topLeft">
                                   <span>{v || '-'}</span></Tooltip>
                           },
                           {title: '时间', dataIndex: 'timestamp', key: 'timestamp', width: 190, render: fmtTime},
                           {title: '点', dataIndex: 'nodeCount', key: 'nodeCount', width: 70},
                           {title: '边', dataIndex: 'edgeCount', key: 'edgeCount', width: 70},
                           {
                               title: '父提交', dataIndex: 'parents', key: 'parents', width: 150,
                               render: (ps) => !Array.isArray(ps) || ps.length === 0
                                   ? <Tag>根提交</Tag>
                                   : <Space direction="vertical" size={0}>
                                       {ps.map((p) => (
                                           <Tooltip key={p}
                                                    title={byId.has(p) ? `${byId.get(p).message} · ${fmtTime(byId.get(p).timestamp)}` : p}>
                                               <code style={{fontSize: 12}}>{`${String(p).slice(0, 8)}…`}</code>
                                           </Tooltip>
                                       ))}
                                   </Space>
                           },
                           {
                               title: '操作', key: 'action', width: 130,
                               render: (_, r) => (
                                   <Button type="link" size="small"
                                           onClick={() => navigate(`/graph/query?branch=${encodeURIComponent(r.branch)}&commit=${encodeURIComponent(r.id)}`)}>
                                       按此版本查
                                   </Button>
                               )
                           },
                       ]}
                       locale={{
                           emptyText: <EmptyState title="没有任何提交"
                                                  description="GET /api/graph/meta/commits 通了但返回空数组 —— 上游 GraphVersionStore 构造时至少会有一个 Initial graph 根提交，所以这一格为空要去「实例与端口」页确认应答者。"/>
                       }}/>
            </Card>
        </div>
    )
}
