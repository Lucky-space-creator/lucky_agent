package com.lucky.agent.model.support.endpoint;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具名映射：点分内部名 ↔ 厂商安全名（OpenAI/Anthropic 要求 ^[a-zA-Z0-9_-]+$）。
 */
class ToolNameMappingTest {

    @Test
    void sanitizesDotNamesInRequest() {
        ToolNameMapper names = new ToolNameMapper();
        ToolSpecification fileRead = ToolSpecification.builder().name("file.read").description("读文件").build();
        ToolSpecification shellExec = ToolSpecification.builder().name("shell.exec").description("执行命令").build();
        ChatRequest req = ChatRequest.builder()
                .messages(List.of(dev.langchain4j.data.message.UserMessage.from("hi")))
                .toolSpecifications(fileRead, shellExec)
                .build();

        ChatRequest wire = ToolNameMappingSupport.sanitizeRequest(req, names);
        List<ToolSpecification> tools = wire.toolSpecifications();
        assertTrue(tools.get(0).name().matches("^[a-zA-Z0-9_-]+$"));
        assertTrue(tools.get(1).name().matches("^[a-zA-Z0-9_-]+$"));
        // 不相等则说明发生了清洗
        assertTrue(tools.get(0).name().equals("file_read") || !tools.get(0).name().equals("file.read"));
    }

    @Test
    void restoresWireNamesInResponse() {
        ToolNameMapper names = new ToolNameMapper();
        String wireName = names.register("file.read");
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("call_1").name(wireName).arguments("{}").build();
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(req)))
                .build();

        ChatResponse restored = ToolNameMappingSupport.restoreResponse(response, names);
        assertEquals("file.read", restored.aiMessage().toolExecutionRequests().get(0).name());
    }

    @Test
    void noToolsPassesThroughRequest() {
        ToolNameMapper names = new ToolNameMapper();
        ChatRequest req = ChatRequest.builder()
                .messages(List.of(dev.langchain4j.data.message.UserMessage.from("hi")))
                .build();
        assertSame(req, ToolNameMappingSupport.sanitizeRequest(req, names));
    }

    @Test
    void noToolCallsPassesThroughResponse() {
        ToolNameMapper names = new ToolNameMapper();
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("hello"))
                .build();
        assertSame(response, ToolNameMappingSupport.restoreResponse(response, names));
    }
}
