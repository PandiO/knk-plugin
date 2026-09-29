package net.knightsandkings.knk.core.regions.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class ManagedRegionPolicyTest {

    private final ManagedRegionPolicy policy = new ManagedRegionPolicy();

    private Set<String> flagNames(ManagedRegionKind kind) {
        return policy.flagRules(kind).stream().map(FlagRule::flag).collect(Collectors.toSet());
    }

    private FlagRule rule(ManagedRegionKind kind, String flag) {
        return policy.flagRules(kind).stream().filter(r -> r.flag().equals(flag)).findFirst().orElseThrow();
    }

    @Test
    void priorityIsParentPlusStepButNeverBelowTheFloor() {
        assertEquals(10, policy.priorityFor(ManagedRegionKind.TOWN, null));
        assertEquals(20, policy.priorityFor(ManagedRegionKind.DISTRICT, 10));
        assertEquals(30, policy.priorityFor(ManagedRegionKind.HOUSE, 20));
        // a shallower chain than the category expects still gets the category floor
        assertEquals(30, policy.priorityFor(ManagedRegionKind.HOUSE, 10));
        // a deeper chain ranks above its parent even past the floor
        assertEquals(60, policy.priorityFor(ManagedRegionKind.BATTLEGROUND, 50));
        assertEquals(50, policy.priorityFor(ManagedRegionKind.BATTLEGROUND, null));
    }

    @Test
    void categoriesRankTownBelowDistrictBelowStructureBelowRoomAndArena() {
        assertTrue(ManagedRegionKind.TOWN.floorPriority() < ManagedRegionKind.DISTRICT.floorPriority());
        assertTrue(ManagedRegionKind.DISTRICT.floorPriority() < ManagedRegionKind.STRUCTURE.floorPriority());
        assertTrue(ManagedRegionKind.STRUCTURE.floorPriority() < ManagedRegionKind.ROOM.floorPriority());
        assertTrue(ManagedRegionKind.ARENA.floorPriority() < ManagedRegionKind.BATTLEGROUND.floorPriority());
    }

    @Test
    void townFlagsFollowV1AndLeaveGreetingsAndEntryAlone() {
        assertEquals(FlagState.DENY, rule(ManagedRegionKind.TOWN, "pvp").value());
        assertEquals(FlagState.DENY, rule(ManagedRegionKind.TOWN, "mob-spawning").value());
        assertEquals(FlagState.DENY, rule(ManagedRegionKind.TOWN, "entity-item-frame-destroy").value());
        assertEquals(FlagState.ALLOW, rule(ManagedRegionKind.TOWN, "damage-animals").value());
        assertEquals("", rule(ManagedRegionKind.TOWN, "deny-message").value());
        Set<String> names = flagNames(ManagedRegionKind.TOWN);
        assertFalse(names.contains("greeting") || names.contains("farewell") || names.contains("entry")
                || names.contains("entry-deny-message"));
        // the KNG-11 override (`/rg flag <town> pvp allow`) must survive a repair
        assertEquals(FlagMode.SEED_IF_ABSENT, rule(ManagedRegionKind.TOWN, "pvp").mode());
    }

    @Test
    void districtsAndGatesInheritAndCarryNoFlagsOfTheirOwn() {
        assertTrue(policy.flagRules(ManagedRegionKind.DISTRICT).isEmpty());
        assertTrue(policy.flagRules(ManagedRegionKind.GATE).isEmpty());
        assertTrue(policy.flagRules(ManagedRegionKind.STRUCTURE).isEmpty());
    }

    @Test
    void housesAndRoomsAreClosedAndFedPropertiesAndArenasAreOpen() {
        for (ManagedRegionKind kind : List.of(ManagedRegionKind.HOUSE, ManagedRegionKind.ROOM)) {
            assertEquals(FlagState.DENY, rule(kind, "entry").value());
            assertEquals(20, rule(kind, "feed-amount").value());
            assertEquals(1, rule(kind, "feed-delay").value());
            assertEquals("", rule(kind, "entry-deny-message").value());
        }
        for (ManagedRegionKind kind : List.of(ManagedRegionKind.PROPERTY, ManagedRegionKind.ARENA)) {
            assertEquals(FlagState.ALLOW, rule(kind, "entry").value());
        }
    }

    @Test
    void battlegroundPvpIsAnEnforcedException() {
        assertEquals(FlagState.ALLOW, rule(ManagedRegionKind.BATTLEGROUND, "pvp").value());
        assertEquals(FlagMode.ENFORCE, rule(ManagedRegionKind.BATTLEGROUND, "pvp").mode());
        assertEquals(FlagState.DENY, rule(ManagedRegionKind.BATTLEGROUND, "entry").value());
    }

    @Test
    void woodFarmBlockBreakNeedsTheOptInBecauseAllowBlocksHasNoWorldGuardEquivalent() {
        assertFalse(flagNames(ManagedRegionKind.RESOURCE_PRODUCTION).contains("block-break"));
        assertFalse(flagNames(ManagedRegionKind.RESOURCE_PRODUCTION).contains("allow-blocks"));
        ManagedRegionPolicy optIn = new ManagedRegionPolicy(new ManagedRegionPolicy.Options(true, false));
        assertTrue(optIn.flagRules(ManagedRegionKind.RESOURCE_PRODUCTION).stream().anyMatch(r -> r.flag().equals("block-break")));
        assertFalse(optIn.flagRules(ManagedRegionKind.PROPERTY).stream().anyMatch(r -> r.flag().equals("block-break")));
    }

    @Test
    void globalRegionIsUntouchedUnlessEnabled() {
        assertTrue(policy.flagRules(ManagedRegionKind.GLOBAL).isEmpty());
        ManagedRegionPolicy on = new ManagedRegionPolicy(new ManagedRegionPolicy.Options(false, true));
        assertEquals(FlagState.DENY, on.flagRules(ManagedRegionKind.GLOBAL).stream()
                .filter(r -> r.flag().equals("build")).findFirst().orElseThrow().value());
    }

    @Test
    void kindsMapFromDomainTypesAndConfigNames() {
        assertEquals(Optional.of(ManagedRegionKind.TOWN), ManagedRegionKind.fromDomainType("Town"));
        assertEquals(Optional.of(ManagedRegionKind.GATE), ManagedRegionKind.fromDomainType("GateStructure"));
        assertEquals(Optional.of(ManagedRegionKind.STRUCTURE), ManagedRegionKind.fromDomainType("Warehouse"));
        assertEquals(Optional.empty(), ManagedRegionKind.fromDomainType(" "));
        assertEquals(Optional.of(ManagedRegionKind.RESOURCE_PRODUCTION), ManagedRegionKind.parse("resource-production"));
        assertEquals(Optional.of(ManagedRegionKind.BATTLEGROUND), ManagedRegionKind.parse("Battleground"));
        assertEquals(Optional.empty(), ManagedRegionKind.parse("castle"));
    }
}
