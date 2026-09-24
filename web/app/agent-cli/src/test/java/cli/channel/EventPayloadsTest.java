package cli.channel;

import com.lucky.agent.cli.channel.EventPayloads;
import com.lucky.agent.common.dto.AgentEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code options} payload 归一化：同进程是记录、过 JSON 后是 Map，两种形状都要认。
 *
 * <p>这是「两种形状」缺陷的单一收口点 —— 若哪天内核把 {@code OptionItem} 换成别的载体，
 * 只需改这里一处，而不是让每个消费端各自 {@code instanceof} 一遍。</p>
 */
class EventPayloadsTest {

    @Test
    @DisplayName("内核记录形状：OptionItem 被归一化为统一 Map 键")
    void normalizesKernelRecord() {
        List<Map<String, Object>> out = EventPayloads.optionList(List.of(
                new AgentEvent.OptionItem("1", "方案甲", "最快", true),
                new AgentEvent.OptionItem("2", "方案乙", "最稳", false)));

        assertEquals(2, out.size());
        assertEquals("1", out.get(0).get("id"));
        assertEquals("方案甲", out.get(0).get("label"));
        assertEquals("最快", out.get(0).get("detail"));
        assertEquals(Boolean.TRUE, out.get(0).get("recommended"));
        assertEquals(Boolean.FALSE, out.get(1).get("recommended"));
    }

    @Test
    @DisplayName("JSON 形状：已经是 Map 的元素原样透传")
    void passesThroughMapShape() {
        List<Map<String, Object>> out = EventPayloads.optionList(
                List.of(Map.of("id", "1", "label", "甲")));

        assertEquals(1, out.size());
        assertEquals("甲", out.get(0).get("label"));
    }

    @Test
    @DisplayName("null 推荐位归一化为 false（而不是 null）")
    void nullRecommendedBecomesFalse() {
        List<Map<String, Object>> out =
                EventPayloads.optionList(List.of(new AgentEvent.OptionItem("1", "甲", null, null)));

        assertEquals(Boolean.FALSE, out.get(0).get("recommended"),
                "必须是 Boolean 而非 null，否则下游渲染会打印出 null");
    }

    @Test
    @DisplayName("混合形状与无法识别的元素：认得的收下，认不得的跳过，不抛异常")
    void toleratesMixedAndUnknown() {
        List<Map<String, Object>> out = EventPayloads.optionList(new java.util.ArrayList<>(java.util.Arrays.asList(
                new AgentEvent.OptionItem("1", "甲", null, false),
                Map.of("id", "2", "label", "乙"),
                "字符串不是选项",
                null,
                42)));

        assertEquals(2, out.size(), "只应保留两种可识别形状");
        assertEquals("甲", out.get(0).get("label"));
        assertEquals("乙", out.get(1).get("label"));
    }

    @Test
    @DisplayName("非列表输入返回空列表（不抛异常）")
    void nonListInputIsEmpty() {
        assertTrue(EventPayloads.optionList(null).isEmpty());
        assertTrue(EventPayloads.optionList("not-a-list").isEmpty());
        assertTrue(EventPayloads.optionList(Map.of("label", "x")).isEmpty());
        assertFalse(EventPayloads.optionList(List.of()).iterator().hasNext());
    }
}
