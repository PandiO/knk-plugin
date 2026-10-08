package net.knightsandkings.knk.paper.navigation.walk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.regions.DomainAccessEvaluator;
import net.knightsandkings.knk.core.roads.route.GateAvailability;
import net.knightsandkings.knk.core.roads.walk.WalkGoal;
import net.knightsandkings.knk.core.roads.walk.WalkRequest;
import net.knightsandkings.knk.core.roads.walk.WalkResult;
import net.knightsandkings.knk.core.roads.walk.WalkSearch;
import net.knightsandkings.knk.paper.config.NavigationConfig;
import net.knightsandkings.knk.paper.utils.TickBudget;

/** KNG-51 §3/§8: one walk leg's main-thread preparation - box, capture, access - and the request it builds. */
class WalkLegPreparerTest {

    private final WalkCaptureTest.FakeWorld fake = WalkCaptureTest.village();
    private final WalkSnapshotServiceTest.FakeChunks chunks = new WalkSnapshotServiceTest.FakeChunks(fake);
    private final NavigationConfig.WalkConfig config = new NavigationConfig.WalkConfig(true, 5000, 2.0, 64, 0, 2, 7, 4, 10,
        6, 2, java.util.List.of("LADDER"), 0.5);
    private final WalkSnapshotService snapshots = new WalkSnapshotService(WalkCaptureTest.rules(), config,
        new TickBudget(() -> 20.0), () -> 0L);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final WalkLegPreparer preparer;

    WalkLegPreparerTest() {
        when(world.getName()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(WalkCaptureTest.MIN_Y);
        when(world.getMaxHeight()).thenReturn(WalkCaptureTest.MAX_Y);
        when(player.getWorld()).thenReturn(world);
        WalkAccessFactory access = new WalkAccessFactory(
            (p, ids) -> new GateAvailability(id -> Optional.empty(), id -> false), p -> Set.of(), p -> false,
            (w, box) -> java.util.Map.of(), (p, w, x, y, z) -> true, id -> Optional.empty(), new DomainAccessEvaluator());
        preparer = new WalkLegPreparer(snapshots, access, config, name -> fake.gates(), w -> chunks);
    }

    private <T> T await(CompletableFuture<T> future) {
        for (int i = 0; i < 40 && !future.isDone(); i++) {
            snapshots.tick();
        }
        return future.join();
    }

    @Test
    void aReadyCaptureBuildsTheRequestWithTheConfigsProfileBudgetAndTheGoal() {
        WalkGoal goal = WalkGoal.within(6.5, 64, 6.5, 0.25);

        Supplier<WalkRequest> supplier = await(preparer.prepare(player, new double[] {1.5, 65.0, 1.5},
            new double[] {6.5, 64, 6.5}, goal));
        WalkRequest request = supplier.get();

        assertSame(goal, request.goal());
        assertEquals(config.profile(), request.profile());
        assertEquals(config.budget(), request.budget());
        assertEquals(1.5, request.startX(), 1e-9);
        assertEquals(65.0, request.startY(), 1e-9);
        assertEquals(64, request.targetFloorY(), 1e-9);
        WalkResult result = new WalkSearch().find(request);
        assertTrue(result.isFound(), "the village is walkable from the lane into the house: " + result);
        assertTrue(preparer.describe().contains("capture:"), preparer.describe());
    }

    @Test
    void anUnloadedChunkCompletesAtOnceWithNoRequest() {
        chunks.unloaded.add(WalkChunk.key(0, 0));

        CompletableFuture<Supplier<WalkRequest>> future = preparer.prepare(player, new double[] {1.5, 65.0, 1.5},
            new double[] {6.5, 64, 6.5}, WalkGoal.within(6.5, 64, 6.5, 1));

        assertTrue(future.isDone(), "no waiting for a chunk that is not there");
        assertNull(future.join(), "null = keep the straight line");
    }

    @Test
    void cancellingTheLegCancelsTheCapture() {
        CompletableFuture<Supplier<WalkRequest>> future = preparer.prepare(player, new double[] {1.5, 65.0, 1.5},
            new double[] {6.5, 64, 6.5}, WalkGoal.within(6.5, 64, 6.5, 1));
        assertEquals(1, snapshots.stats().pendingRequests());

        future.cancel(false);
        snapshots.tick();

        assertEquals(0, snapshots.stats().pendingRequests());
        assertTrue(chunks.captured.isEmpty(), "nobody waits for the chunks any more: " + chunks.captured);
    }
}
