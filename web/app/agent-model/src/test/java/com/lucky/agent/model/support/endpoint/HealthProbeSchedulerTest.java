package com.lucky.agent.model.support.endpoint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 端点健康态必须能自动刷新（台账 #21）。
 *
 * <p>修复前 {@code HealthProbeScheduler} 的 {@code @Scheduled} 整段被注释掉：类还在、
 * Bean 还在、配置项 {@code model.health-probe-sec} 也有默认值，但没有任何东西驱动它。
 * 结果是健康缓存只在「保存配置」和「手动测试」两条路径写入 —— 端点保存后宕机永不切备用，
 * 保存时不可达也永不回切。下面两个用例分别守住「真的被调度」与「单轮失败不毁掉调度」。</p>
 */
class HealthProbeSchedulerTest {

    @Test
    @DisplayName("#21 probeAll 必须真的带 @Scheduled，且周期是秒级")
    void probeAllIsActuallyScheduled() throws Exception {
        Method probe = HealthProbeScheduler.class.getMethod("probeAll");
        Scheduled scheduled = probe.getAnnotation(Scheduled.class);

        assertThat(scheduled)
                .as("没有 @Scheduled 就等于死代码：健康态永不刷新")
                .isNotNull();
        assertThat(scheduled.fixedDelayString())
                .as("裸数字会被解析成毫秒：原写法 :6000000 等于 100 分钟，等于不探测")
                .isEqualTo("${model.health-probe-sec:60}s");
        assertThat(scheduled.fixedRateString())
                .as("必须用 fixedDelay：fixedRate 会在端点慢时把探测堆成并发")
                .isEmpty();
    }

    @Test
    @DisplayName("#21 单轮探活抛异常不得打断后续调度")
    void probeFailureDoesNotBreakScheduling() {
        EndpointAccessCenter accessCenter = mock(EndpointAccessCenter.class);
        doThrow(new IllegalStateException("端点探活炸了")).when(accessCenter).probeAll();

        assertThatCode(() -> new HealthProbeScheduler(accessCenter).probeAll())
                .as("定时任务里抛出会让 Spring 记一条日志并结束本轮，用户再也等不到下一次刷新")
                .doesNotThrowAnyException();
        verify(accessCenter).probeAll();
    }
}
