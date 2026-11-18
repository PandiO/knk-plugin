package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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

import net.knightsandkings.knk.core.navigation.destinations.AllowedDestination;
import net.knightsandkings.knk.core.navigation.destinations.Candidate;
import net.knightsandkings.knk.core.navigation.destinations.Candidate.Place;
import net.knightsandkings.knk.core.navigation.destinations.DestinationEngine;
import net.knightsandkings.knk.core.navigation.destinations.InitialScopes;
import net.knightsandkings.knk.core.navigation.destinations.NavigationContext;
import net.knightsandkings.knk.core.navigation.destinations.Provider;
import net.knightsandkings.knk.core.roads.route.CompositeAccessPolicy;
import net.knightsandkings.knk.core.roads.route.StaticFlagsAvailability;
import net.knightsandkings.knk.paper.config.NavigationConfig;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * {@code NavigationService.navigate(Player, AllowedDestination)} (phase 3, PandiO/knk-workspace#69, DESIGN § 6, E3,
 * A20): a handle starts a session; with {@code navigation.destination-engine} on the raw entry refuses; a handle of
 * another player is refused; the hard blocks still apply until phase 4. A MockBukkit world and player on the tiny
 * network of {@link NavigationTestNetwork}.
 */
class NavigationServiceHandleTest {

    private ServerMock server;
    private World world;
    private PlayerMock player;
    private final NavigationTestNetwork network = new NavigationTestNetwork();
    private final AtomicBoolean frozen = new AtomicBoolean();
    private final AtomicLong tick = new AtomicLong();
    private final List<Event> events = new ArrayList<>();
    private NavigationService service;

    private final Candidate mainStreet = new Candidate("street:" + NavigationTestNetwork.STREET_MAIN, "Main Street", "street",
        List.of(), NavigationTestNetwork.STREET_MAIN, null, 0, "catalogue",
        new Place.Street(NavigationTestNetwork.WORLD, NavigationTestNetwork.STREET_MAIN));
    private final Candidate cinixKeep = new Candidate("node:" + NavigationTestNetwork.C, "Cinix Keep", "node", List.of(),
        NavigationTestNetwork.C, null, 0, "catalogue",
        new Place.Node(NavigationTestNetwork.WORLD, NavigationTestNetwork.C, new double[] {200, 64, 0}));
    private final DestinationEngine engine = new DestinationEngine(InitialScopes.create(
        List.of(Provider.fixed("catalogue", List.of(mainStreet, cinixKeep))), Provider.fixed("siege-match", List.of())));

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld(NavigationTestNetwork.WORLD);
        player = server.addPlayer();
        player.teleport(new Location(world, 0.5, 65, 0.5));
        NavigationService.PolicyFactory policies = (p, snapshot) -> CompositeAccessPolicy.of(new StaticFlagsAvailability());
        NavigationEligibility eligibility = new NavigationEligibility(uuid -> false, uuid -> frozen.get(), uuid -> false);
        service = new NavigationService(new NavigationService.Deps(null, NavigationConfig.defaults(),
            w -> network.snapshot, policies, (w, id) -> Optional.empty(), eligibility, mock(NavigationHud.class),
            trail(), Runnable::run, Runnable::run, tick::get, events::add, Logger.getLogger("test")));
    }

    private static TrailRenderer trail() {
        TrailRenderer trail = mock(TrailRenderer.class);
        when(trail.periodTicks()).thenReturn(10);
        return trail;
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private AllowedDestination handleFor(UUID who, String name) {
        NavigationContext ctx = NavigationContext.of(who, NavigationTestNetwork.WORLD).withPermissions(InitialScopes.NAVIGATE_NODE);
        return engine.resolve(ctx, name, Candidate.Mode.DEFAULT).destination();
    }

    private String lastMessage() {
        String last = null;
        net.kyori.adventure.text.Component message;
        while ((message = player.nextComponentMessage()) != null) {
            last = PlainTextComponentSerializer.plainText().serialize(message);
        }
        return last;
    }

    @Test
    void aHandleStartsASession() {
        service.navigate(player, handleFor(player.getUniqueId(), "Cinix Keep"));
        assertTrue(service.isNavigating(player.getUniqueId()), String.valueOf(lastMessage()));
        assertEquals("Cinix Keep", service.destinationName(player.getUniqueId()));
    }

    @Test
    void withTheFlagOnTheRawEntryRefusesAndTheHandleStillWorks() {
        service.setHandleOnly(true);
        service.navigate(player, Destination.street("Main Street", NavigationTestNetwork.WORLD, NavigationTestNetwork.STREET_MAIN));
        assertFalse(service.isNavigating(player.getUniqueId()));
        assertEquals(PlainTextComponentSerializer.plainText().serialize(NavigationMessages.disabled()), lastMessage());
        service.navigate(player, handleFor(player.getUniqueId(), "Cinix Keep"));
        assertTrue(service.isNavigating(player.getUniqueId()));
    }

    @Test
    void withTheFlagOffTheRawEntryWorksAsToday() {
        service.navigate(player, Destination.node("Cinix Keep", NavigationTestNetwork.WORLD, NavigationTestNetwork.C,
            new double[] {200, 64, 0}));
        assertTrue(service.isNavigating(player.getUniqueId()));
    }

    @Test
    void aHandleOfAnotherPlayerIsRefused() {
        service.navigate(player, handleFor(UUID.randomUUID(), "Cinix Keep"));
        assertFalse(service.isNavigating(player.getUniqueId()));
    }

    @Test
    void theHardBlocksStillApplyToAHandle() {
        frozen.set(true);
        service.navigate(player, handleFor(player.getUniqueId(), "Cinix Keep"));
        assertFalse(service.isNavigating(player.getUniqueId()));
        assertEquals(PlainTextComponentSerializer.plainText().serialize(NavigationMessages.frozen()), lastMessage());
    }
}
