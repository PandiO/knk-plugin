package net.knightsandkings.knk.core.regions;

import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterisation tests for SimpleRegionTransitionService's entry/exit policy and messages,
 * written before the entry/exit rules were extracted to DomainAccessEvaluator (road navigation
 * plan §2 R6) so the extraction is proven behaviour-neutral: these tests must stay green,
 * unchanged, across it. They pin the wording of every message the service produces today.
 */
class SimpleRegionTransitionServiceTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final RegionDomainResolver resolver = new RegionDomainResolver();

    // === Entry denials ===

    @Test
    void enteringADomainThatForbidsEntryIsDeniedWithItsName() {
        register(town(1, "Cinix", "town_cinix", false, true));
        SimpleRegionTransitionService service = new SimpleRegionTransitionService(resolver);

        RegionTransitionDecision decision = service.handleRegionTransition(PLAYER, Set.of(), Set.of("town_cinix"));

        assertFalse(decision.isMovementAllowed());
        assertEquals(RegionTransitionType.ENTER, decision.getType());
        assertEquals(Optional.of("You are not allowed to enter Cinix."), decision.getMessage());
    }

    @Test
    void enteringAStructureThatForbidsEntryIsDeniedTheSameWay() {
        register(structure(3, "West Gate", "structure_west_gate", false, null));
        SimpleRegionTransitionService service = new SimpleRegionTransitionService(resolver);

        RegionTransitionDecision decision = service.handleRegionTransition(PLAYER, Set.of(), Set.of("structure_west_gate"));

        assertFalse(decision.isMovementAllowed());
        assertEquals(RegionTransitionType.ENTER, decision.getType());
        assertEquals(Optional.of("You are not allowed to enter West Gate."), decision.getMessage());
    }

    @Test
    void anUnsetAllowEntryFlagDoesNotDeny() {
        register(town(1, "Cinix", "town_cinix", null, null));
        SimpleRegionTransitionService service = new SimpleRegionTransitionService(resolver);

        RegionTransitionDecision decision = service.handleRegionTransition(PLAYER, Set.of(), Set.of("town_cinix"));

        assertTrue(decision.isMovementAllowed());
        assertEquals(Optional.of("You are now entering Cinix."), decision.getMessage());
    }

    @Test
    void aDomainThePlayerIsAlreadyInIsNotReCheckedForEntry() {
        register(town(1, "Cinix", "town_cinix", false, true));
        register(district(2, "Docks", "district_docks", true, true, "Cinix"));
        SimpleRegionTransitionService service = new SimpleRegionTransitionService(resolver);

        // Already inside the (no-entry) town; only the district is entered.
        RegionTransitionDecision decision = service.handleRegionTransition(
            PLAYER, Set.of("town_cinix"), Set.of("town_cinix", "district_docks"));

        assertTrue(decision.isMovementAllowed());
        assertEquals(Optional.of("You are now entering Docks * Cinix *"), decision.getMessage());
    }

    // === Exit denials ===

    @Test
    void leavingADomainThatForbidsExitIsDeniedWithItsName() {
        register(town(1, "Cinix", "town_cinix", true, false));
        SimpleRegionTransitionService service = new SimpleRegionTransitionService(resolver);

        RegionTransitionDecision decision = service.handleRegionTransition(PLAYER, Set.of("town_cinix"), Set.of());

        assertFalse(decision.isMovementAllowed());
        assertEquals(RegionTransitionType.EXIT, decision.getType());
        assertEquals(Optional.of("You are not allowed to leave Cinix."), decision.getMessage());
    }

    @Test
    void leavingADistrictThatForbidsExitIsDenied() {
        register(district(2, "Docks", "district_docks", true, false, "Cinix"));
        SimpleRegionTransitionService service = new SimpleRegionTransitionService(resolver);

        RegionTransitionDecision decision = service.handleRegionTransition(PLAYER, Set.of("district_docks"), Set.of());

        assertFalse(decision.isMovementAllowed());
        assertEquals(RegionTransitionType.EXIT, decision.getType());
        assertEquals(Optional.of("You are not allowed to leave Docks."), decision.getMessage());
    }

    @Test
    void anUnsetAllowExitFlagDoesNotDeny() {
        register(town(1, "Cinix", "town_cinix", null, null));
        SimpleRegionTransitionService service = new SimpleRegionTransitionService(resolver);

        RegionTransitionDecision decision = service.handleRegionTransition(PLAYER, Set.of("town_cinix"), Set.of());

        assertTrue(decision.isMovementAllowed());
        assertEquals(Optional.of("You are now leaving Cinix."), decision.getMessage());
    }

    @Test
    void entryDenialWinsOverExitDenialInTheSameTransition() {
        register(town(1, "Cinix", "town_cinix", true, false));
        register(town(2, "Kardenna", "town_kardenna", false, true));
        SimpleRegionTransitionService service = new SimpleRegionTransitionService(resolver);

        RegionTransitionDecision decision = service.handleRegionTransition(
            PLAYER, Set.of("town_cinix"), Set.of("town_kardenna"));

        assertFalse(decision.isMovementAllowed());
        assertEquals(RegionTransitionType.ENTER, decision.getType());
        assertEquals(Optional.of("You are not allowed to enter Kardenna."), decision.getMessage());
    }

    // === Messages for allowed transitions (Town > District > Structure) ===

    @Test
    void enteringATownProducesTheTownMessageEvenWhenADistrictIsEnteredToo() {
        register(town(1, "Cinix", "town_cinix", true, true));
        register(district(2, "Docks", "district_docks", true, true, "Cinix"));
        SimpleRegionTransitionService service = new SimpleRegionTransitionService(resolver);

        RegionTransitionDecision decision = service.handleRegionTransition(
            PLAYER, Set.of(), Set.of("town_cinix", "district_docks"));

        assertTrue(decision.isMovementAllowed());
        assertEquals(RegionTransitionType.ENTER, decision.getType());
        assertEquals(Optional.of("You are now entering Cinix."), decision.getMessage());
    }

    @Test
    void leavingATownProducesTheLeavingMessage() {
        register(town(1, "Cinix", "town_cinix", true, true));
        SimpleRegionTransitionService service = new SimpleRegionTransitionService(resolver);

        RegionTransitionDecision decision = service.handleRegionTransition(PLAYER, Set.of("town_cinix"), Set.of());

        assertTrue(decision.isMovementAllowed());
        assertEquals(RegionTransitionType.EXIT, decision.getType());
        assertEquals(Optional.of("You are now leaving Cinix."), decision.getMessage());
    }

    @Test
    void enteringADistrictWithoutAParentNameEndsWithAFullStop() {
        register(district(2, "Docks", "district_docks", true, true));
        SimpleRegionTransitionService service = new SimpleRegionTransitionService(resolver);

        RegionTransitionDecision decision = service.handleRegionTransition(PLAYER, Set.of(), Set.of("district_docks"));

        assertEquals(Optional.of("You are now entering Docks."), decision.getMessage());
    }

    @Test
    void leavingADistrictProducesNoMessage() {
        register(district(2, "Docks", "district_docks", true, true, "Cinix"));
        SimpleRegionTransitionService service = new SimpleRegionTransitionService(resolver);

        RegionTransitionDecision decision = service.handleRegionTransition(PLAYER, Set.of("district_docks"), Set.of());

        assertTrue(decision.isMovementAllowed());
        assertEquals(Optional.empty(), decision.getMessage());
    }

    @Test
    void enteringAStructureProducesTheStructureMessage() {
        register(structure(3, "Smithy", "structure_smithy", true, true));
        SimpleRegionTransitionService service = new SimpleRegionTransitionService(resolver);

        RegionTransitionDecision decision = service.handleRegionTransition(PLAYER, Set.of(), Set.of("structure_smithy"));

        assertTrue(decision.isMovementAllowed());
        assertEquals(RegionTransitionType.ENTER, decision.getType());
        assertEquals(Optional.of("You are now entering Smithy."), decision.getMessage());
    }

    @Test
    void noDomainChangeIsAllowedWithoutAMessage() {
        register(town(1, "Cinix", "town_cinix", true, true));
        SimpleRegionTransitionService service = new SimpleRegionTransitionService(resolver);

        RegionTransitionDecision decision = service.handleRegionTransition(
            PLAYER, Set.of("town_cinix"), Set.of("town_cinix", "unknown_region"));

        assertTrue(decision.isMovementAllowed());
        assertEquals(Optional.empty(), decision.getMessage());
    }

    // === Callback ===

    @Test
    void enteredDomainsAreReportedToTheCallbackOnlyWhenMovementIsAllowed() {
        register(town(1, "Cinix", "town_cinix", true, true));
        register(town(2, "Kardenna", "town_kardenna", false, true));
        List<Set<DomainSnapshot>> reported = new ArrayList<>();
        SimpleRegionTransitionService service = new SimpleRegionTransitionService(resolver, null, reported::add);

        service.handleRegionTransition(PLAYER, Set.of(), Set.of("town_kardenna"));
        service.handleRegionTransition(PLAYER, Set.of(), Set.of("town_cinix"));

        assertEquals(1, reported.size());
        assertEquals("Cinix", reported.get(0).iterator().next().name());
    }

    // === previewAccess (KNG-17's teleport check, road navigation plan R6 follow-up) ===

    @Test
    void previewAccessAppliesTheSameEntryAndExitRulesWithoutSideEffects() {
        register(town(1, "Cinix", "town_cinix", false, true));
        register(town(2, "Kardenna", "town_kardenna", true, false));
        List<Set<DomainSnapshot>> reported = new ArrayList<>();
        SimpleRegionTransitionService service = new SimpleRegionTransitionService(resolver, null, reported::add);

        RegionTransitionDecision entry = service.previewAccess(Set.of(), Set.of("town_cinix"));
        RegionTransitionDecision exit = service.previewAccess(Set.of("town_kardenna"), Set.of());
        RegionTransitionDecision allowed = service.previewAccess(Set.of(), Set.of("town_kardenna"));

        assertEquals(RegionTransitionType.ENTER, entry.getType());
        assertEquals(Optional.of("You are not allowed to enter Cinix."), entry.getMessage());
        assertEquals(RegionTransitionType.EXIT, exit.getType());
        assertEquals(Optional.of("You are not allowed to leave Kardenna."), exit.getMessage());
        assertEquals(null, allowed, "allowed = null, as the teleport engine expects");
        assertTrue(reported.isEmpty(), "a preview never reports entered domains");
    }

    // === Fixtures ===

    private void register(DomainSnapshot domain) {
        resolver.registerDomain(domain);
    }

    private static DomainSnapshot town(int id, String name, String regionId, Boolean allowEntry, Boolean allowExit) {
        return new DomainSnapshot(id, name, "", regionId, allowEntry, allowExit, "Town",
            Set.of(), Set.of(), Set.of(), Set.of());
    }

    private static DomainSnapshot district(int id, String name, String regionId, Boolean allowEntry, Boolean allowExit,
                                           String... parentNames) {
        return new DomainSnapshot(id, name, "", regionId, allowEntry, allowExit, "District",
            Set.of(), Set.of(parentNames), Set.of(), Set.of());
    }

    private static DomainSnapshot structure(int id, String name, String regionId, Boolean allowEntry, Boolean allowExit) {
        return new DomainSnapshot(id, name, "", regionId, allowEntry, allowExit, "Structure",
            Set.of(), Set.of(), Set.of(), Set.of());
    }
}
