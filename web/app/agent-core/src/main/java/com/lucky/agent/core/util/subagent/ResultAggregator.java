package com.lucky.agent.core.util.subagent;

import com.lucky.agent.core.repository.SubAgentResult;

import java.util.List;

/**
 * 子代理结果聚合：汇总摘要或结构化结果回写主 Agent。
 */
public class ResultAggregator {

    /**
     * 聚合多个子代理结果。
     *
     * @param results 子代理结果列表
     * @return 聚合文本
     */
    public String aggregate(List<SubAgentResult> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (SubAgentResult result : results) {
            if (!result.success()) {
                continue;
            }
            sb.append("### 子代理 ").append(result.subAgentId()).append("\n")
                    .append(result.summary()).append("\n\n");
        }
        return sb.toString().trim();
    }
}
