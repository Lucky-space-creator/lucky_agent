package com.lucky.agent.model.support.usage;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.WorkspaceDirs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型用量统计（按端点累计，本机零托管）。
 *
 * <p>统计维度：模型调用次数、输入/输出 token、失败次数、最近一次使用时间。
 * 每次模型调用成功后由 {@code OpenAi/AnthropicCompatibleModel} 上报 tokenUsage；
 * 数据常驻内存（{@link ConcurrentHashMap}），并定期落盘到
 * {@code <frameworkRoot>/model-usage.json}（进程重启后仍可查看历史用量）。
 * 仅统计、不参与任何业务决策，与 D3「只放元数据不放用户消息」一致。</p>
 */
@Slf4j
@Component
public class ModelUsageTracker {

    private static final String USAGE_FILE = "model-usage.json";

    private final Path usageFile;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, ModelUsage> usages = new ConcurrentHashMap<>();

    public ModelUsageTracker(WorkspaceDirs dirs) {
        this.usageFile = dirs.frameworkRoot().resolve(USAGE_FILE);
        load();
    }

    /** 上报一次模型调用（成功或失败），input/output 可为 0（流式/失败时拿不到 usage）。 */
    public void record(String modelId, String modelName, long inputTokens, long outputTokens, boolean error) {
        if (modelId == null || modelId.isBlank()) {
            return;
        }
        ModelUsage u = usages.computeIfAbsent(modelId,
                k -> new ModelUsage(modelId, modelName));
        synchronized (u) {
            u.record(inputTokens, outputTokens, error);
        }
    }

    /** 按端点 id 读取用量（无记录返回 null）。 */
    public ModelUsage usage(String modelId) {
        return modelId == null ? null : usages.get(modelId);
    }

    /** 全量用量快照（返回副本，外部修改不影响内部状态）。 */
    public Map<String, ModelUsage> snapshot() {
        Map<String, ModelUsage> copy = new HashMap<>();
        usages.forEach((k, v) -> {
            synchronized (v) {
                copy.put(k, v.copy());
            }
        });
        return copy;
    }

    /** 定期落盘（默认每分钟）：调用有增量时写入，静默失败不阻断主流程。 */
    @Scheduled(fixedDelay = 60_000L)
    public void flush() {
        if (usages.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(usageFile.getParent());
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(snapshot());
            Files.write(usageFile, bytes);
        } catch (Exception e) {
            log.warn("模型用量落盘失败（不影响统计）：{}", usageFile, e.getMessage());
        }
    }

    /** 启动时加载历史用量（文件缺失/损坏忽略，从零开始）。 */
    private void load() {
        try {
            if (!Files.exists(usageFile)) {
                return;
            }
            String json = Files.readString(usageFile, StandardCharsets.UTF_8);
            Map<String, ModelUsage> loaded = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, ModelUsage.class));
            if (loaded != null) {
                usages.putAll(loaded);
                log.info("加载模型用量统计：{} 个端点", usages.size());
            }
        } catch (Exception e) {
            log.warn("加载模型用量失败（从零统计）：{}", usageFile, e.getMessage());
        }
    }

    /**
     * 单端点用量（线程安全：更新与读取均持有本对象锁）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ModelUsage {

        private String modelId;
        private String modelName;
        private long calls;
        private long inputTokens;
        private long outputTokens;
        private long errors;
        private long lastUsedAt;

        public ModelUsage() {
        }

        public ModelUsage(String modelId, String modelName) {
            this.modelId = modelId;
            this.modelName = modelName;
        }

        synchronized void record(long input, long output, boolean error) {
            calls++;
            inputTokens += Math.max(0, input);
            outputTokens += Math.max(0, output);
            if (error) {
                errors++;
            }
            lastUsedAt = System.currentTimeMillis();
        }

        ModelUsage copy() {
            ModelUsage c = new ModelUsage();
            c.modelId = this.modelId;
            c.modelName = this.modelName;
            c.calls = this.calls;
            c.inputTokens = this.inputTokens;
            c.outputTokens = this.outputTokens;
            c.errors = this.errors;
            c.lastUsedAt = this.lastUsedAt;
            return c;
        }

        @JsonProperty("modelId")
        public String modelId() {
            return modelId;
        }

        @JsonProperty("modelId")
        public void modelId(String modelId) {
            this.modelId = modelId;
        }

        @JsonProperty("modelName")
        public String modelName() {
            return modelName;
        }

        @JsonProperty("modelName")
        public void modelName(String modelName) {
            this.modelName = modelName;
        }

        @JsonProperty("calls")
        public long calls() {
            return calls;
        }

        @JsonProperty("calls")
        public void calls(long calls) {
            this.calls = calls;
        }

        @JsonProperty("inputTokens")
        public long inputTokens() {
            return inputTokens;
        }

        @JsonProperty("inputTokens")
        public void inputTokens(long inputTokens) {
            this.inputTokens = inputTokens;
        }

        @JsonProperty("outputTokens")
        public long outputTokens() {
            return outputTokens;
        }

        @JsonProperty("outputTokens")
        public void outputTokens(long outputTokens) {
            this.outputTokens = outputTokens;
        }

        @JsonProperty("errors")
        public long errors() {
            return errors;
        }

        @JsonProperty("errors")
        public void errors(long errors) {
            this.errors = errors;
        }

        @JsonProperty("lastUsedAt")
        public long lastUsedAt() {
            return lastUsedAt;
        }

        @JsonProperty("lastUsedAt")
        public void lastUsedAt(long lastUsedAt) {
            this.lastUsedAt = lastUsedAt;
        }
    }
}