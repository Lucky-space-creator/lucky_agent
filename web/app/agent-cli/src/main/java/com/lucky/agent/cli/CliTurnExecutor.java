package com.lucky.agent.cli;

import com.lucky.agent.cli.channel.CliChannel;
import com.lucky.agent.cli.channel.TurnCapture;
import com.lucky.agent.common.dto.RunResult;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.common.dto.UserInput;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单轮运行的执行器：负责「先订阅、后提交」的时序，以及本轮收尾。
 *
 * <p><b>为什么必须单独成为一类</b>：这是整个 CLI 里唯一对<b>订阅顺序</b>敏感的地方，
 * 而顺序错了会让实时性整体消失。原实现先 {@code submit().block()} 再订阅事件流，
 * 两件事的先后正好相反——事件在无订阅者期间全部落进
 * {@code AgentEventPublisher} 的暂存队列，等订阅建立时才被一次性排空，
 * 于是整轮运行期间终端没有任何输出，收尾瞬间打印完全部内容（且队列上限 2000，
 * 长任务还会丢最旧事件）。</p>
 *
 * <p><b>为什么不用 {@code Mono.when(submit, consume)}</b>：{@code when} 是并发订阅，
 * 而 {@code submit} 内部是 {@code Mono.fromCallable(...).subscribeOn(boundedElastic)}——
 * 一旦被订阅就立刻在别的线程开跑，与事件流的订阅<b>赛跑</b>。若提交先抢到线程并发出首条事件，
 * 此刻订阅者尚未注册，首屏输出仍会延迟。依赖「参数顺序等于订阅顺序」是脆弱假设，
 * 故此处显式两步：<b>先 subscribe，再 block 提交</b>。</p>
 *
 * <p><b>结束信号取「提交完成」为主</b>：内核 {@code ConversationManager#execute} 的
 * {@code finally} 会先 {@code publisher.complete(sessionId)} 再返回，因此提交 Mono 返回时
 * 事件流必然已经收尾；事件流的 {@code onComplete} 只作为异常路径的兜底宽限信号。</p>
 */
@Slf4j
public final class CliTurnExecutor {

    /** 单轮等待上限：内核自身有回合/预算安全阀，这里仅防「内核线程卡死导致终端永久无响应」。 */
    private static final Duration TURN_TIMEOUT = Duration.ofMinutes(30);

    /** 事件流收尾宽限（正常路径无需等待，异常路径兜底）。 */
    private static final long STREAM_GRACE_SEC = 5L;

    private final CliChannel channel;
    private final boolean watchCancel;

    /**
     * @param channel     CLI 通道（需已注入渲染器）
     * @param watchCancel 是否在运行期监听 Ctrl+C 发起优雅取消（仅交互式 TTY 下开启；
     *                    headless 从 stdin 读提示词时必须为 false，否则会与输入流争抢字节）
     */
    public CliTurnExecutor(CliChannel channel, boolean watchCancel) {
        this.channel = channel;
        this.watchCancel = watchCancel;
    }

    /**
     * 执行一轮。
     *
     * @param ref     会话
     * @param content 用户输入
     * @param extra   附加参数（如 ASK 确认 {@code confirm}、预算覆盖 {@code maxTurns}）
     * @return 本轮产出
     */
    public TurnOutcome execute(SessionRef ref, String content, Map<String, Object> extra) {
        TurnCapture capture = new TurnCapture();
        CountDownLatch streamDone = new CountDownLatch(1);
        AtomicReference<Throwable> streamError = new AtomicReference<>();

        long started = System.currentTimeMillis();

        // ① 先订阅：此刻起内核发布的事件走实时投递（不再落暂存队列）
        Disposable subscription = channel.subscribe(ref)
                .subscribe(event -> channel.onEvent(event, capture),
                        err -> {
                            streamError.set(err);
                            streamDone.countDown();
                        },
                        streamDone::countDown);

        CancelWatcher watcher = watchCancel
                ? CancelWatcher.start(() -> channel.cancel(ref).subscribe())
                : CancelWatcher.disabled();

        RunResult result = null;
        Throwable failure = null;
        boolean timedOut = false;
        try {
            // ② 后提交：cold Mono，订阅时才真正开跑；订阅者已就绪，首条事件即刻可见
            result = channel.submit(ref, UserInput.of(content, extra)).block(TURN_TIMEOUT);
        } catch (Exception e) {
            if (isTimeout(e)) {
                timedOut = true;
                log.warn("单轮等待超时（{}），放弃等待并释放订阅：session={}", TURN_TIMEOUT, ref.sessionId());
            } else {
                failure = e;
            }
        } finally {
            watcher.stop();
        }

        // ③ 等事件流收尾（正常路径瞬时返回，此处仅为异常路径兜底）
        try {
            streamDone.await(STREAM_GRACE_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        subscription.dispose();
        channel.endOfTurn();

        if (failure == null) {
            failure = streamError.get();
        }
        return new TurnOutcome(result, capture, failure, timedOut,
                System.currentTimeMillis() - started);
    }

    /** {@code Mono#block(Duration)} 超时抛出的固定文案，据此区分「超时」与「运行失败」。 */
    private static boolean isTimeout(Throwable e) {
        return e instanceof IllegalStateException
                && e.getMessage() != null && e.getMessage().contains("Timeout");
    }

    /**
     * 运行期的 Ctrl+C 监听（best-effort）。
     *
     * <p>交互式终端进入原始模式后，Ctrl+C 不再触发 SIGINT，而是作为一个字节（0x03）流入标准输入。
     * 运行期间主线程阻塞在提交上、并未处于行编辑状态，故用一条守护线程轮询该字节，
     * 命中即发起优雅取消（置位取消标记让引擎在循环边界尽早退出），而不是让 JVM 直接终止进程。</p>
     *
     * <p><b>已知取舍</b>：运行期间用户提前键入的<b>其它</b>字符会被本监听消费掉，不会进入下一轮输入
     * （主流 CLI 亦普遍忽略运行期 typeahead）。监听失败不影响主流程——最坏情况退化为进程默认行为。</p>
     */
    private static final class CancelWatcher {

        private static final int CTRL_C = 3;
        private static final int CTRL_D = 4;
        private static final long POLL_MS = 80L;

        private final Thread thread;
        private final AtomicBoolean stopped = new AtomicBoolean(false);

        private CancelWatcher(Runnable onCancel, boolean enabled) {
            if (!enabled) {
                this.thread = null;
                return;
            }
            this.thread = new Thread(() -> run(onCancel), "cli-cancel-watcher");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        static CancelWatcher disabled() {
            return new CancelWatcher(null, false);
        }

        static CancelWatcher start(Runnable onCancel) {
            return new CancelWatcher(onCancel, true);
        }

        private void run(Runnable onCancel) {
            AtomicBoolean fired = new AtomicBoolean(false);
            while (!stopped.get()) {
                try {
                    if (System.in.available() > 0) {
                        int b = System.in.read();
                        if ((b == CTRL_C || b == CTRL_D) && fired.compareAndSet(false, true)) {
                            onCancel.run();
                            return;
                        }
                    }
                    Thread.sleep(POLL_MS);
                } catch (IOException e) {
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        void stop() {
            stopped.set(true);
            if (thread != null) {
                thread.interrupt();
            }
        }
    }
}
