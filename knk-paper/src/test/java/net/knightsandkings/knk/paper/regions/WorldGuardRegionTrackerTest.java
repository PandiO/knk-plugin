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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KNG-55: a domain's AllowEntry/AllowExit denial must hold for every step, not only the first one.
 *
 * <p>The world is a line along x: x &lt; 0 is the town only, 0 &lt;= x &lt; 10 is also the
 * no-entry district, x &gt;= 10 is also an unrelated (non-domain) WorldGuard region inside it.
 * x &lt;= -100 is outside the town; -200 &lt; x &lt;= -100 is the no-exit district.
 */
class WorldGuardRegionTrackerTest {

    private static final String TOWN = "town_oakhaven";
    private static final String CLOSED = "district_closed";
    private static final String PLOT = "plot_unrelated";
    private static final String JAIL = "district_jail";

    // Location only holds its World through a WeakReference: keep the mock strongly reachable.
    private final World world = mock(World.class);
    private final RegionDomainResolver resolver = new RegionDomainResolver();
    private final SimpleRegionTransitionService transitions = new SimpleRegionTransitionService(resolver);
    private final List<Event> events = new ArrayList<>();
    private final Queue<Runnable> lookups = new ArrayDeque<>();
    private final Function<Location, Set<String>> regions = WorldGuardRegionTrackerTest::regionsAt;

    private WorldGuardRegionTracker tracker;

    @BeforeEach
    void setUp() {
        when(world.getSpawnLocation()).thenReturn(at(-500));
        resolver.registerDomain(domain(1, TOWN, "Town", true, true));
        resolver.registerDomain(domain(2, CLOSED, "District", false, true));
        resolver.registerDomain(domain(3, JAIL, "District", true, false));
        tracker = new WorldGuardRegionTracker(regions, transitions, resolver, lookups::add, Runnable::run,
            events::add, null, false);
    }

    private static Set<String> regionsAt(Location location) {
        double x = location.getX();
        Set<String> ids = new HashSet<>();
        if (x <= -200) {
            return ids;
        }
        if (x <= -100) {
            ids.add(JAIL);
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

    /** A player whose position follows teleports, like a real one. */
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

    private void moveTo(Player player, double x) {
        player.teleport(at(x));
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
    void holdingForwardIntoANoEntryDistrictIsDeniedOnEveryStep() {
        Player noble = player(-1);
        tracker.handleJoin(noble);

        for (int step = 0; step < 5; step++) {
            RegionTransitionDecision decision = tracker.handleMove(noble, at(-1), at(0));
            assertNotNull(decision, "step " + step + " must be judged");
            assertFalse(decision.isMovementAllowed(), "step " + step + " must be denied");
        }
        assertEquals(0, enterEvents(CLOSED), "a denied move enters nothing");
    }

    @Test
    void holdingForwardOutOfANoExitDistrictIsDeniedOnEveryStep() {
        Player prisoner = player(-150);
        tracker.handleJoin(prisoner);

        for (int step = 0; step < 5; step++) {
            RegionTransitionDecision decision = tracker.handleMove(prisoner, at(-150), at(-250));
            assertNotNull(decision, "step " + step + " must be judged");
            assertFalse(decision.isMovementAllowed(), "step " + step + " must be denied");
        }
    }

    @Test
    void enteringTheTownIsAllowedAndRecorded() {
        Player player = player(-250);
        tracker.handleJoin(player);

        RegionTransitionDecision decision = tracker.handleMove(player, at(-250), at(-50));

        assertTrue(decision.isMovementAllowed());
        assertEquals(1, enterEvents(TOWN));
        assertNull(tracker.handleMove(player, at(-50), at(-40)), "no border crossed");
        assertEquals(1, enterEvents(TOWN));
    }

    @Test
    void aBypassingPlayerIsLetThroughAndTracked() {
        tracker.setDenialBypass(p -> true);
        Player staff = player(-1);
        tracker.handleJoin(staff);

        RegionTransitionDecision decision = tracker.handleMove(staff, at(-1), at(0));

        assertFalse(decision.isMovementAllowed(), "the denial is still reported; the listener lets it pass");
        assertEquals(1, enterEvents(CLOSED));
        assertNull(tracker.handleMove(staff, at(0), at(1)), "inside now");
    }

    @Test
    void aMoveTheTrackerNeverSawIsJudgedFromWhereThePlayerReallyIs() {
        Player player = player(-1);
        tracker.handleJoin(player);

        // e.g. a ride, or a move another plugin cancelled after the tracker allowed it
        RegionTransitionDecision decision = tracker.handleMove(player, at(5), at(6));

        assertNull(decision, "moving inside the district is no border crossing");
        assertEquals(1, enterEvents(CLOSED), "the tracked set catches up");
    }

    @Test
    void walkingOnDuringAColdLookupIsStillEnforced() {
        RegionDomainResolver cold = new RegionDomainResolver();
        cold.registerDomain(domain(1, TOWN, "Town", true, true));
        WorldGuardRegionTracker coldTracker = new WorldGuardRegionTracker(regions,
            new SimpleRegionTransitionService(cold), cold, lookups::add, Runnable::run, events::add, null, false);
        Player noble = player(-1);
        coldTracker.handleJoin(noble);

        assertNull(coldTracker.handleMove(noble, at(-1), at(0)), "uncached: allowed while it loads");
        moveTo(noble, 12);  // walks on into the nested region before the lookup lands
        cold.registerDomain(domain(2, CLOSED, "District", false, true));  // what the API returns
        runLookups();

        assertEquals(-1, noble.getLocation().getX(), "put back where the move started");
        verify(noble).sendMessage(any(net.kyori.adventure.text.Component.class));
    }

    @Test
    void aSecondPlayerCrossingWhileTheLookupIsInFlightIsRevalidatedToo() {
        RegionDomainResolver cold = new RegionDomainResolver();
        cold.registerDomain(domain(1, TOWN, "Town", true, true));
        WorldGuardRegionTracker coldTracker = new WorldGuardRegionTracker(regions,
            new SimpleRegionTransitionService(cold), cold, lookups::add, Runnable::run, events::add, null, false);
        Player first = player(-1);
        Player second = player(-2);
        coldTracker.handleJoin(first);
        coldTracker.handleJoin(second);

        assertNull(coldTracker.handleMove(first, at(-1), at(0)));
        moveTo(first, 0);
        assertNull(coldTracker.handleMove(second, at(-2), at(1)), "lookup already in flight");
        moveTo(second, 1);
        assertEquals(1, lookups.size(), "one API lookup for both");
        cold.registerDomain(domain(2, CLOSED, "District", false, true));
        runLookups();

        assertEquals(-1, first.getLocation().getX());
        assertEquals(-2, second.getLocation().getX());
    }

    @Test
    void aNonDomainRegionAtTheBorderDoesNotWaveEveryStepThrough() {
        Player outsider = player(-1);
        tracker.handleJoin(outsider);

        // jumps straight into district + plot: the plot is unknown, so it is looked up first
        assertNull(tracker.handleMove(outsider, at(-1), at(10)));
        moveTo(outsider, 10);
        runLookups();  // the plot is no domain; the district refuses entry
        assertEquals(-1, outsider.getLocation().getX(), "revalidation put the player back");

        // the plot is on the lookup cooldown now: the next attempt is judged on the spot
        RegionTransitionDecision again = tracker.handleMove(outsider, at(-1), at(10));
        assertNotNull(again);
        assertFalse(again.isMovementAllowed());
        assertTrue(lookups.isEmpty(), "no second lookup");
    }

    @Test
    void theEnforcementTeleportOutOfANoExitDistrictIsNotJudged() {
        Player prisoner = player(-150);
        tracker.handleJoin(prisoner);
        RegionTransitionDecision[] seenDuringTeleport = new RegionTransitionDecision[1];
        Location[] position = {at(-150)};
        doAnswer(inv -> position[0].clone()).when(prisoner).getLocation();
        doAnswer(inv -> {
            Location target = inv.getArgument(0);
            // what WorldGuardRegionListener.onPlayerTeleport would do
            seenDuringTeleport[0] = tracker.handleMove(prisoner, position[0], target);
            position[0] = target.clone();
            return true;
        }).when(prisoner).teleport(any(Location.class));

        tracker.enforcementTeleport(prisoner, at(-500));

        assertNull(seenDuringTeleport[0]);
        assertEquals(-500, prisoner.getLocation().getX());
        assertNull(tracker.handleMove(prisoner, at(-500), at(-499)), "tracked outside the jail now");
    }

    @Test
    void theListenerCancelsEveryStepOfAHeldForwardKey() {
        WorldGuardRegionListener listener = new WorldGuardRegionListener(tracker);
        Player noble = player(-1);
        tracker.handleJoin(noble);

        for (int step = 0; step < 5; step++) {
            PlayerMoveEvent event = new PlayerMoveEvent(noble, at(-1), at(0));
            listener.onPlayerMove(event);
            assertTrue(event.isCancelled(), "step " + step + " must be cancelled");
        }
    }

    @Test
    void aRiderIsTakenOffAndPutBackWhenTheRideEntersANoEntryDistrict() {
        WorldGuardRegionListener listener = new WorldGuardRegionListener(tracker);
        Player rider = player(-1);
        when(rider.isInsideVehicle()).thenReturn(true);
        tracker.handleJoin(rider);
        Vehicle horse = mock(Vehicle.class);
        List<org.bukkit.entity.Entity> passengers = new ArrayList<>(List.of(rider));
        when(horse.getPassengers()).thenAnswer(inv -> List.copyOf(passengers));
        when(horse.removePassenger(rider)).thenAnswer(inv -> passengers.remove(rider));

        listener.onVehicleMove(new VehicleMoveEvent(horse, at(-1), at(0)));

        verify(horse).removePassenger(rider);
        verify(horse).teleport(at(-1));
        assertEquals(-1, rider.getLocation().getX());
        assertEquals(0, enterEvents(CLOSED));
    }

    @Test
    void anAllowedRideIsRecorded() {
        WorldGuardRegionListener listener = new WorldGuardRegionListener(tracker);
        Player rider = player(-250);
        tracker.handleJoin(rider);
        Vehicle horse = mock(Vehicle.class);
        when(horse.getPassengers()).thenReturn(List.of(rider));

        listener.onVehicleMove(new VehicleMoveEvent(horse, at(-250), at(-50)));

        verify(horse, never()).removePassenger(any());
        assertEquals(1, enterEvents(TOWN));
    }
}
