package com.lucky.agent.cli.term;

/**
 * 「读一行用户输入」的最小契约。
 *
 * <p>刻意只暴露这一个方法，而不是让调用方依赖 {@link LineEditor}：授权裁决、{@code !} 直执行
 * 这些流程只需要「问一句、拿一个回答」，不需要行编辑、历史与补全。依赖收窄的直接收益是
 * <b>可测</b> —— 它们能用一个返回脚本化答案的实现来单测，而不必真造一个终端。</p>
 *
 * <p>返回 {@code null} 表示输入结束（EOF / 管道关闭），调用方应按「放弃」处理。</p>
 */
@FunctionalInterface
public interface InputReader {

    String readLine(String prompt);
}
