package com.lucky.agent.permission.repository.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * 审计元数据（仅元数据，不留存文件内容，隐私）。
 *
 * @param ts     操作时间
 * @param userId 操作者
 * @param wid    工作空间 ID
 * @param op     操作类型
 * @param meta   附加元数据（路径/大小/状态等，不含文件正文）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditMeta(String ts, String userId, String wid, String op, Map<String, Object> meta) {
}
