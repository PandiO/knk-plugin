package net.knightsandkings.knk.core.regions.access;

import net.knightsandkings.knk.core.regions.RegionTransitionType;
import net.knightsandkings.knk.core.regions.access.RegionAccessRules.AccessState;
import net.knightsandkings.knk.core.regions.access.RegionAccessRules.Refusal;
import net.knightsandkings.knk.core.regions.access.RegionAccessRules.RegionView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KNG-56: the AllowEntry/AllowExit rule on WorldGuard regions. */
class RegionAccessRulesTest {

    private final RegionAccessRules rules = new RegionAccessRules();

    private record Region(String regionId, String displayName, AccessState entry, AccessState exit, boolean isMember)
        implements RegionView {
    }

    private static Region region(String id, AccessState entry, AccessState exit) {
        return new Region(id, "Name of " + id, entry, exit, false);
    }

    @Test
    void enteringARegionThatForbidsEntryIsRefused() {
        Optional<Refusal> refusal = rules.evaluate(List.of(region("district_closed", AccessState.DENY, AccessState.UNSET)), List.of());

        assertEquals(RegionTransitionType.ENTER, refusal.orElseThrow().type());
        assertEquals("You are not allowed to enter Name of district_closed.", refusal.get().message());
    }

    @Test
    void leavingARegionThatForbidsLeavingIsRefused() {
        Optional<Refusal> refusal = rules.evaluate(List.of(), List.of(region("district_jail", AccessState.UNSET, AccessState.DENY)));

        assertEquals(RegionTransitionType.EXIT, refusal.orElseThrow().type());
        assertEquals("You are not allowed to leave Name of district_jail.", refusal.get().message());
    }

    @Test
    void everyEnteredRegionCountsNotOnlyTheInnermost() {
        // A closed town around an open district: crossing both borders at once is refused (unlike WG's entry flag).
        Optional<Refusal> refusal = rules.evaluate(
            List.of(region("district_open", AccessState.ALLOW, AccessState.UNSET), region("town_closed", AccessState.DENY, AccessState.UNSET)),
            List.of());

        assertEquals("town_closed", refusal.orElseThrow().regionId());
    }

    @Test
    void movingBetweenDistrictsInsideAClosedTownIsAllowed() {
        // Only the district borders are crossed; the town is neither entered nor left.
        assertTrue(rules.evaluate(
            List.of(region("district_b", AccessState.UNSET, AccessState.UNSET)),
            List.of(region("district_a", AccessState.UNSET, AccessState.UNSET))).isEmpty());
    }

    @Test
    void ownersAndResidentsPass() {
        Region home = new Region("district_closed", "Old Quarter", AccessState.DENY, AccessState.DENY, true);

        assertTrue(rules.evaluate(List.of(home), List.of()).isEmpty());
        assertTrue(rules.evaluate(List.of(), List.of(home)).isEmpty());
    }

    @Test
    void entryIsCheckedBeforeExit() {
        Optional<Refusal> refusal = rules.evaluate(
            List.of(region("closed", AccessState.DENY, AccessState.UNSET)),
            List.of(region("jail", AccessState.UNSET, AccessState.DENY)));

        assertEquals(RegionTransitionType.ENTER, refusal.orElseThrow().type());
    }

    @Test
    void unsetAndAllowAreNotRestrictions() {
        assertTrue(rules.evaluate(
            List.of(region("a", AccessState.UNSET, AccessState.UNSET), region("b", AccessState.ALLOW, AccessState.ALLOW)),
            List.of(region("c", AccessState.ALLOW, AccessState.ALLOW))).isEmpty());
    }

    @Test
    void theRegionIdIsTheFallbackName() {
        Region nameless = new Region("domain_42", null, AccessState.DENY, AccessState.UNSET, false);

        assertEquals("You are not allowed to enter domain_42.", rules.evaluate(List.of(nameless), List.of()).orElseThrow().message());
    }

    @Test
    void allowedFlagsMapToStates() {
        assertEquals(AccessState.UNSET, RegionAccessRules.fromAllowed(null));
        assertEquals(AccessState.ALLOW, RegionAccessRules.fromAllowed(true));
        assertEquals(AccessState.DENY, RegionAccessRules.fromAllowed(false));
    }

    // ===== KNG-255: a domain's main region and sub-regions are one domain =====

    private record DomainRegion(String regionId, String displayName, AccessState entry, AccessState exit, boolean isMember,
                                String domainId) implements RegionView {
    }

    private static DomainRegion part(String id, String domainId, AccessState entry, AccessState exit) {
        return new DomainRegion(id, "Garrison", entry, exit, false, domainId);
    }

    @Test
    void walkingFromTheMainRegionIntoASubRegionOfANoExitDomainIsAllowed() {
        // House (main) and tower (sub) of a gate that may not be left; the border between them is inside the domain.
        DomainRegion house = part("domain_9", "9", AccessState.DENY, AccessState.DENY);
        DomainRegion tower = part("domain_9_sub_1", "9", AccessState.DENY, AccessState.DENY);

        assertTrue(rules.evaluate(List.of(tower), List.of(house), List.of(tower)).isEmpty());
        assertTrue(rules.evaluate(List.of(house), List.of(tower), List.of(house)).isEmpty());
    }

    @Test
    void enteringASubRegionFromOverlapWithTheMainRegionIsNoEntry() {
        DomainRegion house = part("domain_9", "9", AccessState.DENY, AccessState.UNSET);
        DomainRegion tower = part("domain_9_sub_1", "9", AccessState.DENY, AccessState.UNSET);

        // Standing in the house, stepping where house and tower overlap: the tower is entered, the domain is not.
        assertTrue(rules.evaluate(List.of(tower), List.of(), List.of(house, tower)).isEmpty());
    }

    @Test
    void leavingTheLastRegionOfTheDomainIsStillRefused_AndEnteringTheFirstToo() {
        DomainRegion tower = part("domain_9_sub_1", "9", AccessState.DENY, AccessState.DENY);
        Region street = region("district_1", AccessState.UNSET, AccessState.UNSET);

        assertEquals(RegionTransitionType.EXIT,
            rules.evaluate(List.of(), List.of(tower), List.of(street)).orElseThrow().type());
        assertEquals(RegionTransitionType.ENTER,
            rules.evaluate(List.of(tower), List.of(), List.of(street, tower)).orElseThrow().type());
    }

    @Test
    void regionsOfTwoDomainsOrWithoutADomainIdStayTheirOwn() {
        DomainRegion gate = part("domain_9", "9", AccessState.UNSET, AccessState.DENY);
        DomainRegion other = part("domain_10_sub_1", "10", AccessState.DENY, AccessState.UNSET);
        Region legacy = region("legacy", AccessState.UNSET, AccessState.DENY);
        Region legacyTwin = region("legacy_2", AccessState.DENY, AccessState.UNSET);

        assertEquals("domain_9", rules.evaluate(List.of(), List.of(gate), List.of(other)).orElseThrow().regionId());
        assertEquals("legacy_2", rules.evaluate(List.of(legacyTwin), List.of(legacy), List.of(legacyTwin)).orElseThrow().regionId());
    }

    @Test
    void withoutTheDestinationNothingIsGrouped() {
        DomainRegion house = part("domain_9", "9", AccessState.UNSET, AccessState.DENY);
        DomainRegion tower = part("domain_9_sub_1", "9", AccessState.UNSET, AccessState.UNSET);

        assertEquals(RegionTransitionType.EXIT, rules.evaluate(List.of(tower), List.of(house), null).orElseThrow().type());
    }
}
