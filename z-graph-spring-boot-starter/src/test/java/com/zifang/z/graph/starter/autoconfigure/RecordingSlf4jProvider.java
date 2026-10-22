package com.zifang.z.graph.starter.autoconfigure;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;
import org.slf4j.helpers.BasicMDCAdapter;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 测试用的 SLF4J 记录型 provider（**只装在 test scope**）。
 *
 * <p><b>为什么需要它</b>：本仓 starter 模块的测试 classpath 上**没有任何日志后端**
 * —— 地板 z-boot-dependencies 把 {@code spring-boot-starter-logging} 的传递依赖全排除了
 * （logback / log4j-to-slf4j 都不在），本仓又按「库件不替应用写日志栈」的纪律删掉了
 * {@code slf4j-simple}。⇒ {@code log.warn(...)} 在测试里**落不到任何输出**，
 * 「启动日志里有没有 WARN」这条判据就没法用日志文本断言。</p>
 *
 * <p><b>怎么做</b>：SLF4J 2.x 通过 {@link java.util.ServiceLoader} 找
 * {@link SLF4JServiceProvider}，所以在 {@code src/test/resources/META-INF/services/}
 * 下登记本类即可接管本模块测试进程的日志；本 provider 把 WARN 级别的消息收进
 * {@link #WARNINGS}，让测试能断言「有没有 WARN」与「WARN 文案里有没有配置指引」。</p>
 *
 * <p><b>为什么不是降级方案</b>：断言落在「日志框架真收到了这条 WARN」上，
 * 比断言纯函数的返回值更接近真实启动路径（决策函数仍被单独断言，见
 * {@code ZGraphDataDirWarnTest}）。</p>
 */
public class RecordingSlf4jProvider implements SLF4JServiceProvider {

    /** 本测试进程里收到的全部 WARN 消息（按时间顺序）。 */
    public static final List<String> WARNINGS = Collections.synchronizedList(new ArrayList<String>());

    /** 清空记录（每个用例开始前调，避免用例之间串味）。 */
    public static void reset() {
        WARNINGS.clear();
    }

    @Override
    public ILoggerFactory getLoggerFactory() {
        return new ILoggerFactory() {
            @Override
            public Logger getLogger(String name) {
                return new RecordingLogger(name);
            }
        };
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return new BasicMarkerFactory();
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return new BasicMDCAdapter();
    }

    @Override
    public String getRequestedApiVersion() {
        return "2.0.99";
    }

    @Override
    public void initialize() {
        // 无需初始化
    }

    /** 只把 WARN 收进记录表（其余级别丢弃 —— 本测试只关心 WARN）。 */
    private static final class RecordingLogger extends AbstractLogger {

        RecordingLogger(String name) {
            this.name = name;
        }

        @Override
        protected String getFullyQualifiedCallerName() {
            return RecordingLogger.class.getName();
        }

        @Override
        protected void handleNormalizedLoggingCall(Level level, Marker marker, String messagePattern,
                                                   Object[] arguments, Throwable throwable) {
            if (level == Level.WARN) {
                WARNINGS.add(MessageFormatter.arrayFormat(messagePattern, arguments).getMessage());
            }
        }

        @Override
        public boolean isTraceEnabled() {
            return false;
        }

        @Override
        public boolean isDebugEnabled() {
            return false;
        }

        @Override
        public boolean isInfoEnabled() {
            return false;
        }

        @Override
        public boolean isWarnEnabled() {
            return true;
        }

        @Override
        public boolean isErrorEnabled() {
            return true;
        }

        // AbstractLogger 实现了带 Marker 的日志方法，但没实现带 Marker 的 isXxxEnabled —— 补齐。

        @Override
        public boolean isTraceEnabled(Marker marker) {
            return isTraceEnabled();
        }

        @Override
        public boolean isDebugEnabled(Marker marker) {
            return isDebugEnabled();
        }

        @Override
        public boolean isInfoEnabled(Marker marker) {
            return isInfoEnabled();
        }

        @Override
        public boolean isWarnEnabled(Marker marker) {
            return isWarnEnabled();
        }

        @Override
        public boolean isErrorEnabled(Marker marker) {
            return isErrorEnabled();
        }
    }
}
