import {useCallback, useEffect, useMemo, useState} from 'react'
import {Alert, Button, Card, Col, Descriptions, Empty, Modal, Row, Select, Space, Table, Tag} from 'antd'
import {NodeIndexOutlined, ProfileOutlined, ReloadOutlined, TableOutlined} from '@ant-design/icons'
import {DEFAULT_BRANCH, graphApi, graphErrorText, isNotBoundError} from '../services/api'
import {EmptyState, PageHeader} from '@/common/components/ui'

/** /meta/schema 里三类对象的列：字段名逐个对着 CypherEngine.executeShow() 的 row.put(...) 核过 */
const TAG_COLUMNS = [
    {title: '点类型 (Tag)', dataIndex: 'Name', key: 'Name', render: (v) => <Tag color="blue">{v}</Tag>},
    {
        title: '操作', key: 'action', width: 110,
        render: (_, r) => <Button type="link" size="small" onClick={() => r.onFields(r.Name)}>字段</Button>
    },
]
const EDGE_COLUMNS = [
    {title: '边类型 (EdgeType)', dataIndex: 'Name', key: 'Name', render: (v) => <Tag color="purple">{v}</Tag>},
    {
        title: '操作', key: 'action', width: 110,
        render: (_, r) => <Button type="link" size="small" onClick={() => r.onFields(r.Name)}>字段</Button>
    },
]
const INDEX_COLUMNS = [
    {title: '索引名', dataIndex: 'Name', key: 'Name', render: (v) => <code>{v}</code>},
    {
        title: '作用对象类型', dataIndex: 'Kind', key: 'Kind', width: 130,
        render: (v) => v ? <Tag color={v === 'EDGE' ? 'purple' : 'blue'}>{v}</Tag> : '-'
    },
    {title: '挂在', dataIndex: 'On', key: 'On', width: 160},
    {title: '属性', dataIndex: 'Property', key: 'Property', width: 160},
]
/** SHOW TAG/EDGE <name> 的返回列：Field / Type / Null（CypherEngine 1214-1236 行） */
const FIELD_COLUMNS = [
    {title: '字段', dataIndex: 'Field', key: 'Field'},
    {title: '类型', dataIndex: 'Type', key: 'Type', width: 140, render: (v) => <Tag>{v}</Tag>},
    {
        title: '可空', dataIndex: 'Null', key: 'Null', width: 100,
        render: (v) => <Tag color={v === 'NO' ? 'warning' : 'default'}>{v}</Tag>
    },
]

/**
 * 图 Schema — GET /api/graph/meta/schema（+ /meta/stats 汇总、SHOW TAG|EDGE 取字段）。
 *
 * 上游形状（源码级，不是猜的）：
 *   {branch, tags:[{Name}], edges:[{Name}], indexes:[{Name,Kind,On,Property}]}
 * tags/edges 只有名字一列是上游实现如此（CypherEngine.executeShow 只 put("Name", …)），
 * 属性明细要按名字再打一次 `SHOW TAG <n>` / `SHOW EDGE <n>` —— 所以"字段"按钮是第二次请求，
 * 不是本地展开。失败只标那一行，不让整张表变空。
 */
export default function SchemaPage() {
    const [branches, setBranches] = useState([DEFAULT_BRANCH])
    const [branch, setBranch] = useState(DEFAULT_BRANCH)
    const [schema, setSchema] = useState(null)
    const [stats, setStats] = useState(null)
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)
    const [fields, setFields] = useState(null)
    const [fieldsError, setFieldsError] = useState(null)
    const [fieldsLoading, setFieldsLoading] = useState(false)

    const fetch = useCallback(async (b) => {
        setLoading(true)
        try {
            const target = b ?? branch
            // branches 与 schema 是两个上游端点，各自失败各自处理：
            // 拿不到分支列表不该让 schema 页整个报错（默认 main 仍然可用）
            graphApi.branches()
                .then((list) => {
                    if (Array.isArray(list) && list.length > 0) setBranches(list)
                })
                .catch(() => undefined)
            const [s, st] = await Promise.all([
                graphApi.schema(target),
                graphApi.stats(target).catch(() => null),
            ])
            setSchema(s)
            setStats(st)
            setError(null)
        } catch (e) {
            setSchema(null)
            setStats(null)
            setError(e)
        } finally {
            setLoading(false)
        }
    }, [branch])

    useEffect(() => {
        fetch(DEFAULT_BRANCH)
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])

    const openFields = useCallback(async (kind, name) => {
        setFields({kind, name, rows: null})
        setFieldsError(null)
        setFieldsLoading(true)
        try {
            const rows = await graphApi.query(`SHOW ${kind} ${name}`, branch)
            if (!Array.isArray(rows)) {
                throw new Error(`SHOW ${kind} 返回的不是行数组：${JSON.stringify(rows)}`)
            }
            setFields({kind, name, rows})
        } catch (e) {
            setFieldsError(e)
        } finally {
            setFieldsLoading(false)
        }
    }, [branch])

    const statRow = useMemo(() => {
        const list = stats?.stats
        return Array.isArray(list) && list.length > 0 ? list[0] : null
    }, [stats])

    const notBound = isNotBoundError(error)
    const tags = (schema?.tags ?? []).map((t) => ({...t, onFields: (n) => openFields('TAG', n)}))
    const edges = (schema?.edges ?? []).map((e) => ({...e, onFields: (n) => openFields('EDGE', n)}))
    const indexes = schema?.indexes ?? []

    return (
        <div>
            <PageHeader title="图 Schema"
                        subtitle="z-graph 点类型 / 边类型 / 属性索引（数据源 GET /api/graph/meta/schema，由 GraphProxyController 转发到本 JVM 内嵌的 GraphControlServer）"/>
            {error && (
                <Alert style={{marginBottom: 16}} type={notBound ? 'error' : 'warning'} showIcon
                       message={`Schema 读取失败：${graphErrorText(error)}`}
                       description={notBound
                           ? '503 = 本 JVM 的内嵌 GraphControlServer 没 bind 成功（不是「图里没有 schema」）。先去「实例与端口」页看 boundPort 与 foreignListenerSuspected。'
                           : '上游 GraphControlServer 的 handleSchema 内部会跑 SHOW TAGS/EDGES/INDEXES 三次查询，任一抛错就整体 500，错误原文在上面的 message 里。'}/>
            )}
            <Card loading={loading}
                  title="当前分支概览"
                  extra={
                      <Space>
                          <span style={{fontSize: 12, color: '#64748b'}}>分支</span>
                          <Select size="small" style={{width: 160}} value={branch}
                                  options={branches.map((b) => ({value: b, label: b}))}
                                  onChange={(v) => { setBranch(v); fetch(v) }}/>
                          <Button icon={<ReloadOutlined/>} loading={loading} onClick={() => fetch()}>刷新</Button>
                      </Space>
                  }>
                <Descriptions bordered size="small" column={4}
                              items={[
                                  {key: 'branch', label: '分支', children: schema?.branch ?? '-'},
                                  {
                                      key: 'head', label: 'head commit',
                                      children: schema && stats?.head
                                          ? <a title={stats.head}><code>{stats.head.slice(0, 12)}…</code></a> : '-'
                                  },
                                  {key: 'nodes', label: '点数', children: stats?.nodeCount ?? '-'},
                                  {key: 'edges', label: '边数', children: stats?.edgeCount ?? '-'},
                                  {key: 'tags', label: '点类型数', children: schema ? tags.length : '-'},
                                  {key: 'edgeTypes', label: '边类型数', children: schema ? edges.length : '-'},
                                  {key: 'indexes', label: '索引数', children: schema ? indexes.length : '-'},
                                  {
                                      key: 'labelCount', label: '引擎自报统计 (/meta/stats)',
                                      children: statRow
                                          ? <span title={JSON.stringify(statRow)}>
                                              {`标签 ${statRow.labelCount} / 边类型 ${statRow.edgeTypeCount} / 属性索引 ${statRow.propertyIndexCount}`}
                                          </span>
                                          : '-'
                                  },
                              ]}/>
            </Card>

            <Row gutter={16} style={{marginTop: 16}}>
                <Col span={12}>
                    <Card title={<Space><TableOutlined/>点类型 (TAG)</Space>} loading={loading}>
                        <Table size="small" rowKey="Name" dataSource={tags} columns={TAG_COLUMNS}
                               pagination={false} loading={loading}
                               locale={{emptyText: schemaEmptyText(!!schema, '没有点类型')}}/>
                    </Card>
                </Col>
                <Col span={12}>
                    <Card title={<Space><NodeIndexOutlined/>边类型 (EDGE TYPE)</Space>} loading={loading}>
                        <Table size="small" rowKey="Name" dataSource={edges} columns={EDGE_COLUMNS}
                               pagination={false} loading={loading}
                               locale={{emptyText: schemaEmptyText(!!schema, '没有边类型')}}/>
                    </Card>
                </Col>
            </Row>

            <Card style={{marginTop: 16}} title={<Space><ProfileOutlined/>属性索引</Space>} loading={loading}>
                <Table size="small" rowKey="Name" dataSource={indexes} columns={INDEX_COLUMNS}
                       pagination={false} loading={loading}
                       locale={{emptyText: schemaEmptyText(!!schema, '没有属性索引')}}/>
            </Card>

            <Card style={{marginTop: 16}} title="这页为什么可以什么都没有">
                <Empty description={
                    <span style={{fontSize: 12, color: '#64748b', lineHeight: 1.9}}>
                        z-graph 的 schema 由 Cypher 的 DDL 建立，<b>没有独立的建表接口</b>：
                        <code> CREATE TAG name (f TYPE, …)</code> ·
                        <code> CREATE EDGE TYPE name (f TYPE, …)</code> ·
                        <code> CREATE INDEX ON tag(prop)</code>；
                        删除对应 <code>DROP TAG / DROP EDGE TYPE / DROP INDEX</code>（取值与语法以
                        CypherEngine 的实现为准）。<br/>
                        这些语句会<b>真的改图</b>（每个写语句在 GraphQueryService 上开一个事务并提交一个新 commit），
                        所以本页不提供执行入口 —— 要建 schema 请去「图查询」页，那里对写语句有二次确认。
                    </span>
                }/>
            </Card>

            <Modal open={!!fields} width={640}
                   title={fields ? `字段明细 · SHOW ${fields.kind} ${fields.name}` : ''}
                   footer={null}
                   onCancel={() => setFields(null)}>
                {fieldsError ? (
                    <Alert type="error" showIcon message={`读取失败：${graphErrorText(fieldsError)}`}/>
                ) : (
                    <Table size="small" rowKey="Field" loading={fieldsLoading}
                           dataSource={fields?.rows ?? []} columns={FIELD_COLUMNS} pagination={false}
                           locale={{emptyText: <Empty description="该类型没有字段（schema 里有名字但属性列表为空）"/>}}/>
                )}
            </Modal>
        </div>
    )
}

/**
 * 「空」有两种：接口通了确实没有，和接口根本没起。渲染上必须分开，
 * 否则一次 bind 失败会伪装成一个正常的空图（本卡验收第 2 条针对的就是这个）。
 */
function schemaEmptyText(schemaLoaded, label) {
    return schemaLoaded
        ? <EmptyState title={`${label}`}
                      description="GET /api/graph/meta/schema 是通的、这一类确实是空数组。z-graph 的内存图刚启动时只有 Initial graph 一个提交，schema 需要显式建。"/>
        : <EmptyState title="没有拿到 schema 数据"
                      description="schema 请求本身失败了，见页面顶部的错误条；这里不是「空」，是「没读通」。"/>
}
