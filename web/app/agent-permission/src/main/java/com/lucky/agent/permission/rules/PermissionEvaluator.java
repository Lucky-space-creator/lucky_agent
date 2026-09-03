package com.lucky.agent.permission.rules;

import com.lucky.agent.common.constant.PermissionDecision;
import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.contract.PermissionRule;
import com.lucky.agent.common.dto.FileOp;
import com.lucky.agent.permission.guard.CommandSplitter;
import com.lucky.agent.permission.guard.DangerousOpDetector;
import com.lucky.agent.permission.guard.OsSandbox;
import com.lucky.agent.permission.guard.PermissionCircuitBreaker;
import com.lucky.agent.permission.guard.PermissionOverrideStore;
import com.lucky.agent.permission.guard.SymlinkResolver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 权限裁决核心：deny &gt; ask &gt; allow 规则链 + 工作区级别 + 危险操作识别 + 用户一次性放行。
 *
 * <p>框架侧（PermissionService）与执行臂（LocalTransport）共用本裁决器；
 * 执行臂以本地规则副本（{@code PermissionRuleCache}）为唯一生效源，实现「执行臂为最终裁决者」。</p>
 */
public class PermissionEvaluator {

    private final PermissionChain chain;
    private final DangerousOpDetector dangerousOpDetector;
    private final PermissionOverrideStore overrideStore;
    private final SymlinkResolver symlinkResolver;
    private final OsSandbox osSandbox;
    private final PermissionCircuitBreaker breaker;

    public PermissionEvaluator(PermissionChain chain, DangerousOpDetector dangerousOpDetector,
                               PermissionOverrideStore overrideStore) {
        this(chain, dangerousOpDetector, overrideStore,
                new SymlinkResolver(), new OsSandbox(), new PermissionCircuitBreaker(5));
    }

    public PermissionEvaluator(PermissionChain chain, DangerousOpDetector dangerousOpDetector,
                               PermissionOverrideStore overrideStore, SymlinkResolver symlinkResolver,
                               OsSandbox osSandbox, PermissionCircuitBreaker breaker) {
        this.chain = chain;
        this.dangerousOpDetector = dangerousOpDetector;
        this.overrideStore = overrideStore;
        this.symlinkResolver = symlinkResolver;
        this.osSandbox = osSandbox;
        this.breaker = breaker;
    }

    /**
     * 裁决一次文件操作。
     *
     * @param op            文件操作
     * @param level         工作区权限级别
     * @param workspaceRoot 工作区根
     * @param rules         规则集（执行臂传本地副本）
     * @return 裁决结果
     */
    public PermissionDecision evaluate(FileOp op, PermissionLevel level, Path workspaceRoot, List<PermissionRule> rules) {
        Path absPath = workspaceRoot.resolve(op.path() == null ? "" : op.path()).normalize();

        // 0) 断路器打开 → 直接拒绝（防提示注入循环）
        if (breaker.isOpen()) {
            return PermissionDecision.DENY;
        }

        // 0.5) symlink 双路径：真实路径越界即判越界
        Path realPath = symlinkResolver.resolveReal(absPath);
        if (!symlinkResolver.within(workspaceRoot, absPath) || !symlinkResolver.within(workspaceRoot, realPath)) {
            breaker.onDenied();
            return PermissionDecision.DENY;
        }

        // 1) 规则链：deny 永远胜出
        PermissionDecision ruleDecision = chain.evaluatePath(workspaceRoot, absPath, rules);
        if (ruleDecision == PermissionDecision.DENY || ruleDecision == PermissionDecision.ASK) {
            if (ruleDecision == PermissionDecision.DENY) {
                breaker.onDenied();
            }
            return ruleDecision;
        }

        // 2) 工作区级别约束（体验层引导）
        PermissionDecision levelDecision = evaluateByLevel(op, level);
        if (levelDecision == PermissionDecision.DENY) {
            breaker.onDenied();
            return PermissionDecision.DENY;
        }

        // 3) 危险操作：全部权限（用户已选自动执行）时放行，不再逐次 ASK；
        //    仍受 deny 规则链、symlink 越界与执行臂硬边界兜底，不作为越权通道。
        //    非全部权限级别（只读/修改）下危险写操作保持 ASK 或经一次性放行执行。
        if (dangerousOpDetector.isDangerous(op)) {
            if (level == PermissionLevel.FULL) {
                breaker.onAllowed();
                return PermissionDecision.ALLOW;
            }
            return overrideStore.check(op) ? PermissionDecision.ALLOW : PermissionDecision.ASK;
        }

        breaker.onAllowed();
        return PermissionDecision.ALLOW;
    }

    /** 裁决一次命令执行（复合命令逐条拆分 + OS 沙箱 + 断路器）。 */
    public PermissionDecision evaluateCommand(String command, PermissionLevel level, List<PermissionRule> rules) {
        if (breaker.isOpen()) {
            return PermissionDecision.DENY;
        }
        List<String> parts = new CommandSplitter().split(command);
        if (parts.isEmpty()) {
            return PermissionDecision.DENY;
        }
        for (String part : parts) {
            // OS 沙箱红线：任何一段触碰即整体拒绝
            if (osSandbox.violates(part)) {
                breaker.onDenied();
                return PermissionDecision.DENY;
            }
            PermissionDecision ruleDecision = chain.evaluateCommand(part, rules);
            if (ruleDecision == PermissionDecision.DENY) {
                breaker.onDenied();
                return PermissionDecision.DENY;
            }
            if (ruleDecision == PermissionDecision.ASK) {
                return PermissionDecision.ASK;
            }
        }
        if (level != PermissionLevel.FULL) {
            breaker.onDenied();
            return PermissionDecision.DENY;
        }
        breaker.onAllowed();
        return PermissionDecision.ASK;
    }

    private PermissionDecision evaluateByLevel(FileOp op, PermissionLevel level) {
        boolean write = switch (op.opType()) {
            case WRITE, DELETE, RENAME, MKDIR, EXEC -> true;
            case READ, LIST, STAT -> false;
        };
        switch (level) {
            case READ_ONLY -> {
                if (write) {
                    return PermissionDecision.DENY;
                }
            }
            case MODIFY -> {
                if (op.opType() == FileOp.OpType.EXEC) {
                    return PermissionDecision.DENY;
                }
            }
            case FULL -> {
                // 全部权限：命令执行仍走规则链与执行臂硬边界
            }
        }
        return PermissionDecision.ALLOW;
    }
}
