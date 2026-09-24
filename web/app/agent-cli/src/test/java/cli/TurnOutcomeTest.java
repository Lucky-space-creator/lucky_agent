package cli;

import com.lucky.agent.cli.TurnOutcome;
import com.lucky.agent.cli.CliExitCode;
import com.lucky.agent.cli.channel.TurnCapture;
import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.RunResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退出码判定矩阵。
 *
 * <p>退出码是脚本与 CI 唯一的沟通方式，故对每一档都单独设断言：判定优先级一旦被改动，
 * 这些用例会立刻失败并指出是哪一档被破坏。</p>
 */
class TurnOutcomeTest {

    private static final String SESSION = "s-1";

    private static TurnOutcome of(RunResult result, TurnCapture capture, Throwable failure,
                                  boolean timedOut) {
        return new TurnOutcome(result, capture, failure, timedOut, 12L);
    }

    @Test
    @DisplayName("正常成功 → 0")
    void success() {
        TurnOutcome o = of(RunResult.of(SESSION).status(RunResult.RunStatus.SUCCESS).summary("ok"),
                new TurnCapture(), null, false);
        assertEquals(CliExitCode.SUCCESS, o.exitCode(false));
        assertFalse(o.failed());
    }

    @Test
    @DisplayName("用户拒绝授权 → 3（优先级高于其它一切判定）")
    void permissionDeniedWins() {
        TurnOutcome o = of(RunResult.of(SESSION).status(RunResult.RunStatus.SUCCESS).summary("ok"),
                new TurnCapture(), null, false);
        assertEquals(CliExitCode.PERMISSION_DENIED, o.exitCode(true));
    }

    @Test
    @DisplayName("超时 → 1（不得误判为成功，否则脚本会拿不到结果却以为成功）")
    void timeout() {
        TurnOutcome o = of(null, new TurnCapture(), null, true);
        assertEquals(CliExitCode.FAILURE, o.exitCode(false));
    }

    @Test
    @DisplayName("内核报错 → 1")
    void kernelError() {
        TurnOutcome o = of(RunResult.of(SESSION).status(RunResult.RunStatus.ERROR).error("模型不可用"),
                new TurnCapture(), null, false);
        assertEquals(CliExitCode.FAILURE, o.exitCode(false));
        assertTrue(o.failed());
        assertEquals("模型不可用", o.errorText());
    }

    @Test
    @DisplayName("提交链路异常 → 1")
    void throwable() {
        TurnOutcome o = of(RunResult.of(SESSION).status(RunResult.RunStatus.SUCCESS),
                new TurnCapture(), new IllegalStateException("连接被重置"), false);
        assertEquals(CliExitCode.FAILURE, o.exitCode(false));
        assertEquals("连接被重置", o.errorText());
    }

    @Test
    @DisplayName("stop.reason=max_turns → 4（预算耗尽，与普通失败区分开）")
    void budgetFromStopReason() {
        TurnCapture c = new TurnCapture();
        c.add(AgentEvent.stop(SESSION, "max_turns", "达到回合上限"));
        TurnOutcome o = of(RunResult.of(SESSION).status(RunResult.RunStatus.SUCCESS), c, null, false);
        assertEquals(CliExitCode.BUDGET_EXHAUSTED, o.exitCode(false));
    }

    @Test
    @DisplayName("RunStatus.MAX_TURNS → 4")
    void budgetFromStatus() {
        TurnOutcome o = of(RunResult.of(SESSION).status(RunResult.RunStatus.MAX_TURNS),
                new TurnCapture(), null, false);
        assertEquals(CliExitCode.BUDGET_EXHAUSTED, o.exitCode(false));
    }

    @Test
    @DisplayName("无运行结果（异常路径） → 5 挂起未决")
    void pendingUnresolved() {
        TurnOutcome o = of(null, new TurnCapture(), null, false);
        assertEquals(CliExitCode.PENDING_UNRESOLVED, o.exitCode(false));
    }

    @Test
    @DisplayName("异常对象的 message 为空时用类名兜底（避免报错行只有一个冒号）")
    void throwableWithoutMessage() {
        TurnOutcome o = of(null, new TurnCapture(), new RuntimeException(), false);
        assertEquals("RuntimeException", o.errorText());
    }
}
