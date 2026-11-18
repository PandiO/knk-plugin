package net.knightsandkings.knk.paper.tasks;

import net.knightsandkings.knk.core.ports.api.TempRegionReleasesApi;
import net.knightsandkings.knk.core.regions.TempRegionCleanupPolicy.Usage;
import net.knightsandkings.knk.core.regions.TempRegionRelease;
import net.knightsandkings.knk.paper.regions.RegionBookkeepingFlags;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PandiO/knk-workspace#269: one pass over the API's released temp regions. Removes and settles what may go, settles
 * missing and kept ones, and leaves for the next pass whatever has to wait (task running here, API unreachable, a failed
 * removal), so nothing is lost when unsure. No Bukkit: a fake API and region store.
 */
class TempRegionReleasePassTest {

    /** world -> region ids, with their creation timestamps. */
    private final Map<String, Map<String, String>> worlds = new HashMap<>();
    private final List<String> removed = new ArrayList<>();
    private final List<String> settled = new ArrayList<>();
    private final List<TempRegionRelease> due = new ArrayList<>();
    private boolean listFails;
    private boolean settleFails;
    private String removeFailsFor;

    private final TempRegionReleasesApi api = new TempRegionReleasesApi() {
        @Override
        public CompletableFuture<List<TempRegionRelease>> listDue(String serverId) {
            assertEquals("survival-1", serverId);
            return listFails ? CompletableFuture.failedFuture(new RuntimeException("API down")) : CompletableFuture.completedFuture(List.copyOf(due));
        }

        @Override
        public CompletableFuture<Boolean> settle(int releaseId, TempRegionRelease.Outcome outcome, String serverId, String detail) {
            if (settleFails) return CompletableFuture.failedFuture(new RuntimeException("API down"));
            settled.add(releaseId + ":" + outcome.wire());
            return CompletableFuture.completedFuture(true);
        }
    };

    private final TempRegionReleasePass.RegionStore store = new TempRegionReleasePass.RegionStore() {
        @Override
        public List<String> worldsHolding(String regionId, String preferredWorld) {
            List<String> holding = new ArrayList<>();
            for (var entry : worlds.entrySet()) {
                if ((preferredWorld == null || !worlds.containsKey(preferredWorld) || entry.getKey().equals(preferredWorld))
                        && entry.getValue().keySet().stream().anyMatch(id -> id.equalsIgnoreCase(regionId))) {
                    holding.add(entry.getKey());
                }
            }
            holding.sort(String::compareTo);
            return holding;
        }

        @Override
        public String flag(String world, String regionId, String flagName) {
            assertEquals(RegionBookkeepingFlags.CREATION_TIMESTAMP, flagName);
            return worlds.get(world).get(regionId);
        }

        @Override
        public void remove(String world, String regionId) {
            if (regionId.equals(removeFailsFor)) throw new IllegalStateException("locked");
            worlds.get(world).remove(regionId);
            removed.add(world + "/" + regionId);
        }
    };

    private void region(String world, String id, String stamp) {
        worlds.computeIfAbsent(world, w -> new HashMap<>()).put(id, stamp);
    }

    private static TempRegionRelease release(int id, int taskId, String world) {
        return new TempRegionRelease(id, taskId, "tempregion_worldtask_" + taskId, world, "DraftDeleted");
    }

    private TempRegionReleasePass pass(Set<String> inUse, Set<Integer> activeTasks, boolean apiReachable) {
        return new TempRegionReleasePass(api, "survival-1", store, id -> false,
                id -> !apiReachable ? Usage.UNKNOWN : inUse.contains(id) ? Usage.IN_USE : Usage.UNUSED,
                activeTasks::contains);
    }

    @Test
    void removesUnusedReleasedRegions_settlesMissingAndKeptOnes() {
        region("knk_hub", "tempregion_worldtask_1", "1762600000000");
        region("knk_hub", "tempregion_worldtask_2", "1762600000000");
        region("knk_hub", "tempregion_worldtask_4", null);
        due.addAll(List.of(release(11, 1, "knk_hub"), release(12, 2, "knk_hub"), release(13, 3, "knk_hub"), release(14, 4, null)));

        TempRegionReleasePass.Result result = pass(Set.of("tempregion_worldtask_2"), Set.of(), true).run();

        assertEquals(List.of("knk_hub/tempregion_worldtask_1"), removed);
        assertEquals(List.of("knk_hub/tempregion_worldtask_1"), result.removed());
        assertEquals(List.of("tempregion_worldtask_3"), result.missing());
        assertEquals(List.of("knk_hub/tempregion_worldtask_2", "knk_hub/tempregion_worldtask_4"), result.kept());
        assertEquals(List.of("11:Removed", "12:Kept", "13:Missing", "14:Kept"), settled);
        assertTrue(worlds.get("knk_hub").containsKey("tempregion_worldtask_2"));
    }

    @Test
    void aTaskRunningHere_orAnUnreachableApi_waitsForTheNextPass_unsettled() {
        region("knk_hub", "tempregion_worldtask_1", "1762600000000");
        region("knk_hub", "tempregion_worldtask_2", "1762600000000");
        due.addAll(List.of(release(11, 1, "knk_hub"), release(12, 2, "knk_hub")));

        TempRegionReleasePass.Result active = pass(Set.of(), Set.of(1), true).run();
        assertEquals(List.of("knk_hub/tempregion_worldtask_1"), active.waiting());
        assertEquals(List.of("12:Removed"), settled);

        settled.clear();
        removed.clear();
        TempRegionReleasePass.Result down = pass(Set.of(), Set.of(), false).run();

        // Task 1's region needs the lookup and waits; task 2's is gone already, which needs no lookup.
        assertTrue(removed.isEmpty());
        assertEquals(List.of("knk_hub/tempregion_worldtask_1"), down.waiting());
        assertEquals(List.of("12:Missing"), settled);
    }

    @Test
    void aWorldlessReleaseSearchesEveryWorld_andTheSameIdInTwoWorldsIsKept() {
        region("knk_hub", "tempregion_worldtask_1", "1762600000000");
        region("world", "tempregion_worldtask_1", "1762600000000");
        region("world", "tempregion_worldtask_2", "1762600000000");
        due.addAll(List.of(release(11, 1, null), release(12, 2, null)));

        TempRegionReleasePass.Result result = pass(Set.of(), Set.of(), true).run();

        assertEquals(List.of("world/tempregion_worldtask_2"), removed);
        assertEquals(List.of("tempregion_worldtask_1"), result.kept());
        assertEquals(List.of("11:Kept", "12:Removed"), settled);
    }

    @Test
    void aFailedRemoval_isNotSettled_aFailedSettleIsListedAgainLater() {
        region("knk_hub", "tempregion_worldtask_1", "1762600000000");
        region("knk_hub", "tempregion_worldtask_2", "1762600000000");
        due.addAll(List.of(release(11, 1, "knk_hub"), release(12, 2, "knk_hub")));
        removeFailsFor = "tempregion_worldtask_1";

        TempRegionReleasePass.Result result = pass(Set.of(), Set.of(), true).run();

        assertEquals(List.of("knk_hub/tempregion_worldtask_1"), result.failed());
        assertEquals(List.of("12:Removed"), settled);

        settleFails = true;
        removeFailsFor = null;
        TempRegionReleasePass.Result again = pass(Set.of(), Set.of(), true).run();
        assertEquals(List.of("knk_hub/tempregion_worldtask_1"), again.removed());
        assertEquals(List.of("tempregion_worldtask_2"), again.missing());
    }

    @Test
    void anUnreachableListDoesNothing() {
        region("knk_hub", "tempregion_worldtask_1", "1762600000000");
        listFails = true;

        TempRegionReleasePass.Result result = pass(Set.of(), Set.of(), true).run();

        assertTrue(removed.isEmpty());
        assertTrue(!result.any() && result.waiting().isEmpty());
    }
}
