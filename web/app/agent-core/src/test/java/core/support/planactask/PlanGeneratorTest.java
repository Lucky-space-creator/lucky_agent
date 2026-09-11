package core.support.planactask;

import com.lucky.agent.core.models.Plan;
import com.lucky.agent.core.util.planactask.PlanGenerator;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划解析测试：含客观校验声明（verify）的解析、回退与非法声明丢弃。
 */
class PlanGeneratorTest {

    private final PlanGenerator generator = new PlanGenerator();

    @Test
    void testParsePlanWithVerifySpec() {
        String text = """
                先规划一下：
                {"goal":"新增健康检查接口",
                 "steps":[
                   {"id":1,"type":"file","desc":"新增 HealthController","target":"src/HealthController.java",
                    "safe":true,"verify":{"type":"file","path":"src/HealthController.java","contains":"@RestController"}},
                   {"id":2,"type":"shell","desc":"跑构建","target":null,"safe":true,
                    "verify":{"type":"command","command":"mvn -q -DskipTests package"}}
                 ],
                 "canAutoExecute":true}
                结尾说明
                """;

        Optional<Plan> plan = generator.parse(text);

        assertTrue(plan.isPresent());
        assertEquals("新增健康检查接口", plan.get().goal());
        assertEquals(2, plan.get().steps().size());

        Plan.VerifySpec v1 = plan.get().steps().get(0).verify();
        assertNotNull(v1);
        assertEquals("file", v1.type());
        assertEquals("src/HealthController.java", v1.path());
        assertEquals("@RestController", v1.contains());

        Plan.VerifySpec v2 = plan.get().steps().get(1).verify();
        assertNotNull(v2);
        assertEquals("command", v2.type());
        assertEquals("mvn -q -DskipTests package", v2.command());
    }

    @Test
    void testVerifyPathFallsBackToTarget() {
        String text = """
                {"goal":"写配置","steps":[{"id":1,"type":"file","desc":"写 yml","target":"app.yml",
                 "safe":true,"verify":{"type":"file"}}],"canAutoExecute":true}
                """;

        Optional<Plan> plan = generator.parse(text);

        assertTrue(plan.isPresent());
        assertEquals("app.yml", plan.get().steps().get(0).verify().path(), "未显式给 path 时应回退 target");
    }

    @Test
    void testIllegalVerifySpecDropped() {
        String text = """
                {"goal":"写配置","steps":[
                  {"id":1,"type":"file","desc":"写 yml","target":"app.yml","safe":true,
                   "verify":{"type":"unknown"}},
                  {"id":2,"type":"file","desc":"无校验","target":"b.yml","safe":true}
                ],"canAutoExecute":true}
                """;

        Optional<Plan> plan = generator.parse(text);

        assertTrue(plan.isPresent());
        assertNull(plan.get().steps().get(0).verify(), "非法校验类型应被丢弃");
        assertNull(plan.get().steps().get(1).verify(), "未声明 verify 时应为 null");
    }

    @Test
    void testParseFailureReturnsEmpty() {
        assertTrue(generator.parse(null).isEmpty());
        assertTrue(generator.parse("   ").isEmpty());
        assertTrue(generator.parse("这里没有 JSON").isEmpty());
        assertTrue(generator.parse("{\"goal\":\"x\"}").isEmpty(), "缺少 steps 应解析为空");
    }
}
