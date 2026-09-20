package com.lucky.agent.core.util.planactask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.common.util.JsonlUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * ASK 挂起器（D7）：挂起状态本地持久化（{@code .tmp/pending-ask.jsonl} + TTL），进程重启后可恢复�? */

@Slf4j
public class AskSuspender {

    
    private static final String FILE_NAME = "pending-ask.jsonl";
    private static final long TTL_MS = 30 * 60 * 1000L;

    private final Path pendingFile;
    private final ObjectMapper objectMapper;

    public AskSuspender(WorkspaceDirs dirs, ObjectMapper objectMapper) {
        this.pendingFile = dirs.tmpDir().resolve(FILE_NAME);
        this.objectMapper = objectMapper;
    }

    /**
     * 挂起一�?ASK�?     *
     * @param ref      会话
     * @param question 问题
     * @param risk     风险
     */
    public void suspend(SessionRef ref, String question, String risk) {
        Map<String, Object> record = new HashMap<>();
        record.put("sessionId", ref.sessionId());
        record.put("workspaceId", ref.workspaceId());
        record.put("userId", ref.userId());
        record.put("question", question);
        record.put("risk", risk);
        record.put("ts", System.currentTimeMillis());
        JsonlUtil.append(pendingFile, record);
        log.info("ASK 挂起：session={} question={}", ref.sessionId(), question);
    }

    /** 查询会话最近的挂起 ASK（已过期视为无）�?*/
    public Optional<Map<String, Object>> pending(String sessionId) {
        List<Map<String, Object>> records = JsonlUtil.readAll(pendingFile,
                line -> JsonlUtil.parseLine(line, Map.class));
        for (int i = records.size() - 1; i >= 0; i--) {
            Map<String, Object> record = records.get(i);
            if (sessionId.equals(record.get("sessionId"))) {
                long ts = ((Number) record.getOrDefault("ts", 0L)).longValue();
                return System.currentTimeMillis() - ts <= TTL_MS ? Optional.of(record) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** 清除会话挂起�?*/
    public void clear(String sessionId) {
        try {
            List<Map<String, Object>> records = JsonlUtil.readAll(pendingFile,
                    line -> JsonlUtil.parseLine(line, Map.class));
            records.removeIf(r -> sessionId.equals(r.get("sessionId")));
            Path tmp = pendingFile.resolveSibling(pendingFile.getFileName() + ".tmp");
            Files.createDirectories(pendingFile.getParent());
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> record : records) {
                sb.append(objectMapper.writeValueAsString(record)).append(System.lineSeparator());
            }
            Files.writeString(tmp, sb.toString());
            Files.move(tmp, pendingFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.warn("清除挂起 ASK 失败：{}", sessionId, e);
        }
    }
}
