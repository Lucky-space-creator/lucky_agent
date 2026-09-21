package com.lucky.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.workflow.domain.enums.RunMode;
import com.lucky.agent.workflow.dto.TriggerRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TriggerRequest} 契约测试：{@code mode} 必须对大小写与空白宽容。
 *
 * <p>回归背景：{@code mode} 原本声明为 {@link RunMode} 枚举，Jackson 大小写敏感，
 * 前端发送小写 {@code "sync"} 会抛 {@code InvalidFormatException} → 包装为
 * {@code ServerWebInputException} → HTTP 400，且 reason 为
 * {@code "Failed to read HTTP message"}（无信息量、前端只能弹英文黑话）。
 * 本测试锁定修复后的宽容语义与明确报错。</p>
 */
class TriggerRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldAcceptLowercaseModeOverJson() throws Exception {
        TriggerRequest req = mapper.readValue("{\"variables\":{\"a\":1},\"mode\":\"sync\"}", TriggerRequest.class);
        assertThat(req.resolveMode()).isEqualTo(RunMode.SYNC);
        assertThat(req.variables()).containsEntry("a", 1);
    }

    @Test
    void shouldAcceptUppercaseAndMixedCaseAndPadding() {
        assertThat(new TriggerRequest(null, "SYNC").resolveMode()).isEqualTo(RunMode.SYNC);
        assertThat(new TriggerRequest(null, "async").resolveMode()).isEqualTo(RunMode.ASYNC);
        assertThat(new TriggerRequest(null, " Async ").resolveMode()).isEqualTo(RunMode.ASYNC);
    }

    @Test
    void shouldDefaultToSyncWhenModeMissingOrBlank() throws Exception {
        assertThat(new TriggerRequest(null, null).resolveMode()).isEqualTo(RunMode.SYNC);
        assertThat(new TriggerRequest(null, "  ").resolveMode()).isEqualTo(RunMode.SYNC);
        // 完全省略 mode 字段
        TriggerRequest req = mapper.readValue("{\"variables\":{}}", TriggerRequest.class);
        assertThat(req.resolveMode()).isEqualTo(RunMode.SYNC);
    }

    @Test
    void shouldNormalizeNullVariablesToEmptyMap() {
        assertThat(new TriggerRequest(null, "SYNC").variables()).isEmpty();
    }

    @Test
    void shouldRejectIllegalModeWithReadableMessage() {
        assertThatThrownBy(() -> new TriggerRequest(null, "turbo").resolveMode())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("turbo")
                .hasMessageContaining("SYNC");
    }
}
