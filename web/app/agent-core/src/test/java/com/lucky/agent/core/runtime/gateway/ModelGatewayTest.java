package com.lucky.agent.core.runtime.gateway;

import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.core.models.Engine;
import com.lucky.agent.core.models.dto.EngineRunResult;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型调用侧韧性测试：重试退避（{@link RetryModelGateway}）与三态熔断（{@link CircuitBreakerModelGateway}）。
 *
 * <p>这两者补上的是「模型 API 调用」这一最贵、最易失败外部依赖的缺口——既有的工具侧
 * {@code RetryMiddleware} 无法覆盖模型调用。</p>
 */
class ModelGatewayTest {

    private static final String SESSION = "s-gw";

    private ConversationCtx ctx() {
        return ConversationCtx.builder()
                .sessionRef(new SessionRef(SESSION, "u-1", "ws-1"))
                .phase(Phase.ACT)
                .goal("g")
                .extra(Map.of())
                .build();
    }

    /** 脚本化模型：按调用顺序返回预设结果，脚本用尽后重复最后一项。 */
    private Engine scripted(List<EngineRunResult> script, AtomicInteger calls) {
        return (c, phase, goal) -> Mono.just(script.get(Math.min(calls.getAndIncrement(), script.size() - 1)));
    }

    private EngineRunResult ok(long tokens) {
        return EngineRunResult.of(SESSION, Phase.ACT, "ok", tokens, "stub-model", "success");
    }

    /** 瞬时故障（可重试）：含 503 / timeout / connection 等特征词。 */
    private EngineRunResult transientFail(String message) {
        return EngineRunResult.error(SESSION, Phase.ACT, message);
    }

    // ==================== 重试网关 ====================

    @Test
    void retryGatewayShouldRetryTransientFailuresAndRecover() {
        AtomicInteger calls = new AtomicInteger();
        Engine engine = scripted(List.of(
                transientFail("503 Service Unavailable"),
                transientFail("connection reset by peer"),
                ok(42)), calls);

        RetryModelGateway gateway = new RetryModelGateway(new EngineModelGateway(engine), 3, 0L);
        EngineRunResult result = gateway.call(ctx(), Phase.ACT, "g");

        assertThat(result.error()).isNull();
        assertThat(result.finalText()).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3);          // 2 次瞬时故障 + 1 次成功
        assertThat(gateway.retryCount()).isEqualTo(2); // 重试次数可观测
    }

    @Test
    void retryGatewayShouldNotRetryDeterministicFailures() {
        AtomicInteger calls = new AtomicInteger();
        Engine engine = scripted(List.of(transientFail("401 Unauthorized: invalid api key")), calls);

        RetryModelGateway gateway = new RetryModelGateway(new EngineModelGateway(engine), 5, 0L);
        EngineRunResult result = gateway.call(ctx(), Phase.ACT, "g");

        assertThat(result.error()).isNotNull();
        assertThat(calls.get()).isEqualTo(1);         // 确定性失败绝不重试（否则只浪费配额）
        assertThat(gateway.retryCount()).isZero();
    }

    @Test
    void retryGatewayShouldNotRetryAskNorCancel() {
        AtomicInteger calls = new AtomicInteger();
        // 携带瞬时故障特征词，但状态是 ask —— 业务语义优先，不应被当成故障重试
        EngineRunResult asking = new EngineRunResult(
                SESSION, Phase.ACT, "需要你确认", 0L, null, "ask", "connection reset by peer");
        Engine engine = scripted(List.of(asking), calls);

        RetryModelGateway gateway = new RetryModelGateway(new EngineModelGateway(engine), 3, 0L);
        EngineRunResult result = gateway.call(ctx(), Phase.ACT, "g");

        assertThat(result.status()).isEqualTo("ask");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(gateway.retryCount()).isZero();
    }

    @Test
    void retryGatewayShouldGiveUpAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();
        Engine engine = scripted(List.of(transientFail("request timed out 超时")), calls);

        RetryModelGateway gateway = new RetryModelGateway(new EngineModelGateway(engine), 3, 0L);
        EngineRunResult result = gateway.call(ctx(), Phase.ACT, "g");

        assertThat(result.error()).isNotNull();
        assertThat(calls.get()).isEqualTo(3);
        assertThat(gateway.retryCount()).isEqualTo(2);
    }

    @Test
    void backoffShouldGrowExponentiallyAndStayBounded() {
        RetryModelGateway gateway = new RetryModelGateway((c, phase, goal) -> null, 10, 100L, 0.0);

        assertThat(gateway.backoffFor(1)).isEqualTo(100L);
        assertThat(gateway.backoffFor(2)).isEqualTo(200L);
        assertThat(gateway.backoffFor(3)).isEqualTo(400L);
        assertThat(gateway.backoffFor(4)).isEqualTo(800L);
        assertThat(gateway.backoffFor(9)).isEqualTo(800L); // 退避倍数上限 8×，不会无限增长阻塞编排
    }

    // ==================== 熔断网关 ====================

    @Test
    void circuitBreakerShouldOpenAfterThresholdAndShortCircuitDelegate() {
        AtomicInteger calls = new AtomicInteger();
        Engine engine = scripted(List.of(transientFail("503 Service Unavailable")), calls);

        // 重试次数设为 1，使「调用次数」直接等于熔断器观察到的失败次数
        RetryModelGateway inner = new RetryModelGateway(new EngineModelGateway(engine), 1, 0L);
        CircuitBreakerModelGateway breaker = new CircuitBreakerModelGateway(inner, 2, 60_000L);

        assertThat(breaker.call(ctx(), Phase.ACT, "g").error()).isNotNull(); // 失败 1
        assertThat(breaker.state()).isEqualTo(CircuitBreakerModelGateway.State.CLOSED);
        assertThat(breaker.call(ctx(), Phase.ACT, "g").error()).isNotNull(); // 失败 2 → 达阈值
        assertThat(breaker.state()).isEqualTo(CircuitBreakerModelGateway.State.OPEN);
        assertThat(calls.get()).isEqualTo(2);

        // OPEN 状态下应由熔断器直接短路：委托（含其内部重试）不再被调用
        EngineRunResult shortCircuited = breaker.call(ctx(), Phase.ACT, "g");
        assertThat(shortCircuited.error()).contains("熔断");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(breaker.snapshot().get("shortCircuits")).isEqualTo(1L);
        assertThat(breaker.snapshot().get("trips")).isEqualTo(1L);
    }

    @Test
    void circuitBreakerShouldHalfOpenAfterCooldownThenCloseOnSuccess() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        // 第 1 次失败，之后全部成功
        Engine engine = (c, phase, goal) -> Mono.just(
                calls.getAndIncrement() == 0 ? transientFail("connection refused") : ok(7));

        RetryModelGateway inner = new RetryModelGateway(new EngineModelGateway(engine), 1, 0L);
        CircuitBreakerModelGateway breaker = new CircuitBreakerModelGateway(inner, 1, 30L);

        assertThat(breaker.call(ctx(), Phase.ACT, "g").error()).isNotNull();
        assertThat(breaker.state()).isEqualTo(CircuitBreakerModelGateway.State.OPEN);

        Thread.sleep(60L); // 等待冷却到期
        assertThat(breaker.state()).isEqualTo(CircuitBreakerModelGateway.State.HALF_OPEN);

        EngineRunResult probed = breaker.call(ctx(), Phase.ACT, "g"); // 探测请求
        assertThat(probed.error()).isNull();
        assertThat(breaker.state()).isEqualTo(CircuitBreakerModelGateway.State.CLOSED);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void circuitBreakerShouldNotCountAskAsFailure() {
        AtomicInteger calls = new AtomicInteger();
        Engine engine = scripted(List.of(
                EngineRunResult.of(SESSION, Phase.ACT, "需要你确认", 0L, null, "ask")), calls);

        RetryModelGateway inner = new RetryModelGateway(new EngineModelGateway(engine), 1, 0L);
        CircuitBreakerModelGateway breaker = new CircuitBreakerModelGateway(inner, 1, 60_000L);

        breaker.call(ctx(), Phase.ACT, "g");
        breaker.call(ctx(), Phase.ACT, "g");

        // ask 是业务语义而非故障，熔断器必须保持闭合
        assertThat(breaker.state()).isEqualTo(CircuitBreakerModelGateway.State.CLOSED);
        assertThat(breaker.snapshot().get("consecutiveFailures")).isEqualTo(0);
    }

    @Test
    void engineGatewayShouldConvertExceptionToErrorResult() {
        Engine throwing = (c, phase, goal) -> {
            throw new IllegalStateException("boom");
        };
        EngineRunResult result = new EngineModelGateway(throwing).call(ctx(), Phase.ACT, "g");

        // 「错误即数据」：不外抛异常，便于外层重试/熔断只判定结果
        assertThat(result.error()).isEqualTo("boom");
        assertThat(result.status()).isEqualTo("error");
    }
}
