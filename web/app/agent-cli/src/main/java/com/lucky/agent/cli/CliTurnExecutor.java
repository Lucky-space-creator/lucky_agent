package com.lucky.agent.cli;

import com.lucky.agent.cli.channel.CliChannel;
import com.lucky.agent.cli.channel.TurnCapture;
import com.lucky.agent.common.dto.RunResult;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.common.dto.UserInput;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 *
 * <p><b>运行期 Ctrl+C 只由 {@link InterruptTrap} 处理</b>：主线程阻塞在提交上、并不在行编辑里，
 * 终端此时是 cooked 模式，Ctrl+C 由终端驱动转成 SIGINT，<b>不会</b>以 0x03 进入标准输入。
 * 曾有一条旁路线程轮询 {@code System.in.available()} 找 0x03：既拿不到真正的 Ctrl+C，
 * 又会把用户提前键入的内容（typeahead）逐字节吃掉，导致下一轮输入整行丢失。
 * 不要再引入任何读取标准输入的运行期监听。</p>
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

        // 本轮内临时接管 SIGINT：见 InterruptTrap 的类注释（缺陷 D17）。
        // 与 watchCancel 同开关：headless 下 stdin 承载数据、也不该吞掉脚本的信号语义。
        InterruptTrap trap = watchCancel
                ? InterruptTrap.install(() -> channel.cancel(ref).subscribe())
                : InterruptTrap.disabled();

        RunResult result = null;
        Throwable failure = null;
        boolean timedOut = false;
        try {
            // ② 后提交：cold Mono，订阅时才真正开跑；订阅者已就绪，首条事件即刻可见
            result = channel.submit(ref, UserInput.of(content, extra)).block(TURN_TIMEOUT);
        } catch (Exception e) {
            if (isTimeout(e)) {
                timedOut = true;
                // 超时不能只是「不等了」：内核线程仍在执行，会继续改文件、占用会话，
                // 下一次提交还会与它竞争同一会话状态（幽灵运行）。必须发取消让它退出。
                log.warn("单轮等待超时（{}），发起取消并释放订阅：session={}", TURN_TIMEOUT, ref.sessionId());
                requestCancel(ref, "超时");
            } else {
                failure = e;
            }
        } finally {
            // 必须先恢复原 SIGINT 处理器：提示符处 JLine 处于 raw 模式、根本不产生 SIGINT，
            // 但一旦有异常路径让终端停在 cooked 模式，留着我们的处理器会吞掉用户唯一的退出手段
            trap.close();
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

    /**
     * 判定是否为「等待超时」。
     *
     * <p>{@code Mono#block(Duration)} 超时抛 {@link IllegalStateException}，文案形如
     * {@code "Timeout on blocking read for ..."}。此处做两点稳健化：① 大小写不敏感匹配
     * （不依赖 Reactor 的精确拼写）；② 沿 {@code cause} 链查找（响应式栈可能把原始异常包一层）。
     * 刻意仍限定 {@link IllegalStateException}，避免把模型侧连接超时（{@code TimeoutException}）
     * 误判为「本轮等待超时」而改变退出码语义。</p>
     */
    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof IllegalStateException) {
                String msg = t.getMessage();
                if (msg != null && msg.toLowerCase(Locale.ROOT).contains("timeout")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 尽力发起取消（超时等补偿动作）。
     *
     * <p>失败只记日志：取消是"止损"而非必需步骤，不应因为取消本身失败而丢掉本轮结果。</p>
     */
    private void requestCancel(SessionRef ref, String reason) {
        try {
            channel.cancel(ref).subscribe(null,
                    err -> log.warn("{}后取消失败：session={} err={}", reason, ref.sessionId(), err.getMessage()));
        } catch (Exception e) {
            log.warn("{}后取消发起异常：session={} err={}", reason, ref.sessionId(), e.getMessage());
        }
    }
}
