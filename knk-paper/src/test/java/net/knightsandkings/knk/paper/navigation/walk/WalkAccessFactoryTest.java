package net.knightsandkings.knk.paper.navigation.walk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.regions.DomainAccessEvaluator;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.route.GateAvailability;
import net.knightsandkings.knk.core.roads.route.RegionShape;
import net.knightsandkings.knk.core.roads.walk.CellAccess;
import net.knightsandkings.knk.core.roads.walk.WalkRequest;
import net.knightsandkings.knk.core.roads.walk.WalkResult;
import net.knightsandkings.knk.core.roads.walk.WalkSearch;
import net.knightsandkings.knk.core.util.BlockKey;
import net.knightsandkings.knk.paper.config.NavigationConfig;
import net.knightsandkings.knk.paper.utils.TickBudget;

/**
 * The per-player cell access of a captured box (KNG-51 §6): gates by the router's rule, the door
 * interact check once per door block, domain regions resolved off the main thread, the bypass.
 */
class WalkAccessFactoryTest {

    private final Player player = mock(Player.class);
    private final World world = mock(World.class);
    private final WalkCaptureTest.FakeWorld fake = WalkCaptureTest.village();
    private final List<String> doorChecks = new ArrayList<>();
    private final Map<Integer, GateAvailability.GateView> gateViews = new HashMap<>();
    private final List<Collection<Integer>> gateQueries = new ArrayList<>();
    private boolean bypass;
    private boolean doorsAllowed = true;
    private Map<String, RegionShape> regions = Map.of();
    private Set<String> playerRegions = Set.of();
    private final Map<String, DomainSnapshot> domains = new HashMap<>();

    private WalkAccessFactory factory() {
        return new WalkAccessFactory(
            (p, ids) -> {
                gateQueries.add(List.copyOf(ids));
                return new GateAvailability(id -> Optional.ofNullable(gateViews.get(id)), id -> false);
            },
            p -> playerRegions,
            p -> bypass,
            (w, box) -> regions,
            (p, w, x, y, z) -> {
                doorChecks.add(x + "," + y + "," + z);
                return doorsAllowed;
            },
            id -> Optional.ofNullable(domains.get(id)),
            new DomainAccessEvaluator());
    }

    private static DomainSnapshot domain(int id, String name, String region, Boolean allowEntry, Boolean allowExit) {
        return new DomainSnapshot(id, name, null, region, allowEntry, allowExit, "Town", Set.of(), Set.of(), Set.of(),
            Set.of());
    }

    /** The village captured around (1, 64, 1) → (8, 64, 8) with a margin of 4: the house, its door, the garden gate. */
    private WalkSnapshotService.WalkCapture capture() {
        WalkSnapshotService service = new WalkSnapshotService(WalkCaptureTest.rules(),
            NavigationConfig.WalkConfig.defaults(), new TickBudget(() -> 20.0), () -> 0L);
        WalkSnapshotServiceTest.FakeChunks chunks = new WalkSnapshotServiceTest.FakeChunks(fake);
        WalkBox box = WalkBox.around("world", 1.5, 65.0, 1.5, 8.5, 64, 8.5, 4, WalkCaptureTest.MIN_Y, WalkCaptureTest.MAX_Y);
        var future = service.capture(chunks, fake.gates(), box);
        for (int i = 0; i < 20 && !future.isDone(); i++) {
            service.tick();
        }
        return future.join();
    }

    private WalkResult walkInto(CellAccess access, WalkSnapshotService.WalkCapture capture) {
        return new WalkSearch().find(WalkRequest.toPoint(capture.terrain().terrain(), 1.5, 65.0, 1.5, 6.5, 64, 6.5, 0.25)
            .withAccess(access));
    }

    /** NO_PATH or FALLBACK (the search ran into the length cap looking for another way): no path either way (L2-1). */
    private static void assertNoWalkPath(WalkResult result, String why) {
        assertTrue(result.status() != WalkResult.Status.FOUND, why + ": " + result);
    }

    @Test
    void eachDoorBlockIsCheckedOnceAndADeniedDoorKeepsThePathOut() {
        WalkSnapshotService.WalkCapture capture = capture();
        assertEquals(Set.of(BlockKey.pack(6, 65, 4), BlockKey.pack(6, 66, 4), BlockKey.pack(10, 65, 2)),
            Set.copyOf(capture.doorBlocks()));

        WalkAccessFactory.WalkAccess allowed = factory().prepare(player, world, fake.gates(), capture, 2);
        assertEquals(3, doorChecks.size(), "both halves of the house door and the garden gate, once each");
        assertEquals(WalkResult.Status.FOUND, walkInto(allowed.resolve(), capture).status());

        doorsAllowed = false;
        WalkAccessFactory.WalkAccess denied = factory().prepare(player, world, fake.gates(), capture, 2);
        assertEquals(3, denied.doors().deniedDoorBlocks().size());
        assertNoWalkPath(walkInto(denied.resolve(), capture), "the house has no other way in");
    }

    @Test
    void theRegionBypassOpensDoorsAndDomainsWithoutAskingWorldGuard() {
        doorsAllowed = false;
        bypass = true;
        regions = Map.of("house", RegionShape.cuboid(4, 0, 4, 8, 100, 8));
        domains.put("house", domain(1, "House", "house", false, true));

        WalkAccessFactory.WalkAccess access = factory().prepare(player, world, fake.gates(), capture(), 2);

        assertTrue(doorChecks.isEmpty());
        assertTrue(access.regions().isEmpty());
        assertEquals(WalkResult.Status.FOUND, walkInto(access.resolve(), capture()).status());
    }

    @Test
    void aDomainThatDeniesEntryKeepsThePathOutOfItsRegionDoorIncluded() {
        regions = Map.of("house", RegionShape.cuboid(4, 0, 4, 8, 100, 8), "garden", RegionShape.cuboid(10, 0, 0, 13, 100, 3));
        domains.put("house", domain(1, "House", "house", false, true));
        domains.put("garden", domain(2, "Garden", "garden", true, true));

        WalkAccessFactory.WalkAccess access = factory().prepare(player, world, fake.gates(), capture(), 2);
        CellAccess resolved = access.resolve();

        assertEquals(2, access.regions().size(), "both regions are candidates; resolve keeps the denying one");
        assertEquals(CellAccess.BLOCKED, resolved.extraCost(6, 65, 4), "the door cell lies in the denied region");
        assertEquals(Optional.of("you may not enter House"), resolved.denyReason(6, 65, 6));
        assertEquals(0.0, resolved.extraCost(11, 65, 1), "the garden allows entry");
        assertNoWalkPath(walkInto(resolved, capture()), "the target is in the denied region");
    }

    @Test
    void aRegionThePlayerIsInAndMayNotLeaveKeepsThePathInside() {
        regions = Map.of("yard", RegionShape.cuboid(0, 0, 0, 3, 100, 3));
        playerRegions = Set.of("yard");
        domains.put("yard", domain(3, "Yard", "yard", false, false));

        CellAccess resolved = factory().prepare(player, world, fake.gates(), capture(), 2).resolve();

        assertEquals(0.0, resolved.extraCost(2, 65, 2), "inside; entry is not asked of a region one is in");
        assertEquals(Optional.of("you may not leave Yard"), resolved.denyReason(5, 65, 2));
    }

    @Test
    void gateVerdictsComeFromTheRoutersRuleForTheDoorsInTheBox() {
        // the village's open gate 7 at (5, 65..66, 24) and closed gate 8 at (6, 65..66, 24)
        gateViews.put(7, new GateAvailability.GateView(7, "North Gate", AnimationState.OPEN, false, false, false, false, false));
        gateViews.put(8, new GateAvailability.GateView(8, "South Gate", AnimationState.CLOSED, false, false, false, false, false));
        WalkSnapshotService service = new WalkSnapshotService(WalkCaptureTest.rules(),
            NavigationConfig.WalkConfig.defaults(), new TickBudget(() -> 20.0), () -> 0L);
        WalkBox box = WalkBox.around("world", 5.5, 65.0, 21.5, 6.5, 64, 26.5, 2, WalkCaptureTest.MIN_Y, WalkCaptureTest.MAX_Y);
        var future = service.capture(new WalkSnapshotServiceTest.FakeChunks(fake), fake.gates(), box);
        for (int i = 0; i < 20 && !future.isDone(); i++) {
            service.tick();
        }

        WalkAccessFactory.WalkAccess access = factory().prepare(player, world, fake.gates(), future.join(), 2);

        assertEquals(Set.of(7, 8), Set.copyOf(gateQueries.get(0)));
        assertEquals(0.0, access.gates().extraCost(5, 65, 24));
        assertEquals(CellAccess.BLOCKED, access.gates().extraCost(6, 65, 24));
        assertEquals(Optional.of("the South Gate is closed"), access.resolve().denyReason(6, 65, 24));
    }

    @Test
    void onlyAReadyCaptureCanBePrepared() {
        WalkSnapshotService.WalkCapture unloaded = new WalkSnapshotService.WalkCapture(WalkSnapshotService.Status.UNLOADED,
            new WalkBox("world", 0, 0, 0, 1, 1, 1), null, List.of());
        assertThrows(IllegalArgumentException.class, () -> factory().prepare(player, world, GateCells.NONE, unloaded, 2));
    }
}
