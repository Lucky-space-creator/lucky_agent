package com.lucky.agent.permission.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.permission.api.dto.AuditMeta;

import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;

/**
 * 操作审计日志（仅元数据，不含文件内容，隐私）�? *
 * <p>审计行结�?{@code {ts, user, wid, op, meta}}，落�?{@code <frameworkRoot>/.logs/audit.jsonl}（框架隐藏目录）�?/p>
 */

@Slf4j
public class AuditLogger {

    
    private final Path auditFile;
    private final ObjectMapper objectMapper;

    public AuditLogger(Path logsDir, ObjectMapper objectMapper) {
        this.auditFile = logsDir.resolve("audit.jsonl");
        this.objectMapper = objectMapper;
    }

    /**
     * 记录一次操作审计（仅元数据）�?     *
     * @param meta 审计元数�?     */
    public void record(AuditMeta meta) {
        try {
            java.nio.file.Files.createDirectories(auditFile.getParent());
            String line = objectMapper.writeValueAsString(meta);
            java.nio.file.Files.writeString(auditFile, line + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("写入审计日志失败：{}", auditFile, e);
        }
    }

    public Path auditFile() {
        return auditFile;
    }
}
