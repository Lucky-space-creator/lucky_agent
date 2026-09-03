package com.lucky.agent.model.endpoint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * EndpointFormat：格式识别与 baseUrl 原样透传（不做路径补全，路径由 LangChain4j 官方模型拼接）。
 */
class EndpointFormatTest {

    @Test
    void resolvesDeepSeekAnthropicBaseUrlVerbatim() {
        String url = "https://api.deepseek.com/anthropic";
        assertEquals(url, EndpointFormat.resolveUrl(url));
        assertEquals(EndpointFormat.ANTHROPIC, EndpointFormat.fromUrl(url));
        assertEquals("/messages", EndpointFormat.endpointPath(url));
    }

    @Test
    void resolvesDeepSeekOpenAiBaseUrlVerbatim() {
        String url = "https://api.deepseek.com";
        assertEquals(url, EndpointFormat.resolveUrl(url));
        assertEquals(EndpointFormat.OPENAI, EndpointFormat.fromUrl(url));
        assertEquals("/chat/completions", EndpointFormat.endpointPath(url));
    }

    @Test
    void resolvesAliyunCompatibleModeBaseUrl() {
        String url = "https://llm.example.com/compatible-mode/v1";
        assertEquals(url, EndpointFormat.resolveUrl(url));
        assertEquals(EndpointFormat.OPENAI, EndpointFormat.fromUrl(url));
    }

    @Test
    void resolvesAnthropicMessagesUrl() {
        String url = "https://api.anthropic.com/v1/messages";
        assertEquals(url, EndpointFormat.resolveUrl(url));
        assertEquals(EndpointFormat.ANTHROPIC, EndpointFormat.fromUrl(url));
    }

    @Test
    void trimsTrailingSlash() {
        assertEquals("https://api.deepseek.com/chat/completions",
                EndpointFormat.resolveUrl("https://api.deepseek.com/chat/completions/"));
    }

    @Test
    void rejectsNonHttpUrl() {
        assertThrows(IllegalArgumentException.class, () -> EndpointFormat.resolveUrl("http://not a url"));
    }

    @Test
    void returnsBlankWhenBlank() {
        assertEquals("", EndpointFormat.resolveUrl(""));
        assertEquals(null, EndpointFormat.resolveUrl(null));
    }
}
