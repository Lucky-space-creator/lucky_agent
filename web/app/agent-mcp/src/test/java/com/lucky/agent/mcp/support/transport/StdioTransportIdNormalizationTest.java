package com.lucky.agent.mcp.support.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSON-RPC 响应 id 的形态归一化（台账 #30）。
 *
 * <p>JSON-RPC 2.0 允许 id 为数字<b>或字符串</b>；不少 MCP 服务端实现会把数字 id 字符串化后回传。
 * 此前读线程只认 {@code Number}，字符串 id 落进「通知」分支被静默丢弃 ——
 * 请求等满 10s 超时才失败，日志里没有任何线索（debug 级别且只打原文）。
 * 本测试锁住「字符串数字必须能匹配到挂起请求」这一根因。</p>
 */
class StdioTransportIdNormalizationTest {

    @Test
    @DisplayName("JSON 数字 id 归一化为 long")
    void numberIdIsNormalized() {
        assertThat(StdioTransport.normalizeId(7)).isEqualTo(7L);
        assertThat(StdioTransport.normalizeId(42L)).isEqualTo(42L);
    }

    @Test
    @DisplayName("字符串化 id 必须能匹配（此前被丢弃 → 每个请求等满超时）")
    void stringifiedIdIsNormalized() {
        assertThat(StdioTransport.normalizeId("7")).isEqualTo(7L);
        assertThat(StdioTransport.normalizeId(" 128 ")).isEqualTo(128L);
    }

    @Test
    @DisplayName("经 JSON 解析后的两种 id 形态结果一致")
    void parsedResponsesNormalizeToSameKey() {
        ObjectMapper mapper = new ObjectMapper();
        // convertValue(..., Object.class) 对 JSON number 给 Integer/Long，对 JSON string 给 String
        assertThat(StdioTransport.normalizeId(JsonRpcResponse.parse(mapper, "{\"id\":9,\"result\":{}}").id()))
                .isEqualTo(9L);
        assertThat(StdioTransport.normalizeId(JsonRpcResponse.parse(mapper, "{\"id\":\"9\",\"result\":{}}").id()))
                .isEqualTo(9L);
    }

    @Test
    @DisplayName("非数字 id 与缺失 id 返回 null（按通知处理，不误配挂起请求）")
    void nonNumericIdIsNotMatched() {
        assertThat(StdioTransport.normalizeId(null)).isNull();
        assertThat(StdioTransport.normalizeId("")).isNull();
        assertThat(StdioTransport.normalizeId("   ")).isNull();
        assertThat(StdioTransport.normalizeId("req-abc")).isNull();
        assertThat(StdioTransport.normalizeId("550e8400-e29b-41d4-a716-446655440000")).isNull();
    }
}
