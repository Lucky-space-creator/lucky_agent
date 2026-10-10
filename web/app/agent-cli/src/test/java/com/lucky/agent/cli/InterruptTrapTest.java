package com.lucky.agent.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sun.misc.Signal;
import sun.misc.SignalHandler;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * SIGINT 接管的回归守卫（缺陷 D17）。
 *
 * <p><b>为什么值得单测</b>：这条修复此前只有「真机按 Ctrl+C」一次验证。而 Ctrl+C 是用户
 * 唯一的紧急出口，一旦接管逻辑写错（例如装上了但没恢复、或首次就吃掉信号却不取消），
 * 后果是「进程无响应」或「进程照旧被杀」，两者都很难在真机上被稳定复现。这里用
 * {@link Signal#raise} 在进程内合成 SIGINT，把「接管生效」与「关闭后还原」钉死。</p>
 *
 * <p><b>安全约束</b>：本测试<b>绝不</b>验证「连按两次即強制退出」那条路径 —— 它会调用
 * {@code Runtime.halt(130)} 直接终止 JVM，在测试进程里触发就等于杀掉 surefire 本身。
 * 该路径只能由真机人工验证（见缺陷报告附录 E 的复测证据）。</p>
 */
class InterruptTrapTest {

    private static final Signal INT = new Signal("INT");

    /** 等待条件成立，避免依赖处理器在哪个线程上执行。 */
    private static void awaitUntil(BooleanSupplier cond) {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    @DisplayName("安装后收到 SIGINT：回调被调用，且进程不退出")
    void installTranslatesSigintIntoCallback() {
        AtomicInteger calls = new AtomicInteger();
        InterruptTrap trap = InterruptTrap.install(calls::incrementAndGet);
        try {
            // 某些 JVM（-Xrs / 受限环境）不允许安装，此时 install 会退回 disabled —— 跳过而非失败。
            // 跳过本身就是断言的一部分：接管失败必须「静默退回」而不是抛异常。
            assumeTrue(trap.installed(), "本 JVM 不允许安装 SIGINT 处理器，跳过");
            assertTrue(trap.installed());
            Signal.raise(INT);
            awaitUntil(() -> calls.get() > 0);
            assertEquals(1, calls.get(), "首次 SIGINT 应恰好触发一次优雅取消回调");
            // 能走到这里就说明进程没被默认处理器杀掉
            assertTrue(true);
        } finally {
            trap.close();
        }
    }

    @Test
    @DisplayName("关闭后还原先前处理器（否则会吞掉用户后续的 Ctrl+C）")
    void closeRestoresPreviousHandler() {
        // 先建一个已知状态：默认处理器
        SignalHandler previous = Signal.handle(INT, SignalHandler.SIG_DFL);
        try {
            InterruptTrap trap = InterruptTrap.install(() -> { });
            assumeTrue(trap.installed(), "本 JVM 不允许安装 SIGINT 处理器，跳过");
            trap.close();
            // handle 会返回「被替换掉的那个」= 关闭时应已还原为 SIG_DFL
            SignalHandler afterClose = Signal.handle(INT, SignalHandler.SIG_DFL);
            assertEquals(SignalHandler.SIG_DFL, afterClose,
                    "关闭后应还原为安装前的处理器，实际为 " + afterClose);
        } finally {
            Signal.handle(INT, previous);
        }
    }

    @Test
    @DisplayName("disabled() 不接管且 close 无副作用")
    void disabledIsNoop() {
        InterruptTrap trap = InterruptTrap.disabled();
        assertFalse(trap.installed());
        trap.close();
        assertFalse(trap.installed());
    }
}