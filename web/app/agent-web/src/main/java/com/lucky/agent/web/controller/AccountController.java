package com.lucky.agent.web.controller;

import com.lucky.agent.web.service.LocalAccountService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 本机账号/个人中心接口（§5.1）。
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final LocalAccountService accountService;

    public AccountController(LocalAccountService accountService) {
        this.accountService = accountService;
    }

    /** 当前本机账号。 */
    @GetMapping
    public LocalAccountService.Account current() {
        return accountService.current();
    }

    /** 更新显示名。 */
    @PutMapping
    public LocalAccountService.Account update(@RequestBody Map<String, String> body) {
        return accountService.updateDisplayName(body.get("displayName"));
    }
}
