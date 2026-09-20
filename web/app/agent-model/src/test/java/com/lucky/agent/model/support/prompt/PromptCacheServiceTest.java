package com.lucky.agent.model.support.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PromptCacheService 缓存前缀指纹/覆盖率/继承 单元测试（D17 Phase2）。
 */
class PromptCacheServiceTest {

    private final PromptCacheService service = new PromptCacheService();

    @Test
    void testFingerprint_Stable() {
        String fp1 = service.fingerprint("STATIC-PREFIX-A");
        String fp2 = service.fingerprint("STATIC-PREFIX-A");
        String fp3 = service.fingerprint("STATIC-PREFIX-B");
        assertEquals(fp1, fp2, "相同前缀指纹应稳定");
        assertFalse(fp1.equals(fp3), "不同前缀指纹应不同");
    }

    @Test
    void testPlan_CoverageAndInherit() {
        SystemPromptAssembler assembler = new SystemPromptAssembler()
                .base("BASE").persona("PERSONA").phase("PHASE").permission("PERM")
                .memory("DYNAMIC-MEMORY-CONTEXT");
        PromptCachePlan plan = service.plan(assembler, true);
        assertTrue(plan.coverage() > 0, "覆盖率应大于 0");
        assertTrue(plan.inherit(), "子代理应继承父缓存前缀");
        assertTrue(plan.cacheable(), "静态主导应可缓存");
        assertEquals(service.fingerprint(assembler.staticPrefix()), plan.prefixFingerprint());
    }

    @Test
    void testPlan_NoInheritForRoot() {
        PromptCachePlan plan = service.plan("STATIC", "STATIC+DYNAMIC", false);
        assertFalse(plan.inherit());
        assertEquals("STATIC", plan.cacheablePrefix());
    }

    @Test
    void testSamePrefix() {
        String fp = service.fingerprint("X");
        assertTrue(service.samePrefix(fp, fp));
        assertFalse(service.samePrefix(fp, service.fingerprint("Y")));
    }
}
