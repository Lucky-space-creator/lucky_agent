package com.lucky.agent.workflow.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 节点在画布上的坐标（可视化元数据，不参与执行语义）。
 *
 * <p>放在定义里而非前端本地缓存，是为了让「布局」随定义一起持久化/分享：
 * 换机器或重新导入后画布仍保持原样。引擎侧完全忽略该字段。</p>
 *
 * @param x 画布坐标系 X（像素）
 * @param y 画布坐标系 Y（像素）
 */
public record NodePosition(
        @JsonProperty("x") double x,
        @JsonProperty("y") double y) {
}
