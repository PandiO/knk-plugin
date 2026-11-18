package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.navigation.NavigationEffect.EndReason;
import net.knightsandkings.knk.core.navigation.compass.CompassItemRule;
import net.knightsandkings.knk.core.navigation.compass.CompassSettings;
import net.knightsandkings.knk.core.navigation.destinations.Candidate;
import net.knightsandkings.knk.core.navigation.destinations.InitialScopes;
import net.knightsandkings.knk.core.navigation.destinations.Provider;
import net.knightsandkings.knk.core.navigation.engine.LandmarkProvider;
import net.knightsandkings.knk.core.navigation.model.Landmark;
import net.knightsandkings.knk.core.navigation.model.NavigationModel;
import net.knightsandkings.knk.core.navigation.model.NavigationModelHolder;
import net.knightsandkings.knk.core.roads.route.CompositeAccessPolicy;
import net.knightsandkings.knk.core.roads.route.StaticFlagsAvailability;
import net.knightsandkings.knk.paper.config.NavigationConfig;
import net.knightsandkings.knk.paper.events.NavigationArriveEvent;
import net.knightsandkings.knk.paper.events.NavigationEndEvent;
import net.knightsandkings.knk.paper.navigation.engine.PlayerNavigationEngine;
import net.knightsandkings.knk.paper.siege.SiegeService;

/**
 * Landmarks in a running session (off-road phase 5.1, PandiO/knk-workspace#708, A13; destination engine § 8): the real
 * {@link LandmarkProvider} over a {@link NavigationModelHolder}, wired as {@code KnKPlugin.startDestinationEngine} does
 * with {@code navigation.landmarks} on. A landmark session survives re-validation while the landmark exists and stays
 * enabled (a rename included), and ends with {@link EndReason#DESTINATION_NOT_ALLOWED} once a model refresh deletes or
 * disables it. A Radius extent is arrived at inside its cylinder, a landmark without one within {@code arrive-distance}.
 * MockBukkit on the tiny network of {@link NavigationTestNetwork}: the landmark sits at node B (100, 64, 0).
 */
class NavigationServiceLandmarkTest {

    private static final String WORLD = NavigationTestNetwork.WORLD;

    private ServerMock server;
    private World world;
    private PlayerMock player;
    private final NavigationTestNetwork network = new NavigationTestNetwork();
    private final AtomicLong tick = new AtomicLong();
    private final List<Event> events = new ArrayList<>();
    private final NavigationModelHolder holder = new NavigationModelHolder();
    private NavigationService service;
    private PlayerNavigationEngine engine;

    private static Landmark atB(String name, Landmark.Extent extent, Double radius, boolean enabled) {
        return new Landmark(41, name, WORLD, 100.5, 65, 0.5, extent, null, radius, 9, "Kardenna", enabled);
    }

    private static NavigationModel model(Landmark... landmarks) {
        return new NavigationModel(Instant.now(), List.of(), List.of(), null, List.of(landmarks));
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld(WORLD);
        player = server.addPlayer();
        player.teleport(new Location(world, 0.5, 65, 0.5));
        holder.replace(model(atB("Throne room", Landmark.Extent.NONE, null, true)));
        CompassSettings compass = new CompassSettings(CompassItemRule.defaults(), true, true, List.of());
        SiegeService siege = mock(SiegeService.class);
        when(siege.runningMatchOf(player.getUniqueId())).thenReturn(Optional.empty());
        when(siege.lobbyOf(player.getUniqueId())).thenReturn(Optional.empty());
        engine = PlayerNavigationEngine.create(List.of(new LandmarkProvider(holder::current)),
            Provider.of("siege-match", ctx -> List.of()),
            new PlayerNavigationEngine.Sources(uuid -> false, uuid -> false, p -> ActiveMode.NONE,
                (p, node) -> Set.of(InitialScopes.NAVIGATE_NODE).contains(node), () -> siege, () -> compass,
                holder::version, () -> null));
        NavigationService.PolicyFactory policies = (p, snapshot) -> CompositeAccessPolicy.of(new StaticFlagsAvailability());
        NavigationEligibility eligibility = new NavigationEligibility(uuid -> false, uuid -> false,
            NavigationEligibility.siegeMembersOutsideMatch(() -> siege));
        TrailRenderer trail = mock(TrailRenderer.class);
        when(trail.periodTicks()).thenReturn(10);
        service = new NavigationService(new NavigationService.Deps(null, NavigationConfig.defaults(),
            w -> network.snapshot, policies, (w, regionId) -> Optional.empty(), eligibility, mock(NavigationHud.class),
            trail, Runnable::run, Runnable::run, tick::get, events::add, Logger.getLogger("test")));
        // as KnKPlugin.startDestinationEngine wires it
        service.setHandleOnly(true);
        service.setRevalidator(engine::revalidate);
        holder.onChange(m -> service.revalidateSoon());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void navigateTo(String name) {
        var resolution = engine.resolve(player, name, Candidate.Mode.DEFAULT);
        assertTrue(resolution.found(), name + ": " + resolution.outcome());
        service.navigate(player, resolution.destination());
    }

    private void nextTick() {
        tick.incrementAndGet();
        service.tickAll();
    }

    private void pastTheRecheck() {
        tick.addAndGet(NavigationService.RECHECK_TICKS + 1);
        service.tickAll();
    }

    private boolean navigating() {
        return service.isNavigating(player.getUniqueId());
    }

    private EndReason endReason() {
        NavigationEndEvent ended = null;
        for (Event event : events) {
            if (event instanceof NavigationEndEvent end) {
                ended = end;
            }
        }
        assertNotNull(ended, "no NavigationEndEvent");
        return ended.getReason();
    }

    private boolean arrived() {
        return events.stream().anyMatch(NavigationArriveEvent.class::isInstance);
    }

    @Test
    void aLandmarkSessionSurvivesReValidationAndARename() {
        navigateTo("Throne room");
        assertTrue(navigating());
        pastTheRecheck();
        assertTrue(navigating());

        holder.replace(model(atB("King's hall", Landmark.Extent.NONE, null, true)));
        nextTick();
        pastTheRecheck();
        assertTrue(navigating(), "the key is the id: a rename keeps the session");
    }

    @Test
    void deletingTheLandmarkEndsTheSessionWithDestinationNotAllowed() {
        navigateTo("Throne room");
        holder.replace(model());
        nextTick();
        assertFalse(navigating());
        assertEquals(EndReason.DESTINATION_NOT_ALLOWED, endReason());
    }

    @Test
    void disablingTheLandmarkEndsTheSessionWithDestinationNotAllowed() {
        navigateTo("Throne room");
        holder.replace(model(atB("Throne room", Landmark.Extent.NONE, null, false)));
        nextTick();
        assertFalse(navigating());
        assertEquals(EndReason.DESTINATION_NOT_ALLOWED, endReason());
    }

    @Test
    void aRadiusExtentIsReachedInsideItsCylinder() {
        holder.replace(model(atB("Market square", Landmark.Extent.RADIUS, 10.0, true)));
        navigateTo("Market square");
        assertTrue(navigating());

        player.teleport(new Location(world, 92.5, 65, 0.5)); // 8 blocks from the pin, inside the radius
        nextTick();

        assertFalse(navigating());
        assertTrue(arrived(), "arrived inside the radius");
    }

    @Test
    void withoutAnExtentEightBlocksAwayIsNotThereYet() {
        navigateTo("Throne room");
        player.teleport(new Location(world, 92.5, 65, 0.5));
        nextTick();
        assertTrue(navigating(), "arrive-distance is 4");
        assertFalse(arrived());
    }

    @Test
    void standingInsideTheRadiusIsAlreadyThere() {
        holder.replace(model(atB("Market square", Landmark.Extent.RADIUS, 10.0, true)));
        player.teleport(new Location(world, 95.5, 65, 0.5));
        navigateTo("Market square");
        assertFalse(navigating(), "no session: already there");
    }
}
