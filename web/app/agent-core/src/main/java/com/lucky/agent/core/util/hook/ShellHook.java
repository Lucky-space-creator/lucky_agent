package com.lucky.agent.core.util.hook;

import com.lucky.agent.common.dto.HookEvent;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Shell 外部 Hook：将事件 JSON 写入命令 stdin，解析 stdout 中的裁决 JSON。
 * <p>命令在当前 JVM 所在系统的原生 shell 中执行（Windows 用 cmd，其余用 /bin/sh）；
 * 超时/进程异常一律按放行处理，避免外部脚本故障阻断主流程。</p>
 */
@Slf4j
public class ShellHook extends ExternalHook {

    public ShellHook(ExternalHookConfig config) {
        super(config);
    }

    @Override
    protected HookEvent invoke(HookEvent event) {
        String command = config.command();
        if (command == null || command.isBlank()) {
            log.warn("Shell Hook 未配置 command：{}", config.name());
            return event;
        }
        Process process = null;
        try {
            String[] cmdLine = isWindows()
                    ? new String[]{"cmd.exe", "/c", command}
                    : new String[]{"/bin/sh", "-c", command};
            ProcessBuilder builder = new ProcessBuilder(cmdLine).redirectErrorStream(true);
            process = builder.start();

            String eventJson = OBJECT_MAPPER.writeValueAsString(event);
            OutputStream stdin = process.getOutputStream();
            stdin.write(eventJson.getBytes(StandardCharsets.UTF_8));
            stdin.close();

            boolean finished = process.waitFor(config.timeoutSec(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Shell Hook 执行超时（按放行处理）：{}", config.name());
                return event;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return applyDecision(event, output);
        } catch (IOException e) {
            log.warn("Shell Hook 执行失败（按放行处理）：{}", config.name(), e);
            return event;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Shell Hook 被中断（按放行处理）：{}", config.name(), e);
            return event;
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    private boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }
}
