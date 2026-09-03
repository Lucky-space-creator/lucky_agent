package com.lucky.agent.common.dto;

import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.constant.Phase;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.HashMap;
import java.util.Map;

/**
 * 会话执行上下文，在工具调用 / 引擎运行间传递。
 *
 * <p>携带会话定位、当前阶段、工作区权限级别（带入上下文作为体验层引导）与附加信息；
 * 精确权限裁决由规则链 + 执行臂硬边界完成，本对象不承载裁决逻辑。</p>
 */
@Data
@Builder
@Accessors(chain = true, fluent = true)
public class ConversationCtx {

    private final SessionRef sessionRef;
    private final Phase phase;
    private final PermissionLevel permissionLevel;
    private final String goal;
    @Builder.Default
    private Map<String, Object> extra = new HashMap<>();

    public String sessionId() {
        return sessionRef == null ? null : sessionRef.sessionId();
    }

    public String userId() {
        return sessionRef == null ? null : sessionRef.userId();
    }

    public String workspaceId() {
        return sessionRef == null ? null : sessionRef.workspaceId();
    }

    public ConversationCtx withPhase(Phase newPhase) {
        return builder().sessionRef(sessionRef).phase(newPhase).permissionLevel(permissionLevel)
                .goal(goal).extra(extra).build();
    }
}
