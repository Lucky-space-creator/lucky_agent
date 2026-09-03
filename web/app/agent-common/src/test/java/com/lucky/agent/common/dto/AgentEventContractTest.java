package com.lucky.agent.common.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentEvent 契约结构测试（与 resources/file/契约定义.md §1 对齐）。
 */
class AgentEventContractTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void testThoughtEventJsonStructure() throws Exception {
        AgentEvent event = AgentEvent.thought("s1", "思考内容");
        JsonNode node = mapper.readTree(mapper.writeValueAsString(event));

        assertEquals("1.0", node.get("version").asText());
        assertEquals("thought", node.get("type").asText());
        assertEquals("s1", node.get("sessionId").asText());
        assertTrue(node.has("eventId"));
        assertTrue(node.has("ts"));
        assertEquals("思考内容", node.get("payload").get("content").asText());
    }

    @Test
    void testToolResultPayloadFields() throws Exception {
        AgentEvent event = AgentEvent.toolResult("s1", "call-1", "file.read", true, "ok", Map.of("size", 3), null);
        JsonNode payload = mapper.readTree(mapper.writeValueAsString(event)).get("payload");

        assertEquals("call-1", payload.get("callId").asText());
        assertEquals("file.read", payload.get("source").asText());
        assertTrue(payload.get("ok").asBoolean());
        assertEquals("ok", payload.get("summary").asText());
        assertTrue(payload.has("data"));
    }

    @Test
    void testAskWithOpPayload() throws Exception {
        Map<String, Object> op = Map.of("opType", "DELETE", "path", "a.txt");
        AgentEvent event = AgentEvent.ask("s1", "是否删除？", "HIGH", op);
        JsonNode payload = mapper.readTree(mapper.writeValueAsString(event)).get("payload");

        assertEquals("是否删除？", payload.get("question").asText());
        assertEquals("HIGH", payload.get("risk").asText());
        assertEquals("DELETE", payload.get("op").get("opType").asText());
        assertEquals("a.txt", payload.get("op").get("path").asText());
    }

    @Test
    void testStopEvent() throws Exception {
        AgentEvent event = AgentEvent.stop("s1", "success", "完成");
        JsonNode payload = mapper.readTree(mapper.writeValueAsString(event)).get("payload");
        assertEquals("success", payload.get("reason").asText());
        assertEquals("完成", payload.get("summary").asText());
    }
}
