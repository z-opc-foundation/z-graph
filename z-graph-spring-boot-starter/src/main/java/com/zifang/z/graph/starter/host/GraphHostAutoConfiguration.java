package com.zifang.z.graph.starter.host;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 宿主侧胶水装配 (2026-10-03 自 z-opc main-starter 平移):
 * GraphProxyController (/api/graph/** 控制面代理) + GraphEmbeddedServerConfig (内嵌 GraphControlServer).
 *
 * <p>跟随 z.graph.host.enabled 开关 (默认关): 寄生 all-in-one 模式由宿主打开,
 * standalone 分布式模式 (z-graph 独立容器) 不开.
 * ZGraphAutoConfiguration 不受此开关影响, 始终可用.
 */
@Configuration
@ConditionalOnProperty(prefix = "z.graph.host", name = "enabled", havingValue = "true", matchIfMissing = false)
@ComponentScan(basePackages = "com.zifang.z.graph.starter.host")
public class GraphHostAutoConfiguration {
}
