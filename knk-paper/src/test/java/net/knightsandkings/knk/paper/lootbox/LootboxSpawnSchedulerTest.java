package net.knightsandkings.knk.paper.lootbox;

import net.knightsandkings.knk.core.lootbox.KnkLootboxArea;
import net.knightsandkings.knk.core.lootbox.LootboxSpawnPlanner;
import net.knightsandkings.knk.core.ports.api.LootboxesCommandApi;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Lootboxes x siege: the region side of a spawn spot (area region, excluded regions, siege arenas). */
class LootboxSpawnSchedulerTest {

    private final World world = mock(World.class);
    private final LootboxRegions regions = mock(LootboxRegions.class);
    private final Set<String> arenas = new HashSet<>();
    private LootboxSpawnScheduler scheduler;

    private static KnkLootboxArea area(List<String> excluded) {
        return new KnkLootboxArea(1, "spawn", "world", "lootbox_spawn", true, 3, 600, 100, 3, 24, 30, excluded, List.of(), 0, null);
    }

    @BeforeEach
    void setUp() {
        when(regions.contains(eq(world), eq("lootbox_spawn"), anyInt(), anyInt(), anyInt())).thenReturn(true);
        when(regions.regionIdsAt(eq(world), anyInt(), anyInt(), anyInt())).thenReturn(Set.of("lootbox_spawn", "domain_10"));
        scheduler = new LootboxSpawnScheduler(mock(Plugin.class), mock(LootboxRuntime.class), regions,
                mock(LootboxesCommandApi.class), mock(LootboxAnnouncer.class), new LootboxSpawnPlanner(() -> 0.5), () -> arenas);
    }

    @Test
    void aSpotInTheArea_withNoSiegeRunning_isAllowed() {
        assertTrue(scheduler.regionsAllow(world, area(List.of()), 10, 64, 10));
    }

    @Test
    void aSpotInsideARunningSiegesArea_isRefused() {
        arenas.add("domain_10");

        assertFalse(scheduler.regionsAllow(world, area(List.of()), 10, 64, 10));
    }

    @Test
    void aSiegeElsewhere_doesNotBlockTheSpot() {
        arenas.add("domain_99");

        assertTrue(scheduler.regionsAllow(world, area(List.of()), 10, 64, 10));
    }

    @Test
    void excludedRegions_stillApply() {
        assertFalse(scheduler.regionsAllow(world, area(List.of("domain_10")), 10, 64, 10));
    }

    @Test
    void outsideTheAreasRegion_isRefused() {
        when(regions.contains(eq(world), eq("lootbox_spawn"), anyInt(), anyInt(), anyInt())).thenReturn(false);

        assertFalse(scheduler.regionsAllow(world, area(List.of()), 10, 64, 10));
    }
}
