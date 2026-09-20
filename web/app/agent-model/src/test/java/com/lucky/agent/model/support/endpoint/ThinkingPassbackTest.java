package com.lucky.agent.model.support.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.model.api.dto.InferenceDepth;
import com.lucky.agent.model.api.dto.ModelConfig;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

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
