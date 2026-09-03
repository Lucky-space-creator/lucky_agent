package com.lucky.agent.model.api.dto;

/**
 * 模型路由配置快照（供状态面板查询）。
 *
 * @param primaryId     主端点 id（未配置为 null）
 * @param primaryName   主端点名称
 * @param primaryModel  主端点模型名
 * @param fallbackId    备用端点 id（无备用为 null）
 * @param fallbackName  备用端点名称
 * @param primaryHealthy 主端点探活是否健康
 * @param contextWindow 主端点上下文窗口大小
 */
public record ModelRouterStatus(String primaryId, String primaryName, String primaryModel,
                                String fallbackId, String fallbackName,
                                boolean primaryHealthy, int contextWindow) {
}
