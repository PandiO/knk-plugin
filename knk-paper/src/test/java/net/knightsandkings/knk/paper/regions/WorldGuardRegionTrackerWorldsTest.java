package net.knightsandkings.knk.paper.regions;

import net.knightsandkings.knk.core.regions.RegionDomainResolver;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import net.knightsandkings.knk.core.regions.RegionTransitionDecision;
import net.knightsandkings.knk.core.regions.SimpleRegionTransitionService;
import net.knightsandkings.knk.paper.events.OnRegionEnterEvent;
import net.knightsandkings.knk.paper.events.OnRegionLeaveEvent;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KNG-112: a hub world and a gameplay world both have a region {@code town_1} at the same coordinates, each belonging to
 * its own town. Moving between the worlds leaves one town and enters the other, and each world's region resolves to its
 * own world's domain.
 */
class WorldGuardRegionTrackerWorldsTest {

    private static final String REGION = "town_1";

    // Location only holds its World through a WeakReference: keep the mocks strongly reachable.
    private final World hub = mock(World.class);
    private final World gameplay = mock(World.class);
    private final RegionDomainResolver resolver = new RegionDomainResolver();
    private final List<Event> events = new ArrayList<>();
    private WorldGuardRegionTracker tracker;

    @BeforeEach
    void setUp() {
        when(hub.getName()).thenReturn("hub");
        when(gameplay.getName()).thenReturn("gameplay");
        resolver.registerDomain("hub", town(1, "Hubtown"));
        resolver.registerDomain("gameplay", town(2, "Oakhaven"));
        // Every location with x >= 0 is inside town_1, in both worlds.
        tracker = new WorldGuardRegionTracker(location -> location.getX() >= 0 ? Set.of(REGION) : Set.of(),
            new SimpleRegionTransitionService(resolver, null, null, false), resolver, Runnable::run, Runnable::run,
            events::add, null, false);
    }

    private static DomainSnapshot town(int id, String name) {
        return new DomainSnapshot(id, name, null, REGION, true, true, "Town", Set.of(), Set.of(), Set.of(), Set.of());
    }

    private Player player(Location start) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Walker");
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(start);
        return player;
    }

    private long count(Class<? extends Event> type) {
        return events.stream().filter(type::isInstance).count();
    }

    @Test
    void eachWorldsRegionResolvesToItsOwnTown() {
        assertEquals("Hubtown", resolver.resolveRegions("hub", Set.of(REGION)).domains().iterator().next().name());
        assertEquals("Oakhaven", resolver.resolveRegions("GAMEPLAY", Set.of(REGION)).domains().iterator().next().name());
        assertTrue(resolver.getDomainByRegionIdNoRefresh("nether", REGION).isEmpty(), "never another world's domain");
        assertTrue(resolver.getDomainByRegionIdNoRefresh(REGION).isEmpty(), "world-blind: ambiguous, so no answer");
    }

    @Test
    void movingToTheSameRegionIdInAnotherWorldLeavesOneTownAndEntersTheOther() {
        Location inHub = new Location(hub, 5, 64, 5);
        Location inGameplay = new Location(gameplay, 5, 64, 5);
        Player player = player(inHub);
        tracker.handleJoin(player);
        events.clear();

        RegionTransitionDecision decision = tracker.handleMove(player, inHub, inGameplay);

        assertTrue(decision.isMovementAllowed());
        assertEquals("You are now entering Oakhaven.", decision.getMessage().orElseThrow());
        assertEquals(1, count(OnRegionLeaveEvent.class));
        assertEquals(1, count(OnRegionEnterEvent.class));
    }

    @Test
    void movingInsideOneWorldsRegionIsNoCrossing() {
        Location here = new Location(gameplay, 5, 64, 5);
        Player player = player(here);
        tracker.handleJoin(player);
        events.clear();

        assertNull(tracker.handleMove(player, here, new Location(gameplay, 6, 64, 5)));
        assertEquals(0, events.size());
    }
}
