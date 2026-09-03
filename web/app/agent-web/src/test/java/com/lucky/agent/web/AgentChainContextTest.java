package com.lucky.agent.web;

import com.lucky.agent.core.config.CoreProperties;
import com.lucky.agent.core.models.Engine;
import com.lucky.agent.core.service.ConversationManager;
import com.lucky.agent.core.service.LoopMemoryManager;
import com.lucky.agent.core.service.Orchestrator;
import com.lucky.agent.core.verify.CommandVerifier;
import com.lucky.agent.core.verify.FileVerifier;
import com.lucky.agent.core.verify.LlmJudgeVerifier;
import com.lucky.agent.core.verify.VerificationChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核心执行链路装配冒烟测试。
 *
 * <p>目的：验证「客观验证器 / 记忆管理 / 安全阀 / 编排器」等新增与改造组件的
 * Spring 装配可正常启动，避免只在编译期通过、运行期缺 Bean 或循环依赖。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AgentChainContextTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Orchestrator orchestrator;

    @Autowired
    private VerificationChain verificationChain;

    @Autowired
    private LoopMemoryManager loopMemoryManager;

    @Test
    void testExecutionChainBeansPresent() {
        assertNotNull(orchestrator, "主回环编排器");
        assertNotNull(verificationChain, "客观验证责任链（D / I 节点）");
        assertNotNull(loopMemoryManager, "主回环记忆管理（K 节点）");
        assertNotNull(context.getBean(ConversationManager.class), "会话编排器（A 节点）");
        assertNotNull(context.getBean(Engine.class), "REACT 引擎（F 节点）");
        assertNotNull(context.getBean(FileVerifier.class), "文件客观验证器");
        assertNotNull(context.getBean(CommandVerifier.class), "命令客观验证器");
        assertNotNull(context.getBean(LlmJudgeVerifier.class), "主观判定验证器");
    }

    @Test
    void testSafetyValveAndVerificationPropertiesBound() {
        CoreProperties properties = context.getBean(CoreProperties.class);

        assertTrue(properties.orchestratorMaxIterations() > 0, "安全阀：主回环最大迭代次数");
        assertTrue(properties.orchestratorMaxRetries() >= 0, "安全阀：子任务局部重试上限");
        assertTrue(properties.runMaxTurns() > 0, "安全阀：回合上限（N 节点）");
        assertTrue(properties.actMaxSteps() > 0, "安全阀：ACT 阶段步数上限");
        assertNotNull(properties.verificationEnabled(), "客观验证总开关应绑定成功");
        assertTrue(properties.verificationTimeoutSec() > 0, "客观验证超时");
    }
}
