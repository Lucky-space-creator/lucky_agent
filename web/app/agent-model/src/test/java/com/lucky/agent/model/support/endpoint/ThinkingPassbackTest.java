package com.lucky.agent.model.support.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.model.api.dto.InferenceDepth;
import com.lucky.agent.model.api.dto.ModelConfig;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 思考链回传（thinking 模式契约）wire 级验收。
 *
 * <p>推理模型（deepseek-reasoner 等）在响应里返回 {@code reasoning_content}，并强制要求下一轮
 * 请求原样回传，否则第二次调用直接被拒。本测试用本地 HTTP 服务模拟厂商协议，断言：</p>
 * <ol>
 *     <li>响应中的 {@code reasoning_content} 被解析进 {@code AiMessage.thinking()}（returnThinking）；</li>
 *     <li>第二轮请求的 assistant 消息里带回了 {@code reasoning_content} 且内容一致（sendThinking）；</li>
 *     <li>非思考模型（响应无该字段）不会写出任何多余参数。</li>
 * </ol>
 */
class ThinkingPassbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String THINKING_1 = "思考链A";
    private static final String THINKING_2 = "思考链B";

    /**
     * 「已观测到思考」是端点级静态状态（台账 #29：模型实例每轮重建，观测结果必须跨实例保留），
     * 因此用例之间会互相污染 —— 例如「观测到思考」的用例会让后面期待「从未观测」的用例翻车。
     * 每个用例前复位，保证各用例的初始条件是「全新进程」。
     */
    @BeforeEach
    void resetObservedState() {
        OpenAiCompatibleModel.resetObservedThinking();
    }

    @AfterEach
    void clearObservedState() {
        OpenAiCompatibleModel.resetObservedThinking();
    }

    @Test
    void passesReasoningContentBackOnNextRound() throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer server = startServer(bodies, toolCallResponse(THINKING_1), finalResponse(THINKING_2));
        try {
            OpenAiCompatibleModel model = model(server);
            AiMessage firstAi = model.chat(firstRequest()).aiMessage();

            // 1) 响应中的 reasoning_content 必须落到 AiMessage.thinking()
            assertEquals(THINKING_1, firstAi.thinking());
            assertEquals(1, firstAi.toolExecutionRequests().size());

            // 2) 工具结果回灌后的第二轮请求必须原样回传 reasoning_content
            model.chat(secondRequest(firstAi));

            assertEquals(2, bodies.size());
            JsonNode assistant = assistantMessageOf(bodies.get(1));
            assertEquals(THINKING_1, assistant.get("reasoning_content").asText());
            assertFalse(assistant.get("tool_calls").isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void omitsReasoningContentForNonThinkingModel() throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer server = startServer(bodies, toolCallResponse(null), finalResponse(null));
        try {
            OpenAiCompatibleModel model = model(server);
            AiMessage firstAi = model.chat(firstRequest()).aiMessage();
            assertNull(firstAi.thinking());

            model.chat(secondRequest(firstAi));

            assertEquals(2, bodies.size());
            assertNull(assistantMessageOf(bodies.get(1)).get("reasoning_content"));
        } finally {
            server.stop(0);
        }
    }

    private OpenAiCompatibleModel model(HttpServer server) {
        ModelConfig config = ModelConfig.of("deepseek",
                "http://127.0.0.1:" + server.getAddress().getPort(), "deepseek-reasoner");
        config.apiKey("sk-test");
        // usageTracker 传 null：本用例只断言请求体（reasoning_content 回传），不涉及用量上报，
        // OpenAiCompatibleModel 对 tracker 为 null 已做判空保护。
        return new OpenAiCompatibleModel(config, InferenceDepth.OFF, null);
    }

    private OpenAiCompatibleModel thinkingModel(HttpServer server) {
        ModelConfig config = ModelConfig.of("deepseek",
                "http://127.0.0.1:" + server.getAddress().getPort(), "deepseek-v4-flash");
        config.apiKey("sk-test");
        // BALANCED：请求携带 reasoning_effort ⇒ 端点判定为思考模式（本缺陷真实场景）
        return new OpenAiCompatibleModel(config, InferenceDepth.BALANCED, null);
    }

    private ToolSpecification toolSpec() {
        return ToolSpecification.builder().name("file_read").description("读文件").build();
    }

    /**
     * D15 主场景：思考模式下，历史里唯一一条 assistant 没有思考链（极短回答实测可为空），
     * 该消息不带 tool_calls ⇒ 必须从发往厂商的请求中剔除，否则厂商 400。
     */
    @Test
    void dropsPlainAssistantWithoutThinkingInThinkingMode() throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer server = startServer(bodies, finalResponse(null), finalResponse(null));
        try {
            OpenAiCompatibleModel model = thinkingModel(server);
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(UserMessage.from("只回复两个字：收到"));
            messages.add(AiMessage.from("收到"));
            model.chat(ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(List.of(toolSpec()))
                    .build());

            JsonNode sent = MAPPER.readTree(bodies.get(0)).get("messages");
            assertEquals(1, sent.size(), "无思考的纯文本 assistant 应被剔除，只留 user");
            assertEquals("user", sent.get(0).get("role").asText());
        } finally {
            server.stop(0);
        }
    }

    /**
     * 思考模式下，无思考但带 tool_calls 的 assistant 不能剔除（会孤儿化 tool 结果），
     * 必须补占位 reasoning_content 保住配对。
     */
    @Test
    void keepsToolCallAssistantWithPlaceholderThinkingInThinkingMode() throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer server = startServer(bodies, finalResponse(null), finalResponse(null));
        try {
            OpenAiCompatibleModel model = thinkingModel(server);
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("call_1").name("file_read").arguments("{}").build();
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(UserMessage.from("读一下 a.txt"));
            messages.add(AiMessage.builder().toolExecutionRequests(List.of(req)).build());
            messages.add(ToolExecutionResultMessage.from(req.id(), req.name(), "ok"));
            model.chat(ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(List.of(toolSpec()))
                    .build());

            JsonNode sent = MAPPER.readTree(bodies.get(0)).get("messages");
            assertEquals(3, sent.size());
            JsonNode assistant = sent.get(1);
            assertEquals("assistant", assistant.get("role").asText());
            assertTrue(assistant.hasNonNull("reasoning_content"), "应补占位思考");
            assertFalse(assistant.get("reasoning_content").asText().isBlank());
            assertFalse(assistant.get("tool_calls").isEmpty(), "tool_calls 必须保留");
        } finally {
            server.stop(0);
        }
    }

    /**
     * 反向约束：非思考模式端点（深度 OFF 且从未产出思考）不做任何归一化，
     * 避免无谓丢弃历史上下文（普通模型零影响）。
     */
    @Test
    void keepsPlainAssistantWhenEndpointNotInThinkingMode() throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer server = startServer(bodies, finalResponse(null), finalResponse(null));
        try {
            OpenAiCompatibleModel model = model(server); // InferenceDepth.OFF，且测试内无思考响应
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(UserMessage.from("只回复两个字：收到"));
            messages.add(AiMessage.from("收到"));
            model.chat(ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(List.of(toolSpec()))
                    .build());

            JsonNode sent = MAPPER.readTree(bodies.get(0)).get("messages");
            assertEquals(2, sent.size(), "非思考模式不得剔除历史消息");
            assertEquals("assistant", sent.get(1).get("role").asText());
            assertNull(sent.get(1).get("reasoning_content"));
        } finally {
            server.stop(0);
        }
    }

    private ChatRequest firstRequest() {
        return ChatRequest.builder().messages(List.of(UserMessage.from("读一下 a.txt"))).build();
    }

    private ChatRequest secondRequest(AiMessage firstAi) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("读一下 a.txt"));
        messages.add(firstAi);
        messages.add(ToolExecutionResultMessage.from(
                firstAi.toolExecutionRequests().get(0).id(),
                firstAi.toolExecutionRequests().get(0).name(), "ok"));
        return ChatRequest.builder().messages(messages).build();
    }

    private JsonNode assistantMessageOf(String requestBody) throws Exception {
        return MAPPER.readTree(requestBody).get("messages").get(1);
    }

    /** 启动本地桩服务，按调用顺序依次返回给定的响应体，并记录每次收到的请求体。 */
    private HttpServer startServer(List<String> bodies, String firstResponse, String secondResponse)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String json = bodies.size() == 1 ? firstResponse : secondResponse;
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    /** 工具调用响应（thinking 为空时模拟普通模型，不返回 reasoning_content）。 */
    private String toolCallResponse(String thinking) {
        String reasoning = thinking == null ? "" : ",\"reasoning_content\":\"" + thinking + "\"";
        return "{\"id\":\"1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"deepseek-reasoner\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"\"" + reasoning
                + ",\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"file_read\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    }

    /** 最终回复响应（thinking 为空时模拟普通模型）。 */
    private String finalResponse(String thinking) {
        String reasoning = thinking == null ? "" : ",\"reasoning_content\":\"" + thinking + "\"";
        return "{\"id\":\"2\",\"object\":\"chat.completion\",\"created\":2,\"model\":\"deepseek-reasoner\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"完成\"" + reasoning
                + "},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":2,\"total_tokens\":4}}";
    }
}
