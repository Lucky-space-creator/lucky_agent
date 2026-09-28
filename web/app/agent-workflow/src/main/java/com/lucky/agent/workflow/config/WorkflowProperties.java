package com.lucky.agent.workflow.config;

import com.lucky.agent.workflow.domain.enums.RunMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工作流模块配置项（前缀 {@code lucky.workflow}）。
 *
 * <pre>
 * lucky:
 *   workflow:
 *     storage-dir: /path/to/workflows     # 为空则使用内存仓储
 *     sandbox:
 *       enabled: false                    # 命令沙箱默认关闭，需显式授权
 *       timeout-ms: 10000
 *       working-dir: /path/to/dir
 *       workspace-id: ws-xxx              # 命令归入的工作空间（留空回退内置默认工作空间）
 *     execution:
 *       thread-pool-size: 4
 *       default-mode: SYNC
 *       auto-triggers: true               # 是否启用周期等自动触发
 *       engine: legacy                    # legacy=自研调度；langgraph=LangGraph4j 状态图
 * </pre>
 */
@ConfigurationProperties(prefix = "lucky.workflow")
public class WorkflowProperties {

    private String storageDir;

    private Sandbox sandbox = new Sandbox();

    private Execution execution = new Execution();

    public String getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir;
    }

    public Sandbox getSandbox() {
        return sandbox;
    }

    public void setSandbox(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    public Execution getExecution() {
        return execution;
    }

    public void setExecution(Execution execution) {
        this.execution = execution;
    }

    public static class Sandbox {
        private boolean enabled = false;
        private long timeoutMs = 10_000L;
        private String workingDir;

        /**
         * 命令归入的工作空间 ID。
         *
         * <p><b>为什么需要它：</b>{@link com.lucky.agent.workflow.adapter.SandboxAdapter} 只接收
         * 命令文本，而执行臂（{@code FileService#exec}）必须按工作空间解析权限级别、realpath 根与
         * 快照位置。宿主用真实执行臂替换 {@code LocalSandboxAdapter} 后，若拿不到工作空间，
         * 命令就无法进入权限链与硬边界。留空时由宿主回退到内置默认工作空间。</p>
         */
        private String workspaceId;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public String getWorkingDir() {
            return workingDir;
        }

        public void setWorkingDir(String workingDir) {
            this.workingDir = workingDir;
        }

        public String getWorkspaceId() {
            return workspaceId;
        }

        public void setWorkspaceId(String workspaceId) {
            this.workspaceId = workspaceId;
        }
    }

    public static class Execution {
        private int threadPoolSize = 4;
        private RunMode defaultMode = RunMode.SYNC;
        private boolean autoTriggers = true;
        /** 引擎实现：{@code legacy}（默认，自研调度器）/ {@code langgraph}（LangGraph4j 状态图）。 */
        private String engine = "legacy";

        public String getEngine() {
            return engine;
        }

        public void setEngine(String engine) {
            this.engine = engine;
        }

        public int getThreadPoolSize() {
            return threadPoolSize;
        }

        public void setThreadPoolSize(int threadPoolSize) {
            this.threadPoolSize = threadPoolSize;
        }

        public RunMode getDefaultMode() {
            return defaultMode;
        }

        public void setDefaultMode(RunMode defaultMode) {
            this.defaultMode = defaultMode;
        }

        public boolean isAutoTriggers() {
            return autoTriggers;
        }

        public void setAutoTriggers(boolean autoTriggers) {
            this.autoTriggers = autoTriggers;
        }
    }
}
