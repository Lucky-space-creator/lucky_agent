package com.lucky.agent.core.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.common.dto.SessionSnapshot;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * replaceMessages / clearMessages 闭环测试（对应 /compact 写回与 /undo 清空）。
 *
 * <p>这两条是 batch-4 新引入的持久化写入路径：压缩后把新消息列表整文件重写、撤销到首条时清空。
 * 关键是「按 role+content 对齐保留原 ts 与检查点引用」，否则压缩/撤销后历史顺序与文件还原点会错位。</p>
 */
class SessionRepositoryWritebackTest {

    private static final String SESSION = "sess-writeback";

    @TempDir
    Path tempDir;

    private SessionRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        WorkspaceDirs dirs = new WorkspaceDirs();
        setField(dirs, "frameworkRoot", tempDir.toString());
        setField(dirs, "workspaceRoot", tempDir.resolve("ws").toString());
        dirs.init();
        repository = new SessionRepository(dirs, new ObjectMapper());
    }

    @Test
    @DisplayName("replaceMessages：按 role+content 对齐，保留原 ts 与检查点引用")
    void replaceMessagesKeepsTsAndCheckpoints() {
        repository.appendMessage(SESSION, "user", "你好", "t1", null, null);
        repository.appendMessage(SESSION, "assistant", "答复", "t2", List.of("cp-1", "cp-2"), null);

        repository.replaceMessages(SESSION, List.of(
                UserMessage.from("你好"),
                AiMessage.from("答复")));

        List<SessionSnapshot.MessageRecord> msgs = repository.loadMessages(SESSION);
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(0).role()).isEqualTo("user");
        assertThat(msgs.get(0).content()).isEqualTo("你好");
        assertThat(msgs.get(0).ts()).isEqualTo("t1");
        assertThat(msgs.get(1).role()).isEqualTo("assistant");
        assertThat(msgs.get(1).content()).isEqualTo("答复");
        assertThat(msgs.get(1).ts()).isEqualTo("t2");
        assertThat(msgs.get(1).checkpointIds()).containsExactly("cp-1", "cp-2");
    }

    @Test
    @DisplayName("clearMessages：清空全部消息并返回被清条数")
    void clearMessagesEmpties() {
        repository.appendMessage(SESSION, "user", "a", "t1", null, null);
        repository.appendMessage(SESSION, "assistant", "b", "t2", null, null);

        int cleared = repository.clearMessages(SESSION);
        assertThat(cleared).isEqualTo(2);
        assertThat(repository.loadMessages(SESSION)).isEmpty();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
