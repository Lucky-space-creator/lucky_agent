package com.lucky.agent.model.support.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.model.api.dto.InferenceDepth;
import com.lucky.agent.model.api.dto.ModelConfig;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 思考模式观测结果必须跨模型实例保留（台账 #29）。
 *
 * <p>真实链路里 {@link ModelEndpointImpl#toModel()} 每轮重建模型实例（保证推理深度改完即时生效），
 * 而「该端点会产出 reasoning_content」是上一轮才观测到的事实。若观测结果放在实例字段上，
 * 第二轮起 {@code thinkingMode()} 回落 false ⇒ {@link ReasoningPassbackSupport} 跳过归一化 ⇒
 * 历史里那条没思考链的 assistant 带着 tools 发给厂商 ⇒ 400。本测试用「实例 A 观测 → 新实例 B 判定」
 * 复现这一跨轮场景。</p>
 */
class ThinkingObservedPersistenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String THINKING = "上一轮想过了";

    private HttpServer server;
    private List<String> bodies;

    @BeforeEach
    void setUp() throws Exception {
        OpenAiCompatibleModel.resetObservedThinking();
        bodies = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = thinkingResponse().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        OpenAiCompatibleModel.resetObservedThinking();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("第一轮观测到思考后，重建的新实例仍应判定为思考模式")
    void observationSurvivesInstanceRebuild() throws Exception {
        // 第一轮：实例 A 收到带 reasoning_content 的响应 ⇒ 记录为负责思考的端点
        OpenAiCompatibleModel first = model("deepseek-reasoner");
        first.chat(plainRequest());
        assertEquals(1, bodies.size());

        // 第二轮：实例 B 是新建的（等价 ModelEndpointImpl.toModel() 每轮重建）。
        // 请求历史里有一条「没有思考链的 assistant」+ tools —— 若 B 误判为非思考模式，该消息会被原样发出。
        OpenAiCompatibleModel second = model("deepseek-reasoner");
        second.chat(requestWithPlainAssistant());

        JsonNode sent = MAPPER.readTree(bodies.get(1)).get("messages");
        assertEquals(1, sent.size(),
                "新实例必须沿用上一轮的思考观测（剔除无思考的纯文本 assistant），否则厂商 400");
        assertEquals("user", sent.get(0).get("role").asText());
    }

    @Test
    @DisplayName("换模型（同端点不同 modelName）应重新观测，不得沿用旧结论")
    void differentModelStartsUnobserved() throws Exception {
        OpenAiCompatibleModel first = model("deepseek-reasoner");
        first.chat(plainRequest());
        assertEquals(1, bodies.size());

        // 同 baseUrl、同端点 id，但换了模型名：这是一个新的能力主体，不该继承上一个模型的观测
        OpenAiCompatibleModel other = model("gpt-4o-mini");
        other.chat(requestWithPlainAssistant());

        JsonNode sent = MAPPER.readTree(bodies.get(1)).get("messages");
        assertEquals(2, sent.size(), "未观测过思考的模型不做归一化，历史必须原样保留");
        assertNull(sent.get(1).get("reasoning_content"));
    }

    private OpenAiCompatibleModel model(String modelName) {
        ModelConfig config = ModelConfig.of("deepseek",
                "http://127.0.0.1:" + server.getAddress().getPort(), modelName);
        config.apiKey("sk-test");
        // OFF：不发送 reasoning_effort，因此 thinkingMode() 完全取决于「是否观测到过思考」
        return new OpenAiCompatibleModel(config, InferenceDepth.OFF, null);
    }

    private ChatRequest plainRequest() {
        return ChatRequest.builder().messages(List.of(UserMessage.from("你好"))).build();
    }

    private ChatRequest requestWithPlainAssistant() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("只回复两个字：收到"));
        messages.add(AiMessage.from("收到"));
        return ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(List.of(ToolSpecification.builder()
                        .name("file_read").description("读文件").build()))
                .build();
    }

    private String thinkingResponse() {
        return "{\"id\":\"1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"deepseek-reasoner\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"好的\","
                + "\"reasoning_content\":\"" + THINKING + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    }
}
