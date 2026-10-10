package com.lucky.agent.cli.term;

import com.lucky.agent.cli.session.SessionHolder;
import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.executor.api.FileService;
import org.jline.reader.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @} 路径补全的契约：经执行臂取数、异步预取 + 缓存、<b>绝不阻塞</b>。
 */
class WorkspacePathCompleterTest {

    private static SessionHolder holder() {
        return new SessionHolder(new SessionRef("s-1", "local-user", "ws-1"));
    }

    private static ExecResult listing(ExecResult.FileEntry... entries) {
        return new ExecResult().ok(true).entries(List.of(entries));
    }

    private static ExecResult.FileEntry dir(String name, String path) {
        return new ExecResult.FileEntry(name, path, true, 0);
    }

    private static ExecResult.FileEntry file(String name, String path, long size) {
        return new ExecResult.FileEntry(name, path, false, size);
    }

    /** 桩：根目录 3 项，src 目录 2 项；其它路径返回空。 */
    private static FileService stubFs() {
        return new FileService() {
            @Override
            public Mono<ExecResult> read(String workspaceId, String path) {
                return Mono.empty();
            }

            @Override
            public Mono<ExecResult> write(String workspaceId, String path, String content) {
                return Mono.empty();
            }

            @Override
            public Mono<ExecResult> list(String workspaceId, String path) {
                if (path == null || path.isEmpty()) {
                    return Mono.just(listing(dir("src", "src"), file("README.md", "README.md", 2048),
                            file("pom.xml", "pom.xml", 512)));
                }
                if ("src".equals(path)) {
                    return Mono.just(listing(file("Main.java", "src/Main.java", 100),
                            dir("util", "src/util")));
                }
                return Mono.just(listing());
            }

            @Override
            public Mono<ExecResult> delete(String workspaceId, String path) {
                return Mono.empty();
            }

            @Override
            public Mono<ExecResult> stat(String workspaceId, String path) {
                return Mono.empty();
            }

            @Override
            public Mono<ExecResult> mkdir(String workspaceId, String path) {
                return Mono.empty();
            }

            @Override
            public Mono<ExecResult> rename(String workspaceId, String from, String to) {
                return Mono.empty();
            }

            @Override
            public Mono<ExecResult> exec(String workspaceId, String command) {
                return Mono.empty();
            }
        };
    }

    private static List<Candidate> completeToString(WorkspacePathCompleter completer, String word) {
        List<Candidate> out = new ArrayList<>();
        completer.complete(word, out);
        return out;
    }

    @Test
    @DisplayName("纯函数：目录带尾斜杠且不追加空格，文件为完整 token；按前缀过滤")
    void candidatesForFiltersAndMarksDirs() {
        List<ExecResult.FileEntry> entries = List.of(
                dir("src", "src"), file("README.md", "README.md", 2048), file("pom.xml", "pom.xml", 512));

        List<Candidate> all = WorkspacePathCompleter.candidatesFor(entries, "");
        assertEquals(3, all.size());
        Candidate src = all.get(0);
        assertEquals("@src/", src.value(), "目录候选应带尾斜杠，便于继续往里补");
        assertFalse(src.complete(), "目录不应作为完整 token 追加空格");
        Candidate readme = all.get(1);
        assertEquals("@README.md", readme.value());
        assertTrue(readme.complete(), "文件是完整 token，可跟说明文字");

        List<Candidate> onlyS = WorkspacePathCompleter.candidatesFor(entries, "s");
        assertEquals(1, onlyS.size());
        assertEquals("@src/", onlyS.get(0).value());
    }

    @Test
    @DisplayName("候选项数受上限约束，超大目录不刷屏")
    void candidatesCapped() {
        List<ExecResult.FileEntry> many = new ArrayList<>();
        for (int i = 0; i < WorkspacePathCompleter.MAX_CANDIDATES + 50; i++) {
            many.add(file("f" + i, "f" + i, 1));
        }
        assertEquals(WorkspacePathCompleter.MAX_CANDIDATES,
                WorkspacePathCompleter.candidatesFor(many, "").size());
    }

    @Test
    @DisplayName("根目录已预热：输入 @ 首次即出候选（命中缓存）")
    void rootIsWarmOnConstruction() {
        WorkspacePathCompleter completer = new WorkspacePathCompleter(
                stubFs(), holder(), Schedulers.immediate());

        List<Candidate> candidates = completeToString(completer, "@");
        assertEquals(3, candidates.size(), "根目录预取后，@ 应立即给出候选而不是先空一次");
    }

    @Test
    @DisplayName("子目录未命中：本次不阻塞、触发异步；缓存填充后下次命中")
    void subdirMissThenHit() {
        WorkspacePathCompleter completer = new WorkspacePathCompleter(
                stubFs(), holder(), Schedulers.immediate());

        // 首次进入 src/：cache miss → 触发预取（immediate 同步完成）→ 本次返回空
        assertTrue(completeToString(completer, "@src/").isEmpty(), "未命中时本轮不应阻塞等待");
        // 第二次：缓存已填充
        List<Candidate> second = completeToString(completer, "@src/M");
        assertEquals(1, second.size());
        assertEquals("@src/Main.java", second.get(0).value());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("执行臂永不返回时也不阻塞（Mono.never）")
    void neverBlocksOnSlowExecutor() {
        FileService never = new FileService() {
            @Override
            public Mono<ExecResult> read(String workspaceId, String path) {
                return Mono.never();
            }

            @Override
            public Mono<ExecResult> write(String workspaceId, String path, String content) {
                return Mono.never();
            }

            @Override
            public Mono<ExecResult> list(String workspaceId, String path) {
                return Mono.never();
            }

            @Override
            public Mono<ExecResult> delete(String workspaceId, String path) {
                return Mono.never();
            }

            @Override
            public Mono<ExecResult> stat(String workspaceId, String path) {
                return Mono.never();
            }

            @Override
            public Mono<ExecResult> mkdir(String workspaceId, String path) {
                return Mono.never();
            }

            @Override
            public Mono<ExecResult> rename(String workspaceId, String from, String to) {
                return Mono.never();
            }

            @Override
            public Mono<ExecResult> exec(String workspaceId, String command) {
                return Mono.never();
            }
        };
        // 用真实 boundedElastic：证明即使取数永不完成，complete() 也立即返回
        WorkspacePathCompleter completer = new WorkspacePathCompleter(never, holder());

        long start = System.nanoTime();
        List<Candidate> candidates = completeToString(completer, "@anything");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(candidates.isEmpty());
        assertTrue(elapsedMs < 2000, "补全回调不得阻塞（实测 " + elapsedMs + "ms）");
    }

    @Test
    @DisplayName("非 @ 开头的词不产生候选")
    void ignoresNonAtWord() {
        WorkspacePathCompleter completer = new WorkspacePathCompleter(
                stubFs(), holder(), Schedulers.immediate());
        assertTrue(completeToString(completer, "hello").isEmpty());
        assertTrue(completeToString(completer, "/help").isEmpty());
    }

    @Test
    @DisplayName("带说明的输入：只补全紧跟 @ 的首个路径词")
    void completesPathTokenOnly() {
        WorkspacePathCompleter completer = new WorkspacePathCompleter(
                stubFs(), holder(), Schedulers.immediate());
        assertEquals(1, completeToString(completer, "@pom").size());
        assertEquals("@pom.xml", completeToString(completer, "@pom").get(0).value());
    }
}
