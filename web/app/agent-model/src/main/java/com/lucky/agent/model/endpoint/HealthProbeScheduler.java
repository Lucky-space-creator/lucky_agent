package com.lucky.agent.model.endpoint;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

/**
 * 端点健康探测定时任务（周期探活，主端点失效自动切备用）�? */

@Slf4j
@Component
public class HealthProbeScheduler {

    
    private final EndpointAccessCenter accessCenter;

    public HealthProbeScheduler(EndpointAccessCenter accessCenter) {
        this.accessCenter = accessCenter;
    }

    /** 周期探活全部启用端点（默�?60s）�?*/
//    @Scheduled(fixedRateString = "${model.health-probe-sec:6000000}")
//    public void probeAll() {
//        try {
//            accessCenter.probeAll();
//        } catch (Exception e) {
//            log.warn("周期探活失败", e);
//        }
//    }
}
