package net.knightsandkings.knk.core.regions.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ManagedRegionReconcilerTest {

    private static ManagedRegionSpec spec(String id, ManagedRegionKind kind, String parent) {
        return new ManagedRegionSpec(id, kind, parent, kind + " " + id);
    }

    private static List<ManagedRegionSpec> world(FakeWorldGuard wg) {
        for (String id : List.of("town_1", "district_1", "house_1", "gate_1", "arena_2", "arena_2-battleground")) {
            wg.add(id);
        }
        return List.of(
                spec("town_1", ManagedRegionKind.TOWN, null),
                spec("district_1", ManagedRegionKind.DISTRICT, "town_1"),
                spec("house_1", ManagedRegionKind.HOUSE, "district_1"),
                spec("gate_1", ManagedRegionKind.GATE, "district_1"),
                spec("arena_2", ManagedRegionKind.ARENA, "district_1"),
                spec("arena_2-battleground", ManagedRegionKind.BATTLEGROUND, "arena_2"));
    }

    private final ManagedRegionReconciler reconciler = new ManagedRegionReconciler(new ManagedRegionPolicy(), Map.of());

    // ---- hierarchy and overlap priority ----

    @Test
    void parentsAndPrioritiesFollowTheHierarchy() {
        FakeWorldGuard wg = new FakeWorldGuard();
        RepairReport report = reconciler.reconcile(world(wg), wg);

        assertEquals(6, report.checked());
        assertEquals(6, report.changed());
        assertNull(wg.get("town_1").parent);
        assertEquals("town_1", wg.get("district_1").parent);
        assertEquals("district_1", wg.get("house_1").parent);
        assertEquals("district_1", wg.get("gate_1").parent);
        assertEquals("arena_2", wg.get("arena_2-battleground").parent);
        assertEquals(10, wg.get("town_1").priority);
        assertEquals(20, wg.get("district_1").priority);
        assertEquals(30, wg.get("house_1").priority);
        assertEquals(30, wg.get("gate_1").priority);
        assertEquals(40, wg.get("arena_2").priority);
        assertEquals(50, wg.get("arena_2-battleground").priority); // arena 40 + 10, also its floor
    }

    @Test
    void priorityDependsOnTheChainNotOnlyTheCategory() {
        FakeWorldGuard wg = new FakeWorldGuard();
        wg.add("town_1");
        wg.add("district_1");
        wg.add("house_1");
        wg.add("room_1");
        wg.add("house_orphan");
        reconciler.reconcile(List.of(
                spec("town_1", ManagedRegionKind.TOWN, null),
                spec("district_1", ManagedRegionKind.DISTRICT, "town_1"),
                spec("house_1", ManagedRegionKind.HOUSE, "district_1"),
                spec("room_1", ManagedRegionKind.ROOM, "house_1"),
                spec("house_orphan", ManagedRegionKind.HOUSE, null)), wg);

        assertEquals(30, wg.get("house_1").priority);
        assertEquals(40, wg.get("room_1").priority);
        assertEquals(30, wg.get("house_orphan").priority); // floor: no parent to build on
    }

    @Test
    void overlappingRegionsResolveMostSpecificFirstAndInheritFromTheTown() {
        FakeWorldGuard wg = new FakeWorldGuard();
        reconciler.reconcile(world(wg), wg);

        // a district inherits the town's pvp deny through its parent link
        assertEquals(FlagState.DENY, wg.resolve("pvp", "town_1", "district_1"));
        // a house inside a district inside a town: pvp still the town's, entry the house's
        assertEquals(FlagState.DENY, wg.resolve("pvp", "town_1", "district_1", "house_1"));
        assertEquals(FlagState.DENY, wg.resolve("entry", "town_1", "district_1", "house_1"));
        // the battleground inside the arena inside the district: its own pvp allow beats the town's deny
        assertEquals(FlagState.ALLOW, wg.resolve("pvp", "town_1", "district_1", "arena_2", "arena_2-battleground"));
        // the arena around it (no pvp of its own) still resolves to the town's deny
        assertEquals(FlagState.DENY, wg.resolve("pvp", "town_1", "district_1", "arena_2"));
        // and the arena base is open to enter
        assertEquals(FlagState.ALLOW, wg.resolve("entry", "town_1", "district_1", "arena_2"));
    }

    // ---- category flags and exceptions ----

    @Test
    void battlegroundPvpIsCorrectedButATownOverrideIsKept() {
        FakeWorldGuard wg = new FakeWorldGuard();
        List<ManagedRegionSpec> specs = world(wg);
        wg.get("town_1").flags.put("pvp", FlagState.ALLOW);                 // KNG-11 admin override
        wg.get("arena_2-battleground").flags.put("pvp", FlagState.DENY);    // wrong for the category

        reconciler.reconcile(specs, wg);

        assertEquals(FlagState.ALLOW, wg.get("town_1").flags.get("pvp"));
        assertEquals(FlagState.ALLOW, wg.get("arena_2-battleground").flags.get("pvp"));
        assertEquals(FlagState.DENY, wg.get("town_1").flags.get("mob-spawning")); // the rest still seeded
    }

    @Test
    void overridesTurnAStructureIntoTheWoodFarmAndCarryAnExactPriority() {
        FakeWorldGuard wg = new FakeWorldGuard();
        wg.add("district_1");
        wg.add("domain_47");
        wg.add("domain_48");
        Map<String, RegionOverride> overrides = Map.of(
                "domain_47", new RegionOverride(ManagedRegionKind.RESOURCE_PRODUCTION, null, null, List.of()),
                "domain_48", new RegionOverride(null, null, 77, List.of(FlagRule.seed("block-break", FlagState.ALLOW))));
        ManagedRegionReconciler custom = new ManagedRegionReconciler(new ManagedRegionPolicy(), overrides);

        custom.reconcile(List.of(
                spec("district_1", ManagedRegionKind.DISTRICT, null),
                spec("domain_47", ManagedRegionKind.STRUCTURE, "district_1"),
                spec("domain_48", ManagedRegionKind.STRUCTURE, "district_1")), wg);

        assertEquals(FlagState.ALLOW, wg.get("domain_47").flags.get("entry"));
        assertNull(wg.get("domain_47").flags.get("block-break"));   // allow-blocks has no WG equivalent: not opened up
        assertEquals(77, wg.get("domain_48").priority);
        assertEquals(FlagState.ALLOW, wg.get("domain_48").flags.get("block-break"));
    }

    @Test
    void aFlagWorldGuardDoesNotKnowIsSkippedWithAWarningNotAFailure() {
        FakeWorldGuard wg = new FakeWorldGuard();
        wg.add("domain_47");
        Map<String, RegionOverride> overrides = Map.of("domain_47",
                new RegionOverride(ManagedRegionKind.PROPERTY, null, null, List.of(FlagRule.seed("allow-blocks", "LOG;"))));

        RepairReport report = new ManagedRegionReconciler(new ManagedRegionPolicy(), overrides)
                .reconcile(List.of(spec("domain_47", ManagedRegionKind.STRUCTURE, null)), wg);

        assertEquals(0, report.failed());
        assertEquals(1, report.changed());
        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("allow-blocks")));
        assertNull(wg.get("domain_47").flags.get("allow-blocks"));
    }

    // ---- creation vs repair ----

    @Test
    void creatingARegionAndRepairingItEndInTheSameState() {
        FakeWorldGuard created = new FakeWorldGuard();
        FakeWorldGuard repaired = new FakeWorldGuard();
        List<ManagedRegionSpec> specs = world(created);
        world(repaired);

        // creation path: each region reconciled alone, in creation order (parents already exist)
        for (ManagedRegionSpec spec : specs) {
            reconciler.reconcileOne(spec, created);
        }
        // repair path: everything in one batch
        reconciler.reconcile(specs, repaired);

        for (String id : List.of("town_1", "district_1", "house_1", "gate_1", "arena_2", "arena_2-battleground")) {
            assertEquals(repaired.get(id).priority, created.get(id).priority, id + " priority");
            assertEquals(repaired.get(id).parent, created.get(id).parent, id + " parent");
            assertEquals(repaired.get(id).flags, created.get(id).flags, id + " flags");
        }
    }

    // ---- idempotence ----

    @Test
    void aSecondRunChangesNothingAndDoesNotSave() {
        FakeWorldGuard wg = new FakeWorldGuard();
        List<ManagedRegionSpec> specs = world(wg);

        reconciler.reconcile(specs, wg);
        Map<String, FakeWorldGuard.Region> afterFirst = wg.snapshotAll();
        int applies = wg.applyCalls;
        int saves = wg.persistCalls;

        RepairReport second = reconciler.reconcile(specs, wg);

        assertEquals(0, second.changed());
        assertEquals(6, second.unchanged());
        assertEquals(applies, wg.applyCalls);
        assertEquals(saves, wg.persistCalls);
        afterFirst.forEach((key, before) -> {
            assertEquals(before.flags, wg.get(key).flags);
            assertEquals(before.priority, wg.get(key).priority);
        });
    }

    // ---- preservation ----

    @Test
    void unrelatedRegionsOwnersMembersAndOtherFlagsAreUntouched() {
        FakeWorldGuard wg = new FakeWorldGuard();
        List<ManagedRegionSpec> specs = world(wg);
        wg.get("house_1").owners.add("uuid-owner");
        wg.get("house_1").members.add("uuid-member");
        wg.get("house_1").flags.put("greeting", "Welcome home");
        wg.get("town_1").flags.put("greeting", "Welcome to Rivia");
        FakeWorldGuard.Region manual = wg.add("spawn_protection");
        manual.priority = 99;
        manual.flags.put("pvp", FlagState.ALLOW);
        manual.owners.add("uuid-admin");
        Map<String, FakeWorldGuard.Region> before = wg.snapshotAll();

        reconciler.reconcile(specs, wg);

        assertEquals(before.get("house_1").owners, wg.get("house_1").owners);
        assertEquals(before.get("house_1").members, wg.get("house_1").members);
        assertEquals("Welcome home", wg.get("house_1").flags.get("greeting"));
        assertEquals("Welcome to Rivia", wg.get("town_1").flags.get("greeting"));
        assertEquals(before.get("spawn_protection").priority, wg.get("spawn_protection").priority);
        assertEquals(before.get("spawn_protection").flags, wg.get("spawn_protection").flags);
        assertEquals(before.get("spawn_protection").owners, wg.get("spawn_protection").owners);
        assertNull(wg.get("spawn_protection").parent);
    }

    // ---- stale data, failures ----

    @Test
    void aStaleDomainRecordIsSkippedNotCreated() {
        FakeWorldGuard wg = new FakeWorldGuard();
        wg.add("town_1");
        RepairReport report = reconciler.reconcile(List.of(
                spec("town_1", ManagedRegionKind.TOWN, null),
                spec("town_gone", ManagedRegionKind.TOWN, null)), wg);

        assertEquals(2, report.checked());
        assertEquals(1, report.changed());
        assertEquals(1, report.skipped());
        assertNull(wg.get("town_gone"));
    }

    @Test
    void aMissingParentKeepsTheCurrentParentAndFallsBackToTheFloor() {
        FakeWorldGuard wg = new FakeWorldGuard();
        wg.add("town_a");
        FakeWorldGuard.Region district = wg.add("district_1");
        district.parent = "town_a";
        RepairReport report = reconciler.reconcile(List.of(
                spec("district_1", ManagedRegionKind.DISTRICT, "town_missing")), wg);

        assertEquals("town_a", wg.get("district_1").parent);   // not cleared
        assertEquals(20, wg.get("district_1").priority);
        assertEquals(0, report.failed());
        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("town_missing")));
    }

    @Test
    void aParentCycleIsBrokenInsteadOfLoopingOrFailing() {
        FakeWorldGuard wg = new FakeWorldGuard();
        wg.add("a");
        wg.add("b");
        RepairReport report = reconciler.reconcile(List.of(
                spec("a", ManagedRegionKind.DISTRICT, "b"),
                spec("b", ManagedRegionKind.DISTRICT, "a")), wg);

        assertEquals(0, report.failed());
        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("loops")));
        assertTrue(wg.get("a").parent == null || wg.get("b").parent == null);
    }

    @Test
    void oneFailingRegionDoesNotStopTheOthersAndLeavesItselfUnchanged() {
        FakeWorldGuard wg = new FakeWorldGuard();
        List<ManagedRegionSpec> specs = world(wg);
        wg.failApplyFor.add("district_1");

        RepairReport report = reconciler.reconcile(specs, wg);

        assertEquals(1, report.failed());
        assertEquals(5, report.changed());
        assertEquals(0, wg.get("district_1").priority);
        assertEquals(30, wg.get("house_1").priority);
        assertEquals(1, wg.persistCalls);   // still saved once for the regions that did change

        // the next start, with WorldGuard healthy again, finishes the job
        wg.failApplyFor.clear();
        RepairReport retry = reconciler.reconcile(specs, wg);
        assertEquals(1, retry.changed());
        assertEquals(0, retry.failed());
    }

    @Test
    void aFailedSaveIsReportedButTheRunCompletes() {
        FakeWorldGuard wg = new FakeWorldGuard();
        wg.failPersist = true;
        RepairReport report = reconciler.reconcile(world(wg), wg);

        assertTrue(report.persistFailed());
        assertTrue(report.summary().contains("save=FAILED"));
        assertEquals(6, report.changed());
    }

    @Test
    void twoDomainsClaimingOneRegionDifferentlyAreLeftAlone() {
        FakeWorldGuard wg = new FakeWorldGuard();
        wg.add("shared");
        RepairReport report = reconciler.reconcile(List.of(
                new ManagedRegionSpec("shared", ManagedRegionKind.TOWN, null, "Town#1"),
                new ManagedRegionSpec("SHARED", ManagedRegionKind.HOUSE, null, "House#2")), wg);

        assertEquals(1, report.skipped());
        assertEquals(0, wg.applyCalls);
    }

    @Test
    void regionIdsMatchCaseInsensitivelyLikeWorldGuard() {
        FakeWorldGuard wg = new FakeWorldGuard();
        wg.add("town_1");
        wg.add("district_1");
        reconciler.reconcile(List.of(
                spec("Town_1", ManagedRegionKind.TOWN, null),
                spec("District_1", ManagedRegionKind.DISTRICT, "TOWN_1")), wg);
        assertEquals("town_1", wg.get("district_1").parent);
        assertEquals(20, wg.get("district_1").priority);
    }

    @Test
    void aNewRegionPicksItsAlreadyExistingParentUpFromTheStore() {
        FakeWorldGuard wg = new FakeWorldGuard();
        wg.add("district_1").priority = 20;
        wg.add("domain_9");

        List<ManagedRegionSpec> one = new ArrayList<>();
        one.add(spec("domain_9", ManagedRegionKind.HOUSE, "district_1"));
        RepairReport report = reconciler.reconcile(one, wg);

        assertEquals("district_1", wg.get("domain_9").parent);
        assertEquals(30, wg.get("domain_9").priority);
        assertEquals(1, report.changed());
    }
}
