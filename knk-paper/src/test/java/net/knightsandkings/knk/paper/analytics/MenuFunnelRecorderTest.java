package net.knightsandkings.knk.paper.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.analytics.WorldAnalyticsWindow;
import net.knightsandkings.knk.core.domain.analytics.WorldAnalyticsBatch;
import net.knightsandkings.knk.core.domain.analytics.WorldAnalyticsBatch.DomainInteraction;
import net.knightsandkings.knk.core.domain.analytics.WorldAnalyticsBatch.MenuStep;
import net.knightsandkings.knk.core.domain.analytics.WorldAnalyticsBatch.MovementCell;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrant;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrantResult;
import net.knightsandkings.knk.paper.events.OnRegionEnterEvent;
import net.knightsandkings.knk.paper.events.OnRegionLeaveEvent;
import net.knightsandkings.knk.paper.menu.MenuObserver;
import net.knightsandkings.knk.paper.menu.MenuObservers;

/**
 * KNG-34 link 7 (IMPLEMENTATION_PLAN.md §5.2): the Paper glue of world analytics - menu funnels from
 * the {@link MenuObserver} calls, heatmap samples from the timer (AFK, spectators, excluded modes and
 * dead players skipped), domain interactions from region events and discovery grants.
 */
class MenuFunnelRecorderTest {

    // Held in a field: Location keeps its world only through a WeakReference (knk-plugin CLAUDE.md).
    private final World world = mock(World.class);
    private final WorldAnalyticsWindow window =
        new WorldAnalyticsWindow(16, Clock.fixed(Instant.parse("2026-10-03T10:00:00Z"), ZoneOffset.UTC), ZoneOffset.UTC, 12);

    private WorldAnalyticsBatch drainOne() {
        List<WorldAnalyticsBatch> batches = window.drain();
        assertEquals(1, batches.size());
        return batches.get(0);
    }

    private Player player(UUID id, GameMode mode, int x, int z) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getGameMode()).thenReturn(mode);
        when(player.getLocation()).thenReturn(new Location(world, x, 64, z));
        return player;
    }

    @Test
    void menuObserverCalls_becomeFunnelSteps_throughMenuObservers() {
        MenuObservers observers = new MenuObservers();
        observers.add(new MenuFunnelRecorder(window));
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();

        observers.opened(player, "profile.main", null);
        observers.action(player, "profile.main", "menu.open", 12, MenuObserver.ActionOutcome.SUCCEEDED);
        observers.opened(player, "statistics.main", "profile.main");
        observers.action(player, "statistics.main", "statistics.period.cycle", 4, MenuObserver.ActionOutcome.DENIED);
        observers.back(player, "statistics.main", "profile.main");
        observers.action(player, "profile.main", "kits.claim", 3, MenuObserver.ActionOutcome.FAILED);
        observers.clicked(player, "profile.main", 7, "decor", "LEFT");
        observers.closed(id, "profile.main");

        assertEquals(List.of(
            new MenuStep("profile.main", "opened", "info", 1),
            new MenuStep("profile.main", "action:menu.open", "succeeded", 1),
            new MenuStep("statistics.main", "opened", "info", 1),
            new MenuStep("statistics.main", "action:statistics.period.cycle", "denied", 1),
            new MenuStep("statistics.main", "back", "info", 1),
            new MenuStep("profile.main", "action:kits.claim", "failed", 1),
            new MenuStep("profile.main", "closed", "info", 1)), drainOne().menuSteps());
        verify(player, never()).getUniqueId();
    }

    @Test
    void sampler_takesOnePositionPerEligiblePlayer() {
        UUID afk = UUID.randomUUID();
        Player walker = player(UUID.randomUUID(), GameMode.SURVIVAL, 17, -3);
        Player adventurer = player(UUID.randomUUID(), GameMode.ADVENTURE, 17, -3);
        Player idle = player(afk, GameMode.SURVIVAL, 500, 500);
        Player spectator = player(UUID.randomUUID(), GameMode.SPECTATOR, 500, 500);
        Player builder = player(UUID.randomUUID(), GameMode.CREATIVE, 500, 500);
        Player dead = player(UUID.randomUUID(), GameMode.SURVIVAL, 500, 500);
        when(dead.isDead()).thenReturn(true);
        when(world.getName()).thenReturn("world");
        List<Player> online = List.of(walker, adventurer, idle, spectator, builder, dead);

        new MovementSampler(window, () -> online, afk::equals, Set.of(GameMode.CREATIVE)).run();

        assertEquals(List.of(new MovementCell("world", 16, 1, -1, 2)), drainOne().movementCells());
    }

    @Test
    void sampler_withoutAfkSource_samplesEveryoneEligible_andSpectatorsNever() {
        Player spectator = player(UUID.randomUUID(), GameMode.SPECTATOR, 0, 0);
        Player walker = player(UUID.randomUUID(), GameMode.SURVIVAL, 0, 0);
        when(world.getName()).thenReturn("world");

        MovementSampler sampler = new MovementSampler(window, () -> List.of(spectator, walker), null, null);
        sampler.run();

        assertTrue(sampler.eligible(walker));
        assertEquals(1, drainOne().movementCells().get(0).samples());
    }

    @Test
    void regionEventsAndDiscoveries_becomeDomainInteractions() {
        DomainInteractionRecorder recorder = new DomainInteractionRecorder(window);
        UUID aliceId = UUID.randomUUID();
        Player alice = mock(Player.class);
        when(alice.getUniqueId()).thenReturn(aliceId);

        recorder.onRegionEnter(new OnRegionEnterEvent(alice, "aldmoor"));
        recorder.onRegionEnter(new OnRegionEnterEvent(alice, "aldmoor"));
        recorder.onRegionLeave(new OnRegionLeaveEvent(alice, "aldmoor"));
        DiscoveryGrant grant = new DiscoveryGrant(7, "aldmoor", "Aldmoor", "Town", null, "Walk", 1, 0, 1, 1, 0, 1);
        recorder.discoveriesGranted(alice, new DiscoveryGrantResult(List.of(grant), List.of(), List.of(),
            1, 0, 1, 1, 0, 1, List.of(), List.of(), List.of(), null, 1, 0, 1, null));
        recorder.discoveriesGranted(alice, null);

        assertEquals(List.of(
            new DomainInteraction(null, "aldmoor", "enter", 2, 1),
            new DomainInteraction(null, "aldmoor", "leave", 1, 1),
            new DomainInteraction(7, null, "discover", 1, 1)), drainOne().domainInteractions());
    }

    @Test
    void outcomeNames_matchTheApi() {
        assertEquals("succeeded", MenuFunnelRecorder.outcome(MenuObserver.ActionOutcome.SUCCEEDED));
        assertEquals("denied", MenuFunnelRecorder.outcome(MenuObserver.ActionOutcome.DENIED));
        assertEquals("failed", MenuFunnelRecorder.outcome(MenuObserver.ActionOutcome.FAILED));
        assertEquals("failed", MenuFunnelRecorder.outcome(null));
    }
}
