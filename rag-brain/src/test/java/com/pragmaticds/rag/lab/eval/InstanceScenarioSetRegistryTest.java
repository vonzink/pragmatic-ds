package com.pragmaticds.rag.lab.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.lab.eval.InstanceScenarioSetRegistry.ScenarioSet;
import com.pragmaticds.rag.lab.eval.InstanceScenarioSetRegistry.ScenarioSetDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The published view of the scenario-set allowlist.
 *
 * <p>An authoring surface can only offer a set it can name by id, version and digest, and the
 * promotion gate later refuses any triple that does not resolve. These pin that what is offered is
 * loaded through the same path {@link InstanceScenarioSetRegistry#require} uses, so an author
 * cannot be handed a set the gate would then reject.
 */
class InstanceScenarioSetRegistryTest {

    private final InstanceScenarioSetRegistry registry =
            new InstanceScenarioSetRegistry(new ObjectMapper());

    @Test
    void everyShippedScenarioSetIsPublishedAtTheDigestRequireResolves() {
        List<ScenarioSetDescriptor> published = registry.list();

        assertFalse(published.isEmpty(), "this build ships at least one scenario set");
        for (ScenarioSetDescriptor offered : published) {
            ScenarioSet resolved = registry.require(offered.id(), offered.version());
            assertEquals(resolved.sha256(), offered.sha256(),
                    "an offered digest that require() does not reproduce is unpinnable");
            assertTrue(offered.sha256().matches("[0-9a-f]{64}"));
            assertTrue(offered.version() >= 1);
            // The count is the one thing about a set worth seeing before choosing it, and an
            // empty set would be a promotion gate that measures nothing.
            assertEquals(resolved.scenarios().size(), offered.scenarioCount());
            assertTrue(offered.scenarioCount() > 0);
        }
    }

    @Test
    void theOfferedOrderIsStableAndSortedById() {
        List<ScenarioSetDescriptor> published = registry.list();

        assertEquals(published.stream().map(ScenarioSetDescriptor::id).sorted().toList(),
                published.stream().map(ScenarioSetDescriptor::id).toList());
        // Reading twice must not reorder: the second call is served from the load cache.
        assertEquals(published, registry.list());
    }

    /**
     * Nothing outside the allowlist is offerable, and asking for one is a code, not a message.
     *
     * <p>The allowlist is the whole trust boundary: a scenario set decides whether a release may
     * go live, so a set an administrator could name into existence would make the gate
     * self-certifying.
     */
    @Test
    void aSetOutsideTheAllowlistIsNeverOfferedAndNeverResolves() {
        List<String> offered = registry.list().stream().map(ScenarioSetDescriptor::id).toList();
        assertFalse(offered.contains("not-shipped"));

        InstanceScenarioSetRegistry.ScenarioSetException refused = assertThrows(
                InstanceScenarioSetRegistry.ScenarioSetException.class,
                () -> registry.require("not-shipped", 1));
        assertEquals(InstanceScenarioSetRegistry.ScenarioSetException.Code
                .SCENARIO_SET_NOT_ALLOWLISTED, refused.code());

        // A version that was never shipped is refused the same way as an unknown id.
        assertThrows(InstanceScenarioSetRegistry.ScenarioSetException.class,
                () -> registry.require(offered.get(0), 999));
    }

    /**
     * Version 4 is the promotion gate for the calculation worksheet: a release that answers a
     * paystub must publish the engine-written computed block, which only exists when the model
     * declared the income-domain-v2 contract and the enricher ran.
     */
    @Test
    void incomeSmokeFourRequiresTheComputedBasisOnEveryPaystubCase() {
        ScenarioSet v4 = registry.require("income-smoke", 4);
        assertEquals(5, v4.scenarios().size());
        String basis = "/findings/domain/borrowers/0/sources/0/computed/basis";
        for (InstanceScenarioSetRegistry.Scenario scenario : v4.scenarios()) {
            boolean paystub = scenario.packageFixture().equals("fixture-paystub-single")
                    || scenario.packageFixture().equals("fixture-paystub-w2");
            assertEquals(paystub, scenario.requiredPointers().contains(basis), scenario.name());
        }
        assertTrue(InstanceAssertionDocument.addressable(basis));
    }

    /**
     * assets-smoke:1 proves an asset release ran the engine's ledger rules: the reconciliation
     * status exists only when the instance slug resolved assets-v2's rules. Every fixture it names
     * ships, so the gate cannot point at a parse nobody can load.
     */
    @Test
    void assetsSmokeOneRequiresTheEngineWrittenReconciliationOnTheBankStatement() {
        ScenarioSet set = registry.require("assets-smoke", 1);
        assertEquals(3, set.scenarios().size());
        InstancePackageFixtureRegistry fixtures = new InstancePackageFixtureRegistry();
        String reconciliation = "/findings/domain/derived/reconciliation/status";
        for (InstanceScenarioSetRegistry.Scenario scenario : set.scenarios()) {
            fixtures.require(scenario.packageFixture());
            boolean bank = scenario.packageFixture().equals("fixture-bank-statement-single");
            assertEquals(bank, scenario.requiredPointers().contains(reconciliation),
                    scenario.name());
            assertEquals(!bank, scenario.requiredPointers().contains("/reason"), scenario.name());
        }
        assertTrue(InstanceAssertionDocument.addressable(reconciliation));
    }

    /**
     * Version 5 is version 4 plus the subject key, which an evaluation can only produce because the
     * review-required scenario declares a synthetic scope of its own. Every other case stays
     * unscoped, and version 3 stays unallowlisted because it asserts the key with no scope at all.
     */
    @Test
    void incomeSmokeFiveAssertsTheSubjectKeyOnlyWhereItsScenarioDeclaresAScope() {
        ScenarioSet v4 = registry.require("income-smoke", 4);
        ScenarioSet v5 = registry.require("income-smoke", 5);
        assertEquals(v4.scenarios().size(), v5.scenarios().size());
        String subjectKey = "/findings/findings/0/subjectKey";
        for (int i = 0; i < v5.scenarios().size(); i++) {
            InstanceScenarioSetRegistry.Scenario five = v5.scenarios().get(i);
            InstanceScenarioSetRegistry.Scenario four = v4.scenarios().get(i);
            assertEquals(four.packageFixture(), five.packageFixture());
            assertTrue(five.requiredPointers().containsAll(four.requiredPointers()), five.name());
            boolean scoped = five.subjectScope() != null;
            assertEquals(scoped, five.requiredPointers().contains(subjectKey), five.name());
            assertEquals(scoped, five.packageFixture().equals("fixture-paystub-review-required"),
                    five.name());
        }
        assertThrows(InstanceScenarioSetRegistry.ScenarioSetException.class,
                () -> registry.require("income-smoke", 3));
    }
}
