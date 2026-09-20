package com.lucky.agent.workflow.engine.trigger;

import com.lucky.agent.workflow.domain.WorkflowDef;
import com.lucky.agent.workflow.domain.VariableScope;
import com.lucky.agent.workflow.domain.enums.RunMode;
import com.lucky.agent.workflow.engine.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 工作流调度器：把工作流定义绑定到匹配的 {@link Trigger}，实现「定义即触发」。
 * <p>负责自动触发（周期）的注册/注销；手动触发由 API 直接调用引擎。</p>
 */
public class WorkflowScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkflowScheduler.class);

    private final WorkflowEngine engine;
    private final List<Trigger> triggers;

    public WorkflowScheduler(WorkflowEngine engine, List<Trigger> triggers) {
        this.engine = engine;
        this.triggers = triggers == null ? List.of() : List.copyOf(triggers);
    }

    /** 若定义带自动触发器，则注册调度。 */
    public void register(WorkflowDef definition) {
        if (definition.trigger() == null || !definition.trigger().enabled()
                || !definition.isAutoTrigger()) {
            return;
        }
        for (Trigger trigger : triggers) {
            if (trigger.supports(definition.trigger().type())) {
                trigger.schedule(definition, () -> engine.run(definition, new VariableScope(), RunMode.ASYNC));
                log.info("工作流已注册自动触发: {} ({})", definition.id(), trigger.name());
                return;
            }
        }
        log.warn("未找到匹配的触发器实现: workflow={} type={}", definition.id(), definition.trigger().type());
    }

    public void unregister(WorkflowDef definition) {
        for (Trigger trigger : triggers) {
            trigger.cancel(definition.id());
        }
    }

    @Override
    public void close() {
        for (Trigger trigger : triggers) {
            trigger.shutdown();
        }
    }
}
