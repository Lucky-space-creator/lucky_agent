package com.lucky.agent.model.support.endpoint;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 端点健康探测定时任务：周期探活全部启用端点，主端点失效自动切备用、恢复后自动回切。
 *
 * <p><b>为什么这个类此前等于不存在</b>（台账 #21）：整段 {@code @Scheduled} 被注释掉，
 * 而 {@link HealthProbe} 的健康缓存只在「保存配置」与「手动测试」两条路径写入。
 * 于是用户保存配置后再让端点宕机 → 健康态永远停在 saved 时的 true，主备切换永不触发；
 * 反之保存时探测失败、之后端点恢复 → 也永远不会回切。配置项 {@code model.health-probe-sec}
 * 虽有默认值却无人读取，属于「看起来有、实际没有」的能力。</p>
 *
 * <p><b>为什么用 {@code fixedDelay} 而不是 {@code fixedRate}：</b>探活是同步 HTTP 请求，
 * 端点慢时单轮可能超过周期。{@code fixedRate} 会让下一轮在上一轮还没结束时排进来，
 * 堆积成并发探测；{@code fixedDelay} 保证「上一轮收尾后再等 N 秒」，代价只是探活周期被拉长，
 * 对「切换备用」这个用途完全可接受。</p>
 *
 * <p><b>时间单位：</b>配置项语义是<b>秒</b>（{@code lucky-defaults.yml} 写的是 {@code 60}），
 * 而 {@code fixedDelayString} 的裸数字按<b>毫秒</b>解析。因此这里显式加 {@code s} 后缀
 * —— 原实现写 {@code :6000000} 是「100 分钟」，本身就等于不探测。</p>
 */
@Slf4j
@Component
public class HealthProbeScheduler {

    private final EndpointAccessCenter accessCenter;

    public HealthProbeScheduler(EndpointAccessCenter accessCenter) {
        this.accessCenter = accessCenter;
    }

    /** 周期探活全部启用端点（默认 60 秒，可用 {@code model.health-probe-sec} 调整，单位秒）。 */
    @Scheduled(fixedDelayString = "${model.health-probe-sec:60}s")
    public void probeAll() {
        try {
            accessCenter.probeAll();
        } catch (Exception e) {
            // 定时任务里抛出会打断后续调度（Spring 只记日志、且不会重排本次），
            // 故这里必须兜住：单轮失败不能让探活机制整体失效。
            log.warn("周期探活失败，本轮跳过（下一轮继续）：{}", e.toString());
        }
    }
}
