package com.lucky.agent.core.util.engine;

import com.lucky.agent.common.dto.AgentEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OptionsBlockParser} 单元测试。
 *
 * <p>覆盖：标准围栏解析、宽松围栏、裸 JSON 兜底、选项块剥离、字段默认值与校验、
 * 推荐项优先选择、非法输入退化为「非选项正文」。</p>
 */
class OptionsBlockParserTest {

    @Test
    @DisplayName("标准 ```options 围栏：解析出问题、选项并剥离块")
    void parseStandardFence() {
        String text = """
                我建议按以下方案推进：
                ```options
                {"question":"选择实现方案","options":[
                  {"id":"1","label":"方案A：重构","detail":"改动较大"},
                  {"id":"2","label":"方案B：打补丁","recommended":true}
                ],"allowCustom":true,"customHint":"其他方案"}
                ```
                请确认。""";

        Optional<OptionsBlockParser.Parsed> parsed = OptionsBlockParser.parse(text);

        assertThat(parsed).isPresent();
        OptionsBlockParser.Parsed p = parsed.get();
        assertThat(p.question()).isEqualTo("选择实现方案");
        assertThat(p.options()).hasSize(2);
        assertThat(p.options().get(1).label()).isEqualTo("方案B：打补丁");
        assertThat(p.options().get(1).recommended()).isTrue();
        assertThat(p.allowCustom()).isTrue();
        assertThat(p.customHint()).isEqualTo("其他方案");
        // 选项块须从正文剥离，用户不应看到原始 JSON
        assertThat(p.strippedBody()).doesNotContain("question").doesNotContain("```");
        assertThat(p.strippedBody()).contains("我建议按以下方案推进");
    }

    @Test
    @DisplayName("宽松围栏：```json options 与 ```json 均可解析")
    void parseLooseFences() {
        String jsonOptions = """
                ```json options
                {"question":"Q","options":[{"label":"x"}]}
                ```""";
        String plainJson = """
                ```json
                {"question":"Q","options":[{"label":"x"}]}
                ```""";

        assertThat(OptionsBlockParser.parse(jsonOptions)).isPresent();
        assertThat(OptionsBlockParser.parse(plainJson)).isPresent();
    }

    @Test
    @DisplayName("无围栏的裸 JSON 对象也能兜底解析")
    void parseRawJsonFallback() {
        String text = "要不要继续？ {\"question\":\"继续吗\",\"options\":[{\"id\":\"y\",\"label\":\"继续\"}]}";
        Optional<OptionsBlockParser.Parsed> parsed = OptionsBlockParser.parse(text);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().question()).isEqualTo("继续吗");
        assertThat(parsed.get().strippedBody()).doesNotContain("\"question\"");
    }

    @Test
    @DisplayName("缺 id 时按序号补全；blank label 的项被丢弃")
    void idFallbackAndBlankLabelDropped() {
        String text = """
                ```options
                {"question":"Q","options":[
                  {"label":"第一项"},
                  {"label":"  "},
                  {"label":"第三项"}
                ]}
                ```""";

        OptionsBlockParser.Parsed p = OptionsBlockParser.parse(text).orElseThrow();

        assertThat(p.options()).hasSize(2);
        assertThat(p.options().get(0).id()).isEqualTo("1");
        assertThat(p.options().get(1).id()).isEqualTo("3");
    }

    @Test
    @DisplayName("allowCustom 缺省为 true；无推荐项时 preferred 取第一项")
    void defaultsAndPreferred() {
        String text = """
                ```options
                {"question":"Q","options":[{"label":"甲"},{"label":"乙"}]}
                ```""";

        OptionsBlockParser.Parsed p = OptionsBlockParser.parse(text).orElseThrow();

        assertThat(p.allowCustom()).isTrue();
        assertThat(p.customHint()).isNull();
        assertThat(p.preferred().label()).isEqualTo("甲");
    }

    @Test
    @DisplayName("preferred：有 recommended 时优先选中该项")
    void preferredPicksRecommended() {
        String text = """
                ```options
                {"question":"Q","options":[
                  {"id":"a","label":"甲"},
                  {"id":"b","label":"乙","recommended":true}
                ]}
                ```""";

        OptionsBlockParser.Parsed p = OptionsBlockParser.parse(text).orElseThrow();

        assertThat(p.preferred().id()).isEqualTo("b");
    }

    @Test
    @DisplayName("非法输入退化为非选项正文（不抛异常、返回空）")
    void invalidInputsReturnEmpty() {
        assertThat(OptionsBlockParser.parse(null)).isEmpty();
        assertThat(OptionsBlockParser.parse("")).isEmpty();
        assertThat(OptionsBlockParser.parse("普通正文，无任何选项")).isEmpty();
        // options 为空数组视为无效
        assertThat(OptionsBlockParser.parse("```options\n{\"question\":\"Q\",\"options\":[]}\n```")).isEmpty();
        // 缺 question 视为无效
        assertThat(OptionsBlockParser.parse("```options\n{\"options\":[{\"label\":\"x\"}]}\n```")).isEmpty();
        // JSON 语法错误视为无效
        assertThat(OptionsBlockParser.parse("```options\n{not json}\n```")).isEmpty();
    }

    @Test
    @DisplayName("解析结果的选项为 AgentEvent.OptionItem 契约")
    void optionItemType() {
        String text = """
                ```options
                {"question":"Q","options":[{"id":"1","label":"甲","detail":"说明"}]}
                ```""";

        List<AgentEvent.OptionItem> items = OptionsBlockParser.parse(text).orElseThrow().options();
        assertThat(items).allMatch(o -> o instanceof AgentEvent.OptionItem);
        assertThat(items.get(0).detail()).isEqualTo("说明");
    }
}
