package net.knightsandkings.knk.paper.regions;

import net.knightsandkings.knk.core.regions.RegionDomainResolver;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import net.knightsandkings.knk.core.regions.RegionTransitionDecision;
import net.knightsandkings.knk.core.regions.SimpleRegionTransitionService;
import net.knightsandkings.knk.paper.events.OnRegionEnterEvent;
import net.knightsandkings.knk.paper.listeners.WorldGuardRegionListener;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The region tracker describes moves WorldGuard allowed (messages, gates, region events); since KNG-56
 * it never refuses one - {@code regions.access} does, from flags on the regions. Keeps KNG-55's
 * bookkeeping: moves are described from where the player really is, and lookups don't leak.
 *
 * <p>The world is a line along x: x &lt; 0 is the town only, 0 &lt;= x &lt; 10 is also the
 * closed district, x &gt;= 10 is also an unrelated (non-domain) WorldGuard region inside it.
 */
class WorldGuardRegionTrackerTest {

    private static final String TOWN = "town_oakhaven";
    private static final String CLOSED = "district_closed";
    private static final String PLOT = "plot_unrelated";

    // Location only holds its World through a WeakReference: keep the mock strongly reachable.
    private final World world = mock(World.class);
    private final RegionDomainResolver resolver = new RegionDomainResolver();
    private final List<Event> events = new ArrayList<>();
    private final Queue<Runnable> lookups = new ArrayDeque<>();
    private final Function<Location, Set<String>> regions = WorldGuardRegionTrackerTest::regionsAt;

    private WorldGuardRegionTracker tracker;

    @BeforeEach
    void setUp() {
        resolver.registerDomain(domain(1, TOWN, "Town", true, true));
        resolver.registerDomain(domain(2, CLOSED, "District", false, true));
        tracker = tracker(resolver);
    }

    /** As in production: the transition service only describes (accessChecks=false). */
    private WorldGuardRegionTracker tracker(RegionDomainResolver domains) {
        return new WorldGuardRegionTracker(regions, new SimpleRegionTransitionService(domains, null, null, false),
            domains, lookups::add, Runnable::run, events::add, null, false);
    }

    private static Set<String> regionsAt(Location location) {
        double x = location.getX();
        Set<String> ids = new HashSet<>();
        if (x <= -100) {
            return ids;
        }
        ids.add(TOWN);
        if (x >= 0) {
            ids.add(CLOSED);
        }
        if (x >= 10) {
            ids.add(PLOT);
        }
        return ids;
    }

    private static DomainSnapshot domain(int id, String regionId, String type, boolean allowEntry, boolean allowExit) {
        return new DomainSnapshot(id, regionId, null, regionId, allowEntry, allowExit, type,
            Set.of(), Set.of(), Set.of(), Set.of());
    }

    private Location at(double x) {
        return new Location(world, x, 64, 0);
    }

    private Player player(double x) {
        Player player = mock(Player.class);
        Location[] position = {at(x)};
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Noble");
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenAnswer(inv -> position[0].clone());
        when(player.teleport(any(Location.class))).thenAnswer(inv -> {
            position[0] = ((Location) inv.getArgument(0)).clone();
            return true;
        });
        return player;
    }

    private void runLookups() {
        while (!lookups.isEmpty()) {
            lookups.poll().run();
        }
    }

    private long enterEvents(String regionId) {
        return events.stream()
            .filter(e -> e instanceof OnRegionEnterEvent enter && enter.getRegionId().equals(regionId))
            .count();
    }

    @Test
    void anEnteredDomainIsDescribedOnceWithItsEnterEvent() {
        Player player = player(-150);
        tracker.handleJoin(player);

        RegionTransitionDecision decision = tracker.handleMove(player, at(-150), at(-50));

        assertTrue(decision.isMovementAllowed());
        assertEquals("You are now entering " + TOWN + ".", decision.getMessage().orElseThrow());
        assertEquals(1, enterEvents(TOWN));
        assertNull(tracker.handleMove(player, at(-50), at(-40)), "no border crossed");
        assertEquals(1, enterEvents(TOWN));
    }

    @Test
    void aMoveWorldGuardAllowedIntoAClosedDistrictIsDescribedNotRefused() {
        // e.g. a resident (region member) of the closed district: WorldGuard let them in.
        Player resident = player(-1);
        tracker.handleJoin(resident);

        RegionTransitionDecision decision = tracker.handleMove(resident, at(-1), at(0));

        assertTrue(decision.isMovementAllowed());
        assertEquals(1, enterEvents(CLOSED));
    }

    @Test
    void aMoveTheTrackerNeverSawIsDescribedFromWhereThePlayerReallyIs() {
        Player player = player(-1);
        tracker.handleJoin(player);

        // e.g. a ride, or a move another plugin cancelled after the tracker saw it
        assertNull(tracker.handleMove(player, at(5), at(6)), "moving inside the district is no border crossing");
        assertEquals(1, enterEvents(CLOSED), "the tracked set catches up");
    }

    @Test
    void oneLookupServesEveryPlayerCrossingWhileItIsInFlight() {
        RegionDomainResolver cold = new RegionDomainResolver();
        cold.registerDomain(domain(1, TOWN, "Town", true, true));
        WorldGuardRegionTracker coldTracker = tracker(cold);
        Player first = player(-1);
        Player second = player(-2);
        coldTracker.handleJoin(first);
        coldTracker.handleJoin(second);

        assertNull(coldTracker.handleMove(first, at(-1), at(0)), "uncached: described once it loads");
        first.teleport(at(0));
        assertNull(coldTracker.handleMove(second, at(-2), at(1)));
        second.teleport(at(1));
        assertEquals(1, lookups.size(), "one API lookup for both");
        cold.registerDomain(domain(2, CLOSED, "District", true, true));
        runLookups();

        verify(first, atLeastOnce()).sendActionBar(any(net.kyori.adventure.text.Component.class));
        verify(second, atLeastOnce()).sendActionBar(any(net.kyori.adventure.text.Component.class));
    }

    @Test
    void aRegionWithoutADomainIsNotLookedUpOnEveryCrossing() {
        Player player = player(-1);
        tracker.handleJoin(player);

        tracker.handleMove(player, at(-1), at(10));  // the plot is unknown
        runLookups();  // ... and turns out to be no domain
        tracker.handleMove(player, at(10), at(-1));
        tracker.handleMove(player, at(-1), at(10));

        assertTrue(lookups.isEmpty(), "on the cooldown: no second lookup");
    }

    @Test
    void theListenerNeverCancelsAMove() {
        WorldGuardRegionListener listener = new WorldGuardRegionListener(tracker);
        Player player = player(-1);
        tracker.handleJoin(player);

        PlayerMoveEvent event = new PlayerMoveEvent(player, at(-1), at(0));
        listener.onPlayerMove(event);

        assertFalse(event.isCancelled());
        assertEquals(1, enterEvents(CLOSED));
    }

    @Test
    void aRideIsDescribedForItsRider() {
        WorldGuardRegionListener listener = new WorldGuardRegionListener(tracker);
        Player rider = player(-150);
        tracker.handleJoin(rider);
        Vehicle horse = mock(Vehicle.class);
        when(horse.getPassengers()).thenReturn(List.of(rider));

        listener.onVehicleMove(new VehicleMoveEvent(horse, at(-150), at(-50)));

        assertEquals(1, enterEvents(TOWN));
    }
}
