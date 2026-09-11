package permission.support.rules;

import com.lucky.agent.common.constant.PermissionDecision;
import com.lucky.agent.common.contract.PermissionRule;
import com.lucky.agent.permission.support.rules.PermissionChain;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * deny &gt; ask &gt; allow 规则链单元测试。
 */
class PermissionChainTest {

    private final PermissionChain chain = new PermissionChain();

    @Test
    void testDenyWins_OverAllow() {
        PermissionRule deny = new PermissionRule().id("r1").priority(10)
                .type(PermissionRule.RuleType.PATH)
                .matcher(new PermissionRule.Matcher("/**/secret/**", "project"))
                .action(PermissionRule.RuleAction.DENY);
        PermissionRule allow = new PermissionRule().id("r2").priority(20)
                .type(PermissionRule.RuleType.PATH)
                .matcher(new PermissionRule.Matcher("/**", "project"))
                .action(PermissionRule.RuleAction.ALLOW);

        PermissionDecision decision = chain.evaluatePath(
                Paths.get("C:/ws"), Paths.get("C:/ws/secret/token.txt"),
                List.of(allow, deny));
        assertEquals(PermissionDecision.DENY, decision);
    }

    @Test
    void testNoRuleHit_ReturnsDefer() {
        PermissionRule allow = new PermissionRule().id("r1").priority(10)
                .type(PermissionRule.RuleType.PATH)
                .matcher(new PermissionRule.Matcher("/docs/**", "project"))
                .action(PermissionRule.RuleAction.ALLOW);

        PermissionDecision decision = chain.evaluatePath(
                Paths.get("C:/ws"), Paths.get("C:/ws/other/file.txt"), List.of(allow));
        assertEquals(PermissionDecision.DEFER, decision);
    }

    @Test
    void testAskRule_Hits() {
        PermissionRule ask = new PermissionRule().id("r1").priority(10)
                .type(PermissionRule.RuleType.PATH)
                .matcher(new PermissionRule.Matcher("/delete/**", "project"))
                .action(PermissionRule.RuleAction.ASK);

        PermissionDecision decision = chain.evaluatePath(
                Paths.get("C:/ws"), Paths.get("C:/ws/delete/x"), List.of(ask));
        assertEquals(PermissionDecision.ASK, decision);
    }
}
