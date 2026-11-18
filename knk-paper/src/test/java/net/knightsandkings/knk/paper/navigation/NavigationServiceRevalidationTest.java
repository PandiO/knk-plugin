package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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

import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.navigation.NavigationEffect.EndReason;
import net.knightsandkings.knk.core.navigation.compass.CompassItemRule;
import net.knightsandkings.knk.core.navigation.compass.CompassSettings;
import net.knightsandkings.knk.core.navigation.destinations.AllowedDestination;
import net.knightsandkings.knk.core.navigation.destinations.Candidate;
import net.knightsandkings.knk.core.navigation.destinations.Candidate.Place;
import net.knightsandkings.knk.core.navigation.destinations.InitialScopes;
import net.knightsandkings.knk.core.navigation.destinations.Provider;
import net.knightsandkings.knk.core.navigation.model.NavigationModel;
import net.knightsandkings.knk.core.navigation.model.NavigationModelHolder;
import net.knightsandkings.knk.core.roads.route.CompositeAccessPolicy;
import net.knightsandkings.knk.core.roads.route.StaticFlagsAvailability;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster;
import net.knightsandkings.knk.core.siege.SiegePhase;
import net.knightsandkings.knk.paper.config.NavigationConfig;
import net.knightsandkings.knk.paper.events.NavigationEndEvent;
import net.knightsandkings.knk.paper.navigation.engine.PlayerNavigationEngine;
import net.knightsandkings.knk.paper.siege.SiegeLobbyRuntime;
import net.knightsandkings.knk.paper.siege.SiegeMatch;
import net.knightsandkings.knk.paper.siege.SiegeService;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Live re-validation of running sessions (destination engine phase 4, PandiO/knk-workspace#62, DESIGN § 8, A22–A26):
 * with {@code navigation.destination-engine} on, a session started from a handle is re-checked against the player's
 * list every {@link NavigationService#RECHECK_TICKS} and at once (the next tick) on a siege event, a mode change, a
 * freeze change and a model refresh; a destination that left the list ends the session with
 * {@link EndReason#DESTINATION_NOT_ALLOWED} and the scope's reason. With the flag off the siege rule of
 * {@link NavigationEligibility} ends sessions as before ({@link EndReason#SIEGE}). A MockBukkit world and player on
 * the tiny network of {@link NavigationTestNetwork}, the real engine with the initial scope table.
 */
class NavigationServiceRevalidationTest {

    private static final String WORLD = NavigationTestNetwork.WORLD;
    private static final int TEAM = 2;

    private ServerMock server;
    private PlayerMock player;
    private final NavigationTestNetwork network = new NavigationTestNetwork();
    private final AtomicBoolean frozen = new AtomicBoolean();
    private final AtomicBoolean loading = new AtomicBoolean();
    private final AtomicBoolean inMatch = new AtomicBoolean();
    private final AtomicBoolean inLobby = new AtomicBoolean();
    private final AtomicReference<ActiveMode> mode = new AtomicReference<>(ActiveMode.NONE);
    private final Set<String> nodes = new HashSet<>(Set.of(InitialScopes.NAVIGATE_NODE));
    private final AtomicLong tick = new AtomicLong();
    private final List<Event> events = new ArrayList<>();
    private final SiegeService siege = mock(SiegeService.class);
    private final SiegeMatch runningMatch = mock(SiegeMatch.class);
    private final SiegeLobbyRuntime lobby = mock(SiegeLobbyRuntime.class);
    private NavigationService service;
    private PlayerNavigationEngine engine;

    private final Candidate cinixKeep = node("node:" + NavigationTestNetwork.C, "Cinix Keep", "node", (int) NavigationTestNetwork.C,
        null, "catalogue");
    private final Candidate mainStreet = new Candidate("street:" + NavigationTestNetwork.STREET_MAIN, "Main Street", "street",
        List.of(), NavigationTestNetwork.STREET_MAIN, null, 1, "catalogue",
        new Place.Street(WORLD, NavigationTestNetwork.STREET_MAIN));
    private final List<Candidate> catalogue = new ArrayList<>(List.of(cinixKeep, mainStreet));
    private final List<Candidate> match = new ArrayList<>(List.of(
        node("objective:7", "Keep Gate", "objective", 7, "capture", "siege-match")));

    private static Candidate node(String key, String label, String type, int id, String detail, String source) {
        return new Candidate(key, label, type, List.of(), id, detail, 0, source,
            new Place.Node(WORLD, NavigationTestNetwork.C, new double[] {200, 64, 0}));
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        World world = server.addSimpleWorld(WORLD);
        player = server.addPlayer();
        player.teleport(new Location(world, 0.5, 65, 0.5));
        UUID id = player.getUniqueId();
        KnkSiegeScenario scenario = mock(KnkSiegeScenario.class);
        when(scenario.id()).thenReturn(1);
        when(runningMatch.scenario()).thenReturn(scenario);
        when(runningMatch.roster()).thenReturn(new SiegeMatchRoster(Map.of(TEAM, List.of(id))));
        when(siege.runningMatchOf(id)).thenAnswer(inv -> inMatch.get() ? Optional.of(runningMatch) : Optional.empty());
        when(siege.lobbyOf(id)).thenAnswer(inv -> inMatch.get() || inLobby.get() ? Optional.of(lobby) : Optional.empty());
        when(lobby.isMember(id)).thenAnswer(inv -> inMatch.get() || inLobby.get());
        when(lobby.phase()).thenAnswer(inv -> inMatch.get() ? SiegePhase.IN_PROGRESS : SiegePhase.HUB);
        CompassSettings compass = new CompassSettings(CompassItemRule.defaults(), true, true, List.of());
        engine = PlayerNavigationEngine.create(List.of(Provider.of("catalogue", ctx -> List.copyOf(catalogue))),
            Provider.of("siege-match", ctx -> inMatch.get() ? List.copyOf(match) : List.of()),
            new PlayerNavigationEngine.Sources(uuid -> loading.get(), uuid -> frozen.get(), p -> mode.get(),
                (p, node) -> nodes.contains(node), () -> siege, () -> compass, () -> 1L, () -> null));
        NavigationService.PolicyFactory policies = (p, snapshot) -> CompositeAccessPolicy.of(new StaticFlagsAvailability());
        // today's eligibility, as KnKPlugin wires it (the flag-off path)
        NavigationEligibility eligibility = new NavigationEligibility(uuid -> loading.get(), uuid -> frozen.get(),
            NavigationEligibility.siegeMembersOutsideMatch(() -> siege));
        service = new NavigationService(new NavigationService.Deps(null, NavigationConfig.defaults(),
            w -> network.snapshot, policies, (w, regionId) -> Optional.empty(), eligibility, mock(NavigationHud.class),
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

    /** The flag on, as KnKPlugin.startDestinationEngine wires it. */
    private void engineOn() {
        service.setHandleOnly(true);
        service.setRevalidator(engine::revalidate);
    }

    private void navigateTo(String name) {
        var resolution = engine.resolve(player, name, Candidate.Mode.DEFAULT);
        assertTrue(resolution.found(), name + ": " + resolution.outcome());
        AllowedDestination handle = resolution.destination();
        service.navigate(player, handle);
        assertTrue(service.isNavigating(player.getUniqueId()), String.valueOf(lastMessage()));
        lastMessage(); // the route's start messages
    }

    /** One server tick (the immediate re-check runs at its start). */
    private void nextTick() {
        tick.incrementAndGet();
        service.tickAll();
    }

    /** Ticks past the 2-second re-check. */
    private void pastTheRecheck() {
        tick.addAndGet(NavigationService.RECHECK_TICKS + 1);
        service.tickAll();
    }

    private String lastMessage() {
        String last = null;
        net.kyori.adventure.text.Component message;
        while ((message = player.nextComponentMessage()) != null) {
            last = PlainTextComponentSerializer.plainText().serialize(message);
        }
        return last;
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

    private void assertEndedWith(String message) {
        assertFalse(service.isNavigating(player.getUniqueId()), "the session should have ended");
        assertEquals(EndReason.DESTINATION_NOT_ALLOWED, endReason());
        assertEquals("[Road] " + message, lastMessage()); // the scope's text in the command's words (E15)
    }

    private void assertStillNavigating() {
        assertTrue(service.isNavigating(player.getUniqueId()), String.valueOf(lastMessage()));
    }

    // ==================== the flag off: today's rule, pinned ====================

    @Test
    void withTheFlagOffAJoinedLobbyEndsTheSessionWithSiegeAsToday() {
        service.navigate(player, Destination.node("Cinix Keep", WORLD, NavigationTestNetwork.C, new double[] {200, 64, 0}));
        assertStillNavigating();
        inLobby.set(true);
        service.lobbyChanged(lobby);
        assertFalse(service.isNavigating(player.getUniqueId()));
        assertEquals(EndReason.SIEGE, endReason());
        assertEquals("[Road] Navigation to Cinix Keep ended - you joined a siege.", lastMessage());
    }

    @Test
    void withTheFlagOffTheRecheckEndsALobbyMemberWithSiegeAsToday() {
        service.navigate(player, Destination.node("Cinix Keep", WORLD, NavigationTestNetwork.C, new double[] {200, 64, 0}));
        inLobby.set(true);
        pastTheRecheck();
        assertFalse(service.isNavigating(player.getUniqueId()));
        assertEquals(EndReason.SIEGE, endReason());
    }

    @Test
    void withTheFlagOffAFreezeDoesNotEndARunningSessionAndTriggersDoNothing() {
        service.navigate(player, Destination.node("Cinix Keep", WORLD, NavigationTestNetwork.C, new double[] {200, 64, 0}));
        frozen.set(true);
        service.revalidateSoon();
        nextTick();
        pastTheRecheck();
        assertStillNavigating();
    }

    // ==================== the flag on: re-validation (A23, A24, A26) ====================

    @Test
    void theHandlePathNoLongerAsksTheOldEligibility() {
        engineOn();
        AllowedDestination handle = engine.resolve(player, "Cinix Keep", Candidate.Mode.DEFAULT).destination();
        frozen.set(true); // after the handle was minted: the session starts, the re-check ends it
        service.navigate(player, handle);
        assertStillNavigating();
        nextTick();
        pastTheRecheck();
        assertEndedWith("You can't navigate while frozen.");
    }

    @Test
    void aFreezeChangeEndsTheSessionAtOnceWithTheFrozenReason() {
        engineOn();
        navigateTo("Cinix Keep");
        frozen.set(true);
        service.revalidateSoon(); // the freeze hook (any thread)
        nextTick();
        assertEndedWith("You can't navigate while frozen.");
    }

    @Test
    void theTwoSecondRecheckEndsASessionWhosePermissionWasTakenAway() {
        engineOn();
        navigateTo("Cinix Keep");
        nodes.remove(InitialScopes.NAVIGATE_NODE);
        nextTick();
        assertStillNavigating(); // no trigger: waits for the 2-second re-check
        pastTheRecheck();
        assertEndedWith("You don't have permission to use /navigate.");
    }

    @Test
    void joiningALobbyEndsTheSessionWithTheLobbysReasonNeverSiege() {
        engineOn();
        navigateTo("Cinix Keep");
        inLobby.set(true);
        service.lobbyChanged(lobby);
        assertStillNavigating(); // the next tick runs the re-check, once the lobby change has settled
        nextTick();
        assertEndedWith("You can't navigate while you are in a siege.");
        assertTrue(events.stream().noneMatch(e -> e instanceof NavigationEndEvent end && end.getReason() == EndReason.SIEGE));
    }

    @Test
    void theRecheckNeverEndsWithSiegeWithTheFlagOn() {
        engineOn();
        navigateTo("Cinix Keep");
        inLobby.set(true);
        pastTheRecheck();
        assertEndedWith("You can't navigate while you are in a siege.");
    }

    @Test
    void aDomainRemovedByAModelRefreshEndsTheSession() {
        engineOn();
        NavigationModelHolder holder = new NavigationModelHolder();
        holder.onChange(model -> service.revalidateSoon()); // as KnKPlugin.startDestinationEngine wires it
        navigateTo("Cinix Keep");
        catalogue.remove(cinixKeep);
        // the refresh swaps in a changed model (on the refresh thread): the listener asks for the re-check
        assertTrue(holder.replace(new NavigationModel(java.time.Instant.now(), List.of(), List.of(), null, List.of())));
        nextTick();
        assertEndedWith("Cinix Keep is no longer part of the world.");
    }

    @Test
    void aCandidateStillListedKeepsTheSessionAfterATrigger() {
        engineOn();
        navigateTo("Cinix Keep");
        catalogue.remove(mainStreet); // the list changed, the destination did not leave it
        service.revalidateSoon();
        nextTick();
        pastTheRecheck();
        assertStillNavigating();
    }

    @Test
    void anObjectiveThatChangesKindButStaysListedKeepsTheSession() {
        engineOn();
        inMatch.set(true);
        navigateTo("Keep Gate");
        match.set(0, node("objective:7", "Keep Gate", "objective", 7, "defend", "siege-match"));
        service.objectiveCaptured(lobby, runningMatch, null);
        nextTick();
        assertStillNavigating();
    }

    @Test
    void anObjectiveDroppedOnCaptureEndsTheSessionAtOnce() {
        engineOn();
        inMatch.set(true);
        match.add(node("objective:8", "Outer Wall", "objective", 8, "capture", "siege-match"));
        navigateTo("Keep Gate");
        match.remove(0);
        service.objectiveCaptured(lobby, runningMatch, null);
        nextTick();
        assertEndedWith("Keep Gate is no longer part of this match.");
    }

    @Test
    void theMatchEndingEndsTheSessionWithTheScopeThePlayerIsInNow() {
        engineOn();
        inMatch.set(true);
        navigateTo("Keep Gate");
        inMatch.set(false);
        inLobby.set(true); // back at the hub, waiting to be restored
        service.matchEnded(lobby, runningMatch);
        nextTick();
        assertEndedWith("You can't navigate while you are in a siege.");
    }

    @Test
    void aReleasedRoundReChecksAtOnce() {
        engineOn();
        inMatch.set(true);
        match.add(node("objective:8", "Outer Wall", "objective", 8, "capture", "siege-match"));
        navigateTo("Keep Gate");
        match.remove(0);
        service.roundReleased(lobby);
        nextTick();
        assertEndedWith("Keep Gate is no longer part of this match.");
    }

    @Test
    void aLobbyChangeDuringTheMatchKeepsTheMatchSession() {
        engineOn();
        inMatch.set(true);
        navigateTo("Keep Gate");
        service.lobbyChanged(lobby); // a vote, someone leaving
        nextTick();
        pastTheRecheck();
        assertStillNavigating();
    }

    // ==================== owner/staff mode and freeze (A25, E9) ====================

    @Test
    void switchingStaffModeOffInAMatchEndsATownSessionAtOnce() {
        engineOn();
        inMatch.set(true);
        mode.set(ActiveMode.STAFF);
        navigateTo("Cinix Keep"); // the bypass lists the world in a match
        mode.set(ActiveMode.NONE);
        service.revalidateSoon(); // the mode listener
        nextTick();
        assertEndedWith("Cinix Keep is no longer part of this match.");
    }

    @Test
    void switchingStaffModeOnAppliesTheBypassAndTheSessionKeepsGoing() {
        engineOn();
        navigateTo("Cinix Keep");
        mode.set(ActiveMode.STAFF);
        inMatch.set(true); // without the bypass the town would leave the list
        service.revalidateSoon();
        nextTick();
        pastTheRecheck();
        assertStillNavigating();
    }

    @Test
    void freezeStillEndsAStaffSession() {
        engineOn();
        mode.set(ActiveMode.STAFF);
        navigateTo("Cinix Keep");
        frozen.set(true);
        service.revalidateSoon();
        nextTick();
        assertEndedWith("You can't navigate while frozen.");
    }

    @Test
    void freezeStillEndsABypassNodeSession() {
        engineOn();
        nodes.add(InitialScopes.BYPASS_NODE);
        inMatch.set(true);
        navigateTo("Cinix Keep");
        frozen.set(true);
        service.revalidateSoon();
        nextTick();
        assertEndedWith("You can't navigate while frozen.");
    }

    // ==================== robustness ====================

    @Test
    void aFailingCheckKeepsTheSession() {
        service.setHandleOnly(true);
        service.setRevalidator((p, handle) -> {
            throw new IllegalStateException("boom");
        });
        navigateTo("Cinix Keep");
        service.revalidateSoon();
        nextTick();
        pastTheRecheck();
        assertStillNavigating();
    }

    @Test
    void theNotAllowedEndHasItsOwnGenericText() {
        assertEquals("[Road] Navigation to Cinix Keep ended - you can't navigate there now.", PlainTextComponentSerializer.plainText()
            .serialize(NavigationMessages.ended(EndReason.DESTINATION_NOT_ALLOWED, "Cinix Keep")));
    }
}
