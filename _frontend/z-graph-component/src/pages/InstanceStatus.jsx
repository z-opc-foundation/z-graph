import {useCallback, useEffect, useState} from 'react'
import {Alert, Button, Card, Descriptions, Space, Table, Tag} from 'antd'
import {ReloadOutlined} from '@ant-design/icons'
import {graphApi, graphErrorText} from '../services/api'
import {PageHeader} from '@/common/components/ui'

const ENDPOINT_COLUMNS = [
    {title: '方法', dataIndex: 'method', key: 'method', width: 90, render: (v) => <Tag>{v}</Tag>},
    {title: '路径', dataIndex: 'path', key: 'path', ellipsis: true},
    {title: '来源 IP', dataIndex: 'clientIp', key: 'clientIp', width: 150},
    {
        title: '状态', dataIndex: 'status', key: 'status', width: 90,
        render: (v) => <Tag color={v >= 500 ? 'error' : v >= 400 ? 'warning' : 'success'}>{v}</Tag>
    },
    {title: '耗时', dataIndex: 'elapsedMs', key: 'elapsedMs', width: 90, render: (v) => `${v} ms`},
    {title: '请求 ID', dataIndex: 'requestId', key: 'requestId', width: 170, render: (v) => <code>{v}</code>},
    {title: '线程', dataIndex: 'thread', key: 'thread', width: 160, render: (v) => <code>{v}</code>},
]

/** 上游自报的 uptime 是"这个 HTTP 控制面活了多久"，用它就能一眼看穿应答者是不是刚构建出来的 JVM */
function fmtUptime(sec) {
    if (sec == null) return '-'
    const d = Math.floor(sec / 86400), h = Math.floor((sec % 86400) / 3600), m = Math.floor((sec % 3600) / 60)
    if (d > 0) return `${d} 天 ${h} 时 ${m} 分`
    if (h > 0) return `${h} 时 ${m} 分 ${sec % 60} 秒`
    return `${m} 分 ${sec % 60} 秒`
}

function fmtBytes(b) {
    if (b == null) return '-'
    const mb = b / 1024 / 1024
    return `${mb.toFixed(1)} MiB`
}

function yesNo(v, okText = '是', badText = '否', badColor = 'error') {
    if (v == null) return '-'
    return v ? <Tag color="success">{okText}</Tag> : <Tag color={badColor}>{badText}</Tag>
}

/**
 * 实例与端口 — GET /api/graph/__instance 的可视化，外加控制面自己的 /meta/metrics 与 /meta/logs。
 *
 * 这页存在的理由是本卡验收第 1、2 条要证明的那件事：**"页面有数据"和"数据来自本次构建的 JVM"
 * 是两个独立命题**。z-graph 的 HTTP 面既不在 Spring 里（查不到 /actuator/mappings），
 * 又能被一个几天前手搓起来的独立进程顶着同一个端口 —— 实测 `lsof -nP -iTCP:8090 -sTCP:LISTEN`
 * 的属主是 PID 1794（2026-09-19 17:50 起的 `java -cp .../z-graph-bolt-server-1.0.1.jar
 * com.zifang.z.graph.bolt.GraphControlServerMain 8090`），而当时 z-opc 是 PID 48695。
 * 于是这里把三条独立证据摊在同一屏：
 *   1) jvm = `<pid>@<host>` —— 和 lsof 的 PID 对得上才算孵化成功；
 *   2) boundPort / embeddedRunning —— 本 JVM 到底 bind 上没有；
 *   3) upstreamMetrics.uptimeSeconds —— 应答者自报的存活时长；比本进程的 lifetime 长就是别人。
 * `foreignListenerSuspected` 是这三者合成的判定式：boundPort==0 且配置端口 TCP 可连
 * ⇒ 应答者必然不是本 JVM（本 JVM 没 bind），此时**任何**表格数据都不属于本次孵化。
 */
export default function InstanceStatus() {
    const [data, setData] = useState(null)
    const [metrics, setMetrics] = useState(null)
    const [metricsError, setMetricsError] = useState(null)
    const [logs, setLogs] = useState(null)
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)

    const fetch = useCallback(async () => {
        setLoading(true)
        try {
            const inst = await graphApi.instance()
            setData(inst)
            setError(null)
            // metrics / logs 走上游转发：内嵌没起时它们必然 503，这本身就是要显示的信息
            try {
                const [m, l] = await Promise.all([graphApi.metrics(), graphApi.logs({limit: 20})])
                setMetrics(m)
                setMetricsError(null)
                setLogs(l)
            } catch (e) {
                setMetrics(null)
                setLogs(null)
                setMetricsError(e)
            }
        } catch (e) {
            setData(null)
            setError(e)
        } finally {
            setLoading(false)
        }
    }, [])

    useEffect(() => {
        fetch()
    }, [fetch])

    const dead = !error && data && !data.embeddedRunning
    const foreign = !error && data && data.foreignListenerSuspected

    return (
        <div>
            <PageHeader title="实例与端口"
                        subtitle="GraphProxyController 自省接口 GET /api/graph/__instance + 控制面自报的 /meta/metrics"/>
            <Card extra={<Space>
                <Button icon={<ReloadOutlined/>} loading={loading} onClick={fetch}>刷新</Button>
            </Space>}>
                {error ? (
                    <Alert type="error" showIcon message={`自省接口调用失败：${graphErrorText(error)}`}
                           description="/api/graph/__instance 由 z-opc 自己的 controller 直接应答，不经过内嵌服务；它都不通说明是 z-opc 侧的路由/认证问题（302 到登录页 = 没带会话 Cookie）。"/>
                ) : (
                    <>
                        {foreign && (
                            <Alert type="error" showIcon style={{marginBottom: 16}}
                                   message="配置端口上有监听者，但它不是本 JVM —— 这一路连的是外部进程，页面数据不能算孵化结果"
                                   description={
                                       `boundPort=0（本 JVM 的 GraphControlServer 没 bind 上）而 ${data.configuredPort} 端口 TCP 可连。` +
                                       `用 lsof -nP -iTCP:${data.configuredPort} -sTCP:LISTEN 取属主 PID，与本页 jvm 字段（${data.jvm}）比对；` +
                                       `对不上就是假孵化。处理方式只有两种：让出该端口，或给 zgraph.server.port 换一个端口——两者都需要定夺，代理层不会替你去占端口。`
                                   }/>
                        )}
                        {dead && !foreign && (
                            <Alert type="warning" showIcon style={{marginBottom: 16}}
                                   message="内嵌 GraphControlServer 没有 bind 成功，其余三页的空数组是「没起」而不是「图里没数据」"
                                   description={
                                       `bindError = ${data.bindError ?? 'null'}。` +
                                       'proxy 只在 boundPort>0 时才转发，所以现在所有 /api/graph/meta* 与 /api/graph/query 都会拿到 503。'
                                   }/>
                        )}
                        <Descriptions bordered size="small" column={2}
                                      items={[
                                          {
                                              key: 'jvm', label: '应答的 JVM (pid@host)',
                                              children: <code>{data?.jvm ?? '-'}</code>
                                          },
                                          {
                                              key: 'lifecycleBean', label: '内嵌配置已装载',
                                              children: data ? (data.lifecycleBean
                                                  ? <Tag color="success">是</Tag>
                                                  : <Tag color="error">否（zgraph.enabled≠true）</Tag>) : '-'
                                          },
                                          {
                                              key: 'configuredPort', label: '配置端口',
                                              children: data?.configuredPort ?? '-'
                                          },
                                          {
                                              key: 'boundPort', label: '内嵌实例实际 bind 端口',
                                              children: data ? (data.boundPort > 0
                                                  ? <Tag color="success">{data.boundPort}</Tag>
                                                  : <Tag color="error">0（没 bind 上）</Tag>) : '-'
                                          },
                                          {
                                              key: 'embeddedRunning', label: '内嵌控制面在跑',
                                              children: yesNo(data?.embeddedRunning)
                                          },
                                          {
                                              key: 'acceptingNow', label: '本实例端口此刻可连',
                                              children: yesNo(data?.acceptingNow, '是', '否', 'warning')
                                          },
                                          {
                                              key: 'foreign', label: '外来监听者嫌疑',
                                              children: data ? (data.foreignListenerSuspected
                                                  ? <Tag color="error">有（应答者不是本 JVM）</Tag>
                                                  : <Tag color="success">无</Tag>) : '-'
                                          },
                                          {
                                              key: 'token', label: '上游 API Token (环境变量)',
                                              children: data ? (data.apiTokenConfigured
                                                  ? <Tag color="success">已设置</Tag>
                                                  : <Tag color="warning">未设置 = 通配端口不鉴权</Tag>) : '-'
                                          },
                                          {
                                              key: 'bindError', label: 'bind 失败原因',
                                              children: data?.bindError ? <code>{data.bindError}</code> : '-'
                                          },
                                          {
                                              key: 'path', label: '转发前缀',
                                              children: <code>/api/graph/** → 127.0.0.1:&lt;boundPort&gt;</code>
                                          },
                                      ]}/>
                        <div style={{marginTop: 12, color: '#64748b', fontSize: 12}}>
                            代理是逐字节透传，不在 z-opc 侧重拼 JSON。上游注册的端点：
                            {(data?.upstreamEndpoints ?? []).map((p) => <code key={p}
                                                                              style={{marginRight: 6}}>{p}</code>)}
                        </div>
                    </>
                )}
            </Card>

            <Card title="控制面自报指标 (GET /api/graph/meta/metrics)" style={{marginTop: 16}}
                  extra={<Tag color="blue">走上游转发，内嵌没起时这里必然报错</Tag>}>
                {metricsError ? (
                    <Alert type="error" showIcon
                           message={`读不到 /meta/metrics：${graphErrorText(metricsError)}`}/>
                ) : (
                    <Descriptions bordered size="small" column={3}
                                  items={[
                                      {
                                          key: 'uptime', label: '控制面存活时长',
                                          children: `${fmtUptime(metrics?.uptimeSeconds)}（${metrics?.uptimeFormatted ?? '-'}）`
                                      },
                                      {key: 'totalRequests', label: '累计请求', children: metrics?.totalRequests ?? '-'},
                                      {key: 'errorResponses', label: '错误应答', children: metrics?.errorResponses ?? '-'},
                                      {key: 'errorRate', label: '错误率', children: metrics?.errorRate ?? '-'},
                                      {key: 'authFailures', label: '鉴权失败', children: metrics?.authFailures ?? '-'},
                                      {key: 'rateLimited', label: '被限流', children: metrics?.rateLimitedRequests ?? '-'},
                                      {
                                          key: 'heap', label: 'JVM 堆 (已用/已申请/上限)',
                                          children: metrics?.jvmMemory
                                              ? `${fmtBytes(metrics.jvmMemory.usedBytes)} / ${fmtBytes(metrics.jvmMemory.totalBytes)} / ${fmtBytes(metrics.jvmMemory.maxBytes)}`
                                              : '-'
                                      },
                                      {key: 'cpu', label: '可用核数', children: metrics?.availableProcessors ?? '-'},
                                      {
                                          key: 'rate', label: '限流阈值/活跃桶',
                                          children: `${metrics?.rateLimitPerMinute ?? '-'} / ${metrics?.activeRateBuckets ?? '-'}`
                                      },
                                  ]}/>
                )}
            </Card>

            <Card title="最近请求日志 (GET /api/graph/meta/logs?limit=20)" style={{marginTop: 16}}>
                {!logs ? (
                    <Alert type="info" showIcon
                           message={metricsError ? '读不到请求日志（同上）' : '日志缓冲为空 —— 控制面刚起或还没打过请求'}/>
                ) : (
                    <Table size="small" rowKey={(r) => `${r.timestamp}-${r.requestId}`} loading={loading}
                           dataSource={logs.entries ?? []} columns={ENDPOINT_COLUMNS}
                           pagination={{
                               size: 'small', total: logs.total ?? 0, pageSize: 20,
                               showTotal: (t) => `环形缓冲 ${t} 条 / 容量 ${logs.bufferSize ?? '-'}`
                           }}/>
                )}
            </Card>
        </div>
    )
}
