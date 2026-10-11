package net.knightsandkings.knk.core.regions;

import net.knightsandkings.knk.core.regions.DomainAccessEvaluator.Denial;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for DomainAccessEvaluator - the entry/exit rules shared by the region tracker and
 * the road router. Messages must equal what SimpleRegionTransitionService showed before the
 * extraction (see SimpleRegionTransitionServiceTest).
 */
class DomainAccessEvaluatorTest {
    private final DomainAccessEvaluator evaluator = new DomainAccessEvaluator();

    @Test
    void entryIsDeniedOnlyWhenAllowEntryIsFalse() {
        assertTrue(evaluator.entry(domain("Cinix", true, true)).isEmpty());
        assertTrue(evaluator.entry(domain("Cinix", null, null)).isEmpty());

        Optional<Denial> denial = evaluator.entry(domain("Cinix", false, true));

        assertTrue(denial.isPresent());
        assertEquals(RegionTransitionType.ENTER, denial.get().type());
        assertEquals("You are not allowed to enter Cinix.", denial.get().message());
    }

    @Test
    void exitIsDeniedOnlyWhenAllowExitIsFalse() {
        assertTrue(evaluator.exit(domain("Cinix", true, true)).isEmpty());
        assertTrue(evaluator.exit(domain("Cinix", null, null)).isEmpty());

        Optional<Denial> denial = evaluator.exit(domain("Cinix", true, false));

        assertTrue(denial.isPresent());
        assertEquals(RegionTransitionType.EXIT, denial.get().type());
        assertEquals("You are not allowed to leave Cinix.", denial.get().message());
    }

    @Test
    void entryAndExitFlagsAreIndependent() {
        DomainSnapshot noEntry = domain("Keep", false, true);
        DomainSnapshot noExit = domain("Keep", true, false);

        assertTrue(evaluator.exit(noEntry).isEmpty());
        assertTrue(evaluator.entry(noExit).isEmpty());
    }

    @Test
    void theDenialCarriesTheDomainItIsAbout() {
        DomainSnapshot domain = domain("Kardenna Castle", false, false);

        assertSame(domain, evaluator.entry(domain).orElseThrow().domain());
        assertSame(domain, evaluator.exit(domain).orElseThrow().domain());
    }

    @Test
    void aNullDomainIsNeverDenied() {
        assertTrue(evaluator.entry(null).isEmpty());
        assertTrue(evaluator.exit(null).isEmpty());
    }

    @Test
    void theRuleDoesNotDependOnTheDomainType() {
        for (String type : new String[]{"Town", "District", "Structure", "gate"}) {
            DomainSnapshot domain = new DomainSnapshot(1, "X", "", "r", false, false, type,
                Set.of(), Set.of(), Set.of(), Set.of());
            assertTrue(evaluator.entry(domain).isPresent(), type);
            assertTrue(evaluator.exit(domain).isPresent(), type);
        }
    }

    private static DomainSnapshot domain(String name, Boolean allowEntry, Boolean allowExit) {
        return new DomainSnapshot(1, name, "", "region_" + name, allowEntry, allowExit, "Town",
            Set.of(), Set.of(), Set.of(), Set.of());
    }
}
