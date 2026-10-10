package net.knightsandkings.knk.core.roads.walk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.regions.DomainAccessEvaluator;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import net.knightsandkings.knk.core.roads.build.GridFixture;
import net.knightsandkings.knk.core.roads.route.DomainAvailability;
import net.knightsandkings.knk.core.roads.route.GateAvailability;
import net.knightsandkings.knk.core.roads.route.RegionShape;
import net.knightsandkings.knk.core.util.BlockKey;
import org.junit.jupiter.api.Test;

/** The §6 cell-access adapters: gates by the router's gate rule, denied domain regions, doors the mover may not open. */
class WalkCellAccessTest {

    private static final String G = GridFixture.GRASS;

    private static GateAvailability.GateView gate(int id, AnimationState state, boolean jammed, boolean destroyed,
                                                  boolean passThrough, boolean siegeLocked, boolean siegeCarries) {
        return new GateAvailability.GateView(id, "West Gate", state, jammed, destroyed, passThrough, siegeLocked,
            siegeCarries);
    }

    /**
     * The server's pass rule for a non-admin: with the use node ({@code useNode}) a door that allows
     * pass-through, without it none (an admin would pass any door - GatePassThroughRules).
     */
    private static GateAvailability availability(Map<Integer, GateAvailability.GateView> views, boolean useNode) {
        return new GateAvailability(id -> Optional.ofNullable(views.get(id)),
            id -> useNode && views.containsKey(id) && views.get(id).allowPassThrough());
    }

    private static DomainSnapshot domain(int id, String name, String region, Boolean allowEntry, Boolean allowExit) {
        return new DomainSnapshot(id, name, null, region, allowEntry, allowExit, "Town", Set.of(), Set.of(), Set.of(),
            Set.of());
    }

    private static DomainAvailability.DomainLookup lookup(DomainSnapshot... domains) {
        Map<String, DomainSnapshot> m = new HashMap<>();
        for (DomainSnapshot d : domains) {
            m.put(d.wgRegionId(), d);
        }
        return region -> Optional.ofNullable(m.get(region));
    }

    private static boolean visits(WalkPath path, int x, int y, int z) {
        for (int i = 0; i < path.size(); i++) {
            if (path.cell(i) == BlockKey.pack(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    private static WalkPath found(WalkResult result) {
        assertEquals(WalkResult.Status.FOUND, result.status(), result.toString());
        return result.path().orElseThrow();
    }

    // ===== gates =====

    @Test
    void gateVerdictsFollowTheRoutersGateRule() {
        Map<Integer, GateAvailability.GateView> views = new HashMap<>();
        views.put(1, gate(1, AnimationState.OPEN, false, false, false, false, false));
        views.put(2, gate(2, AnimationState.CLOSED, false, false, false, false, false));
        views.put(3, gate(3, AnimationState.CLOSED, false, true, false, false, false)); // destroyed
        views.put(4, gate(4, AnimationState.CLOSED, false, false, true, false, false)); // pass-through
        views.put(5, gate(5, AnimationState.OPENING, false, false, false, false, false));
        views.put(6, gate(6, AnimationState.CLOSED, false, false, false, true, false)); // siege-locked
        views.put(7, gate(7, AnimationState.CLOSED, false, false, false, true, true)); // siege carries
        views.put(8, gate(8, AnimationState.OPEN, true, false, false, false, false)); // open beats jammed

        Map<Integer, String> blocked = GateCellAccess.decideAll(availability(views, true), List.of(1, 2, 3, 4, 5, 6, 7, 8, 99));

        assertEquals(Set.of(2, 5, 6), blocked.keySet(), "99 is unknown → open, like the router");
        assertEquals("the West Gate is closed", blocked.get(2));
        assertEquals("the West Gate is opening", blocked.get(5));
        assertTrue(GateCellAccess.decideAll(availability(views, false), List.of(4)).containsKey(4),
            "a pass-through gate the player may not pass is closed to them");
    }

    @Test
    void aCellIsBlockedWhenItsFloorFeetOrHeadIsInABlockedDoorsFootprint() {
        GridFixture grid = new GridFixture().gate(2, 5, 64, 0, 3); // footprint (5, 65..67, 0)
        GateCellAccess access = new GateCellAccess(grid, 2, Map.of(2, "the West Gate is closed"));

        assertEquals(CellAccess.BLOCKED, access.extraCost(5, 65, 0), "feet in the footprint");
        assertEquals(CellAccess.BLOCKED, access.extraCost(5, 64, 0), "head in the footprint");
        assertEquals(CellAccess.BLOCKED, access.extraCost(5, 67, 0), "floor in the footprint (on top of the gate)");
        assertEquals(CellAccess.BLOCKED, access.extraCost(5, 68, 0), "standing on the gate's top block");
        assertEquals(0.0, access.extraCost(5, 63, 0), "under it");
        assertEquals(0.0, access.extraCost(6, 65, 0), "next to it");
        assertEquals(Optional.of("the West Gate is closed"), access.denyReason(5, 65, 0));
        assertEquals(Optional.empty(), access.denyReason(6, 65, 0));

        GateCellAccess open = new GateCellAccess(grid, 2, Map.of());
        assertEquals(0.0, open.extraCost(5, 65, 0), "an open door's footprint is free");
    }

    @Test
    void theSearchDetoursAroundAClosedGateAndWalksThroughAnOpenOne() {
        // wall across z = 2 with a gate at x = 2 and an opening at x = 6
        WalkFixture f = new WalkFixture().floor(0, 0, 6, 4, 64, G).layer(65,
            ".......", ".......", "##.###.", ".......", ".......");
        f.grid.gate(9, 2, 64, 2, 3);
        Map<Integer, GateAvailability.GateView> views = new HashMap<>();
        views.put(9, gate(9, AnimationState.CLOSED, false, false, false, false, false));

        GateCellAccess shut = new GateCellAccess(f.grid, 2, GateCellAccess.decideAll(availability(views, false), List.of(9)));
        WalkPath detour = found(new WalkSearch().find(f.request(2, 64, 0, 2, 64, 4).withAccess(shut)));
        assertFalse(visits(detour, 2, 64, 2), "not through the closed gate");
        assertTrue(visits(detour, 6, 64, 2), "around through the opening");

        views.put(9, gate(9, AnimationState.OPEN, false, false, false, false, false));
        GateCellAccess open = new GateCellAccess(f.grid, 2, GateCellAccess.decideAll(availability(views, false), List.of(9)));
        assertTrue(visits(found(new WalkSearch().find(f.request(2, 64, 0, 2, 64, 4).withAccess(open))), 2, 64, 2));
    }

    // ===== denied regions =====

    @Test
    void onlyDenyingDomainsBecomeRulesEntryOutsideExitInside() {
        RegionShape east = RegionShape.cuboid(10, 0, 0, 20, 100, 10);
        RegionShape home = RegionShape.cuboid(0, 0, 0, 9, 100, 10);
        RegionShape free = RegionShape.cuboid(0, 0, 11, 20, 100, 20);
        List<DeniedRegionAccess.Candidate> candidates = List.of(
            new DeniedRegionAccess.Candidate("east", east, false),
            new DeniedRegionAccess.Candidate("home", home, true),
            new DeniedRegionAccess.Candidate("free", free, false),
            new DeniedRegionAccess.Candidate("unknown", free, false));
        DomainAvailability.DomainLookup lookup = lookup(
            domain(1, "Eastwatch", "east", false, true),
            domain(2, "Home", "home", true, false),
            domain(3, "Free", "free", true, true));

        DeniedRegionAccess access = DeniedRegionAccess.resolve(candidates, lookup, new DomainAccessEvaluator(), false);

        assertEquals(List.of("east", "home"), access.rules().stream().map(DeniedRegionAccess.Rule::regionId).toList());
        assertEquals(CellAccess.BLOCKED, access.extraCost(12, 65, 5), "inside a region it may not enter");
        assertEquals(Optional.of("you may not enter Eastwatch"), access.denyReason(12, 65, 5));
        assertEquals(CellAccess.BLOCKED, access.extraCost(5, 65, 15), "outside the region it may not leave");
        assertEquals(Optional.of("you may not leave Home"), access.denyReason(5, 65, 15));
        assertEquals(0.0, access.extraCost(5, 65, 5), "inside home, outside east");
    }

    @Test
    void anEntryDeniedRegionTheMoverIsAlreadyInIsNotAnEntryAndBypassOpensEverything() {
        RegionShape east = RegionShape.cuboid(10, 0, 0, 20, 100, 10);
        DomainAvailability.DomainLookup lookup = lookup(domain(1, "Eastwatch", "east", false, true));

        DeniedRegionAccess inside = DeniedRegionAccess.resolve(
            List.of(new DeniedRegionAccess.Candidate("east", east, true)), lookup, new DomainAccessEvaluator(), false);
        assertTrue(inside.rules().isEmpty(), "allowExit = true, so being inside denies nothing");

        DeniedRegionAccess bypass = DeniedRegionAccess.resolve(
            List.of(new DeniedRegionAccess.Candidate("east", east, false)), lookup, new DomainAccessEvaluator(), true);
        assertTrue(bypass.rules().isEmpty());
        assertEquals(0.0, bypass.extraCost(12, 65, 5));
    }

    @Test
    void containmentUsesTheFeetBlockAgainstTheRegionsYBand() {
        DeniedRegionAccess access = new DeniedRegionAccess(List.of(
            new DeniedRegionAccess.Rule("r", RegionShape.cuboid(0, 70, 0, 4, 80, 4), false, "no")));

        assertEquals(0.0, access.extraCost(2, 69, 2), "feet below the band");
        assertEquals(CellAccess.BLOCKED, access.extraCost(2, 70, 2), "feet on the band's lowest block");
        assertEquals(CellAccess.BLOCKED, access.extraCost(4, 80, 4), "the max corner is inside (WorldGuard is inclusive)");
        assertEquals(0.0, access.extraCost(5, 75, 2));
    }

    @Test
    void theSearchStaysOutOfADeniedRegionAndInsideOneItMayNotLeave() {
        WalkFixture f = new WalkFixture().floor(0, 0, 10, 6, 64, G);
        DeniedRegionAccess entry = new DeniedRegionAccess(List.of(
            new DeniedRegionAccess.Rule("keep", RegionShape.cuboid(3, 0, 0, 7, 100, 4), false, "no")));
        WalkPath around = found(new WalkSearch().find(f.request(0, 64, 2, 10, 64, 2).withAccess(entry)));
        for (int i = 0; i < around.size(); i++) {
            int x = BlockKey.x(around.cell(i));
            int z = BlockKey.z(around.cell(i));
            assertFalse(x >= 3 && x <= 7 && z <= 4, "entered the denied region at " + x + "," + z);
        }

        DeniedRegionAccess exit = new DeniedRegionAccess(List.of(
            new DeniedRegionAccess.Rule("home", RegionShape.cuboid(0, 0, 0, 5, 100, 6), true, "no")));
        assertEquals(WalkResult.Status.NO_PATH,
            new WalkSearch().find(f.request(1, 64, 2, 10, 64, 2).withAccess(exit)).status(),
            "the target lies outside a region the mover may not leave");
    }

    // ===== doors =====

    @Test
    void aDeniedDoorBlocksTheCellsWhoseFeetOrHeadAreInIt() {
        DoorCellAccess doors = new DoorCellAccess(List.of(BlockKey.pack(3, 65, 0), BlockKey.pack(3, 66, 0)), 2);

        assertEquals(CellAccess.BLOCKED, doors.extraCost(3, 65, 0));
        assertEquals(CellAccess.BLOCKED, doors.extraCost(3, 64, 0), "head in the lower half");
        assertEquals(0.0, doors.extraCost(3, 67, 0), "standing above the door");
        assertEquals(0.0, doors.extraCost(4, 65, 0));
        assertEquals(Optional.of(DoorCellAccess.DENY_REASON), doors.denyReason(3, 65, 0));
        assertEquals(0.0, DoorCellAccess.NONE.extraCost(3, 65, 0));
    }

    @Test
    void aDoorNeedsTheInteractCheckAndTheDomainRulesBoth() {
        // a wall across z = 2 with an oak door at x = 2 and an opening far east at x = 10
        WalkFixture f = new WalkFixture().floor(0, 0, 10, 4, 64, G)
            .layer(65, "...........", "...........", "##D#######.", "...........", "...........")
            .layer(66, "...........", "...........", "##.#######.", "...........", "...........");
        List<Long> doorBlocks = List.of(BlockKey.pack(2, 65, 2), BlockKey.pack(2, 66, 2));
        DeniedRegionAccess regionAroundDoor = new DeniedRegionAccess(List.of(
            new DeniedRegionAccess.Rule("door", RegionShape.cuboid(2, 0, 2, 2, 100, 2), false, "no")));

        WalkPath through = found(new WalkSearch().find(f.request(2, 64, 0, 2, 64, 4)
            .withAccess(CellAccess.all(DoorCellAccess.NONE, DeniedRegionAccess.NONE))));
        assertTrue(visits(through, 2, 64, 2), "allowed by both → through the door");

        List<CellAccess> denials = new ArrayList<>();
        denials.add(CellAccess.all(new DoorCellAccess(doorBlocks, 2), DeniedRegionAccess.NONE));
        denials.add(CellAccess.all(DoorCellAccess.NONE, regionAroundDoor));
        for (CellAccess denial : denials) {
            WalkPath around = found(new WalkSearch().find(f.request(2, 64, 0, 2, 64, 4).withAccess(denial)));
            assertFalse(visits(around, 2, 64, 2), "either denial keeps the path out of the door");
            assertTrue(visits(around, 10, 64, 2), "around through the opening");
        }
    }
}
