package com.lucky.agent.core.util.engine;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ThinkingConsistencyTest {

    private static final ToolExecutionRequest TOOL_CALL = ToolExecutionRequest.builder()
            .id("c1").name("file.list").arguments("{}").build();

    @Test
    void nullOrEmptyReturnsAsIs() {
        assertThat(ThinkingConsistency.normalizeForThinkingMode(null)).isNull();
        assertThat(ThinkingConsistency.normalizeForThinkingMode(List.of())).isEmpty();
    }

    @Test
    void nonThinkingModeLeavesAllMessagesUntouched() {
        List<ChatMessage> msgs = List.of(
                UserMessage.from("hi"),
                AiMessage.from("answer without thinking"));
        List<ChatMessage> out = ThinkingConsistency.normalizeForThinkingMode(msgs);
        assertThat(out).containsExactlyElementsOf(msgs);
    }

    @Test
    void thinkingModeDropsPureTextNoThinkingAssistant() {
        AiMessage withThinking = AiMessage.builder().text("thought answer").thinking("I think...").build();
        AiMessage noThinking = AiMessage.from("plain answer, no thinking");
        List<ChatMessage> msgs = List.of(
                SystemMessage.from("sys"),
                UserMessage.from("q"),
                withThinking,
                noThinking);

        List<ChatMessage> out = ThinkingConsistency.normalizeForThinkingMode(msgs);

        assertThat(out).contains(SystemMessage.from("sys"), UserMessage.from("q"), withThinking);
        assertThat(out).doesNotContain(noThinking);
        assertThat(out).hasSize(3);
    }

    @Test
    void thinkingModeBackfillsPlaceholderForToolCallNoThinkingAssistant() {
        AiMessage withThinking = AiMessage.builder().text("plan").thinking("I think...").build();
        AiMessage toolNoThinking = AiMessage.builder()
                .text("let me list files").toolExecutionRequests(List.of(TOOL_CALL)).build();
        ToolExecutionResultMessage toolResult =
                ToolExecutionResultMessage.from(TOOL_CALL.id(), TOOL_CALL.name(), "result");

        List<ChatMessage> msgs = List.of(
                UserMessage.from("q"),
                withThinking,
                toolNoThinking,
                toolResult);

        List<ChatMessage> out = ThinkingConsistency.normalizeForThinkingMode(msgs);

        // tool-call assistant 必须保留（否则 toolResult 变成孤儿），但补占位思考
        assertThat(out).contains(toolResult);
        AiMessage fixed = out.stream()
                .filter(m -> m instanceof AiMessage a && a.toolExecutionRequests() != null
                        && !a.toolExecutionRequests().isEmpty())
                .map(m -> (AiMessage) m)
                .findFirst()
                .orElseThrow();
        assertThat(fixed.thinking()).isEqualTo(ThinkingConsistency.PLACEHOLDER_THINKING);
        assertThat(fixed.thinking()).isNotBlank(); // 必须非空，否则仍触发 isNullOrEmpty 守卫
        assertThat(fixed.toolExecutionRequests()).containsExactly(TOOL_CALL);
    }

    @Test
    void thinkingModeKeepsThinkingAssistantUnchanged() {
        AiMessage withThinking = AiMessage.builder().text("answer").thinking("deep thought").build();
        List<ChatMessage> msgs = List.of(UserMessage.from("q"), withThinking);
        List<ChatMessage> out = ThinkingConsistency.normalizeForThinkingMode(msgs);
        assertThat(out).containsExactly(UserMessage.from("q"), withThinking);
        assertThat(((AiMessage) out.get(1)).thinking()).isEqualTo("deep thought");
    }
}
