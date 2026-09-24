package com.lucky.agent.core.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.common.dto.SessionSnapshot;
import com.lucky.agent.core.config.CoreProperties;
import com.lucky.agent.core.util.runtime.AgentEventPublisher;
import com.lucky.agent.core.util.runtime.ConversationStateManager;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 思考链（reasoning_content）落盘与跨进程回放闭环测试。
 *
 * <p><b>为什么需要这条契约</b>：推理模型（DeepSeek 等）规定——请求<b>携带 tools</b> 时，
 * 历史所有轮的 {@code reasoning_content} 必须原样回传，缺失即被拒（HTTP 400
 * {@code The reasoning_content in the thinking mode must be passed back to the API.}）。
 * 因此 assistant 消息的思考链必须随正文一并落盘，并在回放时重建回 {@code AiMessage.thinking()}；
 * 同时思考链属内部推理产物，<b>不得</b>经通道 DTO（{@link SessionSnapshot.MessageRecord}）外泄。</p>
 */
class SessionRepositoryReplayTest {

    private static final String SESSION = "sess-replay";

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
    @DisplayName("思考链落盘：loadForReplay 可读回 thinking")
    void persistsAndReloadsThinking() {
        repository.appendMessage(SESSION, "user", "你好", "t1", null, null);
        repository.appendMessage(SESSION, "assistant", "答复正文", "t2", null, "先分析再回答的思考链");

        List<SessionRepository.ReplayMessage> replay = repository.loadForReplay(SESSION);

        assertThat(replay).hasSize(2);
        assertThat(replay.get(0).thinking()).isNull();
        assertThat(replay.get(1).role()).isEqualTo("assistant");
        assertThat(replay.get(1).content()).isEqualTo("答复正文");
        assertThat(replay.get(1).thinking()).isEqualTo("先分析再回答的思考链");
    }

    @Test
    @DisplayName("普通模型零影响：思考链为空或空白时不写入该字段")
    void omitsBlankThinking() throws Exception {
        repository.appendMessage(SESSION, "assistant", "普通回复", "t1", null, null);
        repository.appendMessage(SESSION, "assistant", "空白思考链", "t2", null, "   ");

        List<SessionRepository.ReplayMessage> replay = repository.loadForReplay(SESSION);
        assertThat(replay).allSatisfy(r -> assertThat(r.thinking()).isNull());

        String raw = Files.readString(sessionsDir().resolve(SESSION + ".jsonl"));
        assertThat(raw).doesNotContain("thinking");
    }

    @Test
    @DisplayName("思考链含换行与引号：JSONL 往返无损")
    void roundTripsMultilineThinking() {
        String thinking = "第一步：查看 \"config.json\"\n第二步：核对换行\r\n第三步：结束";
        repository.appendMessage(SESSION, "assistant", "正文", "t1", null, thinking);

        assertThat(repository.loadForReplay(SESSION).get(0).thinking()).isEqualTo(thinking);
    }

    @Test
    @DisplayName("通道 DTO 不外泄思考链：MessageRecord 仍为 4 字段")
    void channelDtoDoesNotExposeThinking() {
        repository.appendMessage(SESSION, "assistant", "正文", "t1", null, "内部思考链");

        assertThat(SessionSnapshot.MessageRecord.class.getRecordComponents()).hasSize(4);
        assertThat(repository.loadMessages(SESSION).get(0).content()).isEqualTo("正文");
    }

    @Test
    @DisplayName("跨进程回放：assistant 消息重建出带 thinking 的 AiMessage")
    void restoresThinkingIntoAiMessage() {
        repository.appendMessage(SESSION, "user", "第一问", "t1", null, null);
        repository.appendMessage(SESSION, "assistant", "第一答", "t2", null, "第一答的思考链");
        repository.appendMessage(SESSION, "user", "第二问", "t3", null, null);

        List<ChatMessage> messages = restoredMessages();

        assertThat(messages).hasSize(3);
        assertThat(messages.get(1)).isInstanceOf(AiMessage.class);
        AiMessage ai = (AiMessage) messages.get(1);
        assertThat(ai.text()).isEqualTo("第一答");
        assertThat(ai.thinking()).isEqualTo("第一答的思考链");
    }

    @Test
    @DisplayName("回放向后兼容：无 thinking 的旧记录仍能重建为普通 assistant 消息")
    void restoresLegacyRecordsWithoutThinking() {
        repository.appendMessage(SESSION, "user", "旧问", "t1");
        repository.appendMessage(SESSION, "assistant", "旧答", "t2");

        List<ChatMessage> messages = restoredMessages();

        assertThat(messages).hasSize(2);
        AiMessage ai = (AiMessage) messages.get(1);
        assertThat(ai.text()).isEqualTo("旧答");
        assertThat(ai.thinking()).isNull();
    }

    /** 走真实回放路径：新建 ConversationStateManager，其 session(ref) 会从磁盘回灌上下文。 */
    private List<ChatMessage> restoredMessages() {
        ConversationStateManager manager = new ConversationStateManager(
                new AgentEventPublisher(), new CoreProperties(
                        0, 0, 0, 0L, false, 0, 0L, 0, 0, null, null, 0L, 0L, 0.0),
                repository);
        return manager.session(SessionRef.of(SESSION, "u1", "w1")).messages();
    }

    private Path sessionsDir() {
        return tempDir.resolve("agent").resolve("sessions");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
