package com.lucky.agent.executor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.concurrent.FileLockGuard;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.executor.support.arm.BoundaryGuard;
import com.lucky.agent.executor.support.arm.CmdSandbox;
import com.lucky.agent.executor.support.arm.FileExecutor;
import com.lucky.agent.executor.support.arm.IntegrityChecker;
import com.lucky.agent.executor.support.arm.PermissionRuleCache;
import com.lucky.agent.executor.support.fileops.TypeDispatcher;
import com.lucky.agent.executor.support.rollback.CheckpointSnapshoter;
import com.lucky.agent.executor.support.rollback.Compensator;
import com.lucky.agent.executor.support.rollback.SnapshotIndex;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import lombok.extern.slf4j.Slf4j;

/**
 * agent-executor 配置：执行臂组件装配 + 启动清理 stale 锁。
 */

@Slf4j
@Configuration
@EnableConfigurationProperties(ExecutorProperties.class)
public class ExecutorConfig {

    
    private final WorkspaceDirs dirs;
    private final ExecutorProperties properties;

    public ExecutorConfig(WorkspaceDirs dirs, ExecutorProperties properties) {
        this.dirs = dirs;
        this.properties = properties;
    }

    /** 启动清理：扫描 .tmp/，删 PID 已死/超时 stale 锁与残留 .part 文件。*/
    @PostConstruct
    public void cleanupStaleLocksOnStartup() {
        try {
            FileLockGuard.of(dirs).cleanupStaleLocks();
        } catch (Exception e) {
            log.warn("启动清理 stale 锁失败", e);
        }
    }

    @Bean
    public BoundaryGuard boundaryGuard() {
        return new BoundaryGuard();
    }

    @Bean
    public FileLockGuard executorFileLockGuard() {
        return FileLockGuard.of(dirs);
    }

    @Bean
    public FileExecutor fileExecutor(FileLockGuard executorFileLockGuard) {
        return new FileExecutor(executorFileLockGuard, dirs);
    }

    @Bean
    public CmdSandbox cmdSandbox(ExecutorProperties properties) {
        return new CmdSandbox(properties.sandboxEnabled(), properties.cmdTimeoutSec());
    }

    @Bean
    public IntegrityChecker integrityChecker(ExecutorProperties properties) {
        return new IntegrityChecker(properties.integrityStrict());
    }

    @Bean
    public PermissionRuleCache permissionRuleCache() {
        return new PermissionRuleCache();
    }

    @Bean
    public TypeDispatcher typeDispatcher() {
        return new TypeDispatcher();
    }

    @Bean
    public SnapshotIndex snapshotIndex(ObjectMapper objectMapper) {
        return new SnapshotIndex(objectMapper);
    }

    @Bean
    public CheckpointSnapshoter checkpointSnapshoter(SnapshotIndex snapshotIndex) {
        return new CheckpointSnapshoter(dirs, snapshotIndex);
    }

    @Bean
    public Compensator compensator() {
        return new Compensator(dirs);
    }
}
