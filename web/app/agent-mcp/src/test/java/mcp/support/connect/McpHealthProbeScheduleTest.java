package mcp.support.connect;

import com.lucky.agent.mcp.support.connect.McpHealthProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 健康探活的调度契约（台账 #40，与 #21 的 {@code model.health-probe-sec} 同一坑）：
 * 配置项语义是「秒」，而 {@code fixedDelayString} 的裸数字按<b>毫秒</b>解析 ——
 * 用户配 {@code 60} 想表达 1 分钟，实际是 60ms 的探活风暴，必须带 {@code s} 后缀换算。
 */
class McpHealthProbeScheduleTest {

    @Test
    @DisplayName("#40 探活周期必须是「秒语义 + s 后缀」")
    void probeScheduleUsesSecondSuffix() throws Exception {
        Method probe = McpHealthProbe.class.getMethod("probe");
        Scheduled scheduled = probe.getAnnotation(Scheduled.class);

        assertThat(scheduled)
                .as("没有 @Scheduled 就没有探活")
                .isNotNull();
        assertThat(scheduled.fixedDelayString())
                .as("裸数字按毫秒解析：占位符必须带 s 后缀（${mcp.health-probe-sec:60}s）")
                .isEqualTo("${mcp.health-probe-sec:60}s");
        assertThat(scheduled.fixedRateString())
                .as("必须用 fixedDelay：fixedRate 会在单轮慢时把探活堆成并发")
                .isEmpty();
    }
}
