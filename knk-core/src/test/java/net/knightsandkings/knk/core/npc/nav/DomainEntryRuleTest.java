package net.knightsandkings.knk.core.npc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.regions.DomainAccessEvaluator;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import net.knightsandkings.knk.core.roads.route.DomainAvailability.RoadRule;

/**
 * PandiO/knk-workspace#701: the NPC walking checks' rule per region knows exits as well as entries, from the road
 * router's own rule ({@code DomainAvailability}) and the shared {@link DomainAccessEvaluator}, with the NPC's access
 * profile (bypass, allow and deny lists) and the road rule applied as for entries.
 */
class DomainEntryRuleTest {

    private static final String WORLD = "world";
    private static final String TOWN = "town_1";
    private static final String KEEP = "structure_keep";
    private static final String MARKET = "district_market";

    private final Map<String, DomainSnapshot> domains = new HashMap<>();

    private static DomainSnapshot domain(String region, Boolean allowEntry, Boolean allowExit, String type) {
        return new DomainSnapshot(region.hashCode(), region, "", region, allowEntry, allowExit, type, Set.of(), Set.of(),
            Set.of(), Set.of());
    }

    private RegionRule rule(NpcAccessProfile profile, Set<String> here, RoadRule roadRule) {
        return DomainEntryRule.of(new NpcAccessSubject("npc-1", profile, WORLD, here), new DomainAccessEvaluator(),
            (world, region) -> Optional.ofNullable(domains.get(region)), roadRule);
    }

    private RegionRule rule(Set<String> here) {
        return rule(NpcAccessProfile.DEFAULT, here, RoadRule.ALWAYS);
    }

    @Test
    void aRegionWhoseDomainDeniesLeavingMayNotBeLeftByAnNpcInIt() {
        domains.put(TOWN, domain(TOWN, true, true, "Town"));
        domains.put(KEEP, domain(KEEP, true, false, "Structure"));

        RegionRule rule = rule(Set.of(TOWN, KEEP));

        assertFalse(rule.mayLeave(KEEP));
        assertTrue(rule.mayLeave(TOWN));
        assertEquals(Set.of(KEEP), rule.exitDenied());
        assertTrue(rule.test(KEEP), "a region it stands in is not entered");
    }

    @Test
    void everyDomainTypeIsAskedForItsExitRule() {
        for (String type : List.of("Town", "District", "Structure", "GateStructure", "DomainSubRegion")) {
            domains.put(MARKET, domain(MARKET, true, false, type));
            assertFalse(rule(Set.of(MARKET)).mayLeave(MARKET), type);
        }
    }

    @Test
    void aRegionItDoesNotStandInMayAlwaysBeLeft() {
        domains.put(KEEP, domain(KEEP, true, false, "Structure"));

        RegionRule rule = rule(Set.of(TOWN));

        assertTrue(rule.mayLeave(KEEP));
        assertTrue(rule.exitDenied().isEmpty());
    }

    @Test
    void unknownDomainsAndUnsetFlagsAreOpen() {
        domains.put(MARKET, domain(MARKET, null, null, "District"));

        RegionRule rule = rule(Set.of(TOWN, MARKET));

        assertTrue(rule.exitDenied().isEmpty(), "a region of unknown domain and a null flag are open, as for entry");
    }

    @Test
    void theProfilesBypassIgnoresExitRules() {
        domains.put(KEEP, domain(KEEP, false, false, "Structure"));
        NpcAccessProfile bypass = new NpcAccessProfile("guard", true, true, Set.of(), Set.of());

        RegionRule rule = rule(bypass, Set.of(KEEP), RoadRule.ALWAYS);

        assertTrue(rule.mayLeave(KEEP));
        assertTrue(rule.exitDenied().isEmpty());
    }

    @Test
    void theProfilesAllowListLetsItLeaveThatRegion() {
        domains.put(KEEP, domain(KEEP, true, false, "Structure"));
        domains.put(MARKET, domain(MARKET, true, false, "District"));
        NpcAccessProfile allowKeep = new NpcAccessProfile("keeper", false, true, Set.of(KEEP), Set.of());

        RegionRule rule = rule(allowKeep, Set.of(KEEP, MARKET), RoadRule.ALWAYS);

        assertTrue(rule.mayLeave(KEEP));
        assertFalse(rule.mayLeave(MARKET), "the allow list names only the keep");
    }

    @Test
    void aDomainWhoseRuleDoesNotApplyToRoadsIsSkippedForExitsAsForEntries() {
        domains.put(KEEP, domain(KEEP, false, false, "Structure"));

        RegionRule rule = rule(NpcAccessProfile.DEFAULT, Set.of(KEEP), domain -> !"Structure".equals(domain.domainType()));

        assertTrue(rule.mayLeave(KEEP));
    }

    @Test
    void aStepIsDeniedForARegionItEntersFirstThenForOneItLeaves() {
        domains.put(KEEP, domain(KEEP, true, false, "Structure"));
        domains.put(MARKET, domain(MARKET, false, true, "District"));
        Set<String> here = Set.of(TOWN, KEEP);
        RegionRule rule = rule(here);

        assertNull(rule.deniedStep(here, Set.of(TOWN, KEEP)), "staying inside");
        assertEquals(new RegionRule.Denied(KEEP, true), rule.deniedStep(here, Set.of(TOWN)));
        assertEquals(new RegionRule.Denied(MARKET, false), rule.deniedStep(here, Set.of(TOWN, MARKET)));
        assertEquals("exit " + KEEP, rule.deniedStep(here, Set.of(TOWN)).key());
        assertEquals("left " + KEEP, rule.deniedStep(here, Set.of(TOWN)).verb());
    }

    @Test
    void stepCheckAsksForTheRuleOnlyWhenAStepCrossesARegionBorder() {
        domains.put(KEEP, domain(KEEP, true, false, "Structure"));
        Set<String> here = Set.of(TOWN, KEEP);
        AtomicInteger asks = new AtomicInteger();
        StepCheck steps = new StepCheck(here, () -> {
            asks.incrementAndGet();
            return rule(here);
        });

        assertTrue(steps.allows(Set.of(KEEP, TOWN)));
        assertEquals(0, asks.get(), "no border crossed, no rule asked");
        assertFalse(steps.allows(Set.of(TOWN)));
        assertEquals(new RegionRule.Denied(KEEP, true), steps.denied(Set.of()));
        assertEquals(1, asks.get(), "asked once for the check's life");
    }

    @Test
    void anEntryRuleWithoutExitsAllowsEveryExit() {
        Predicate<String> entryOnly = region -> !MARKET.equals(region);

        RegionRule rule = RegionRule.of(entryOnly);

        assertTrue(rule.mayLeave(KEEP));
        assertFalse(rule.test(MARKET));
        assertNull(rule.deniedStep(Set.of(KEEP), Set.of()));
        RegionRule domainRule = rule(Set.of(TOWN));
        assertSame(domainRule, RegionRule.of(domainRule), "a region rule is kept as it is");
    }

    @Test
    void accessRulesHandOutTheExitsOfTheirEntryRule() {
        domains.put(KEEP, domain(KEEP, true, false, "Structure"));
        AccessRules rules = new AccessRules() {
            @Override
            public Predicate<String> mayEnter(AccessSubject subject) {
                return DomainEntryRule.of(subject, new DomainAccessEvaluator(),
                    (world, region) -> Optional.ofNullable(domains.get(region)), RoadRule.ALWAYS);
            }

            @Override
            public boolean gateBlocks(AccessSubject subject, int doorId) {
                return false;
            }
        };

        RegionRule rule = rules.regionRule(new NpcAccessSubject("npc-1", NpcAccessProfile.DEFAULT, WORLD, Set.of(KEEP)));

        assertFalse(rule.mayLeave(KEEP));
        assertTrue(AccessRules.OPEN.regionRule(new NpcAccessSubject("npc-1", NpcAccessProfile.DEFAULT, WORLD,
            Set.of(KEEP))).mayLeave(KEEP));
    }
}
