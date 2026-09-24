package com.lucky.agent.cli.channel;

import com.lucky.agent.common.dto.AgentEvent;

/**
 * 事件渲染出口。
 *
 * <p>通道只依赖这个接口，不依赖具体渲染器，使得同一套「订阅 → 采集 → 收尾」的时序代码
 * 能同时服务三种终端形态：交互式 REPL（{@link EventRenderer}）、headless 文本流、
 * headless NDJSON 流（{@code --output-format stream-json}）。</p>
 *
 * <p>把「渲染」抽成接口而不是在渲染器里加一堆 if，是为了避免那条最容易出的错：
 * 交互式渲染器与机器可读输出共用一个实现，某天给交互式加了行内光标控制，
 * 就顺手污染了 JSON 输出。</p>
 */
public interface EventSink {

    /** 渲染一条事件。 */
    void render(AgentEvent event);

    /** 本轮收尾（补换行 / 刷新缓冲）。 */
    default void endOfTurn() {
    }
}
