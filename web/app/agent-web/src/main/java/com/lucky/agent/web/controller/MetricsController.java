package com.lucky.agent.web.controller;

import com.lucky.agent.core.util.metrics.MetricsCollector;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 透明面板指标端点（P3 验收点：前端透明面板数据准确）。
 *
 * <p>前端在会话运行期轮询本端点，读取当前会话的 token 用量、模型调用次数、
 * 缓存命中率、各工具触发次数、Skill/MCP 调用次数与子代理进度。数据由
 * {@link MetricsCollector} 从事件流实时聚合，无需扩展契约事件类型。</p>
 */
@RestController
@RequestMapping("/api/chat")
public class MetricsController {

    private final MetricsCollector metricsCollector;

    public MetricsController(MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    /** 读取会话实时指标快照。 */
    @GetMapping("/{sessionId}/metrics")
    public Map<String, Object> metrics(@PathVariable String sessionId) {
        return metricsCollector.snapshot(sessionId).snapshot();
    }
}
