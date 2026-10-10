package com.lucky.agent.cli;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 运行期的 SIGINT 接管（best-effort）—— 缺陷 D17 的修复点。
 *
 * <p><b>问题</b>：提示符处 JLine 处于 raw 模式，Ctrl+C 只是输入流里的一个字节，由
 * {@code LineEditor} 处理（清行 / 两次退出），那条路径是好的。但一轮执行期间主线程阻塞在
 * {@code submit} 上，{@code readLine} 已返回并<b>恢复了 cooked 模式</b>，此时 Ctrl+C 会被
 * 操作系统直接翻译成 SIGINT，JVM 默认处理器立刻终止进程 —— 用户敲一次就丢掉整轮上下文，
 * 与「运行中按 Ctrl+C 可优雅取消本轮」的承诺正好相反。</p>
 *
 * <p><b>做法</b>：本轮内临时接管 SIGINT，把它翻译成与 {@code CancelWatcher} 相同的「优雅取消」
 * （内核在循环边界检查取消标记后尽早退出）；本轮结束<b>立即恢复原处理器</b>，提示符处的既有
 * 语义不受影响。连续第二次 Ctrl+C 视为「用户要强制退出」，以 130（128+SIGINT，惯例码）
 * 立即终止，避免内核长时间不响应取消时用户被困住。</p>
 *
 * <p><b>为什么用 {@code sun.misc.Signal}</b>：它在 {@code jdk.unsupported} 模块中、由 JDK
 * 正式导出，是 Spring Boot / logback 等库处理 SIGINT 的标准做法。备选「整轮持有 raw 模式」
 * 需要改动终端生命周期，还要与 JLine 的输入流争抢同一个控制台输入缓冲区，风险更高。
 * 若本机 JVM 不允许安装（例如以 {@code -Xrs} 启动），{@link #install} 会静默退回未接管状态 ——
 * 最坏情况与修复前持平，不会更差。</p>
 *
 * <p><b>可测性</b>：本类是 package-private 顶层类而非嵌套类，就是为了让
 * {@code InterruptTrapTest} 能直接用 {@code Signal.raise} 验证「接管生效 / 关闭后还原」，
 * 而不必启动整个 REPL。</p>
 */
@Slf4j
final class InterruptTrap implements AutoCloseable {

    /** 用户请求中断的惯例退出码（128 + SIGINT）。 */
    private static final int EXIT_SIGINT = 130;

    private final sun.misc.Signal signal;
    private final sun.misc.SignalHandler previous;
    private final AtomicBoolean consumed;

    private InterruptTrap(sun.misc.Signal signal, sun.misc.SignalHandler previous,
                          AtomicBoolean consumed) {
        this.signal = signal;
        this.previous = previous;
        this.consumed = consumed;
    }

    /** 未接管（headless 等不需要监听取消的场景）。 */
    static InterruptTrap disabled() {
        return new InterruptTrap(null, null, null);
    }

    /**
     * 安装 SIGINT 处理器：首次触发调用 {@code onInterrupt}，再次触发强制退出。
     *
     * @return 已安装的实例；安装失败时返回 {@link #disabled()}（不抛异常，不改变既有行为）
     */
    static InterruptTrap install(Runnable onInterrupt) {
        try {
            sun.misc.Signal sig = new sun.misc.Signal("INT");
            // 首次取消、再次强退：用共享标记区分，避免把「连按两下」误判成两次取消
            AtomicBoolean consumed = new AtomicBoolean(false);
            sun.misc.SignalHandler prev = sun.misc.Signal.handle(sig, s -> {
                if (consumed.compareAndSet(false, true)) {
                    onInterrupt.run();
                } else {
                    Runtime.getRuntime().halt(EXIT_SIGINT);
                }
            });
            return new InterruptTrap(sig, prev, consumed);
        } catch (Throwable e) {
            // 含 IllegalArgumentException（未知信号名）与 UnsupportedOperationException（-Xrs 等）：
            // 接管失败不改变行为，静默退回 JVM 默认处理
            log.debug("SIGINT 接管失败，运行期 Ctrl+C 将退回默认行为：{}", e.toString());
            return disabled();
        }
    }

    /** 是否已真正接管（供测试与排障判断，安装失败时为 false）。 */
    boolean installed() {
        return signal != null;
    }

    @Override
    public void close() {
        if (signal == null || previous == null) {
            return;
        }
        try {
            sun.misc.Signal.handle(signal, previous);
        } catch (Throwable e) {
            log.debug("SIGINT 处理器恢复失败：{}", e.toString());
        }
    }
}