package com.lucky.agent.web.account;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.WorkspaceDirs;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * 本机本地身份（�?.1）：�?{@code <frameworkRoot>/.config/account.json}，无密码库、无中心注册�? */

@Slf4j
@Service
public class LocalAccountService {

    
    private static final String FILE_NAME = "account.json";

    private final Path accountFile;
    private final ObjectMapper objectMapper;
    private volatile Account account;

    public LocalAccountService(WorkspaceDirs dirs, ObjectMapper objectMapper) {
        this.accountFile = dirs.configDir().resolve(FILE_NAME);
        this.objectMapper = objectMapper;
        load();
    }

    /** 当前本机账号�?*/
    public Account current() {
        return account;
    }

    /** 更新显示名�?*/
    public synchronized Account updateDisplayName(String displayName) {
        account = new Account(account.userId(), displayName);
        save();
        return account;
    }

    private void load() {
        if (Files.exists(accountFile)) {
            try {
                String json = Files.readString(accountFile, StandardCharsets.UTF_8);
                account = objectMapper.readValue(json, Account.class);
                return;
            } catch (Exception e) {
                log.warn("读取本机账号失败：{}", accountFile, e);
            }
        }
        account = new Account(UUID.randomUUID().toString(), "本机用户");
        save();
    }

    private void save() {
        try {
            Files.createDirectories(accountFile.getParent());
            Files.write(accountFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(account));
        } catch (Exception e) {
            log.error("保存本机账号失败：{}", accountFile, e);
        }
    }

    /**
     * 本机账号（仅 userId + displayName）�?     *
     * @param userId      用户 ID
     * @param displayName 显示�?     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Account(String userId, String displayName) {
    }
}
