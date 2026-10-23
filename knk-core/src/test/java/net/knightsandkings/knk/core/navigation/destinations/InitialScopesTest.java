package net.knightsandkings.knk.core.navigation.destinations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.navigation.destinations.Candidate.Mode;
import net.knightsandkings.knk.core.navigation.destinations.DestinationEngine.Resolution;
import net.knightsandkings.knk.core.navigation.destinations.DestinationEngine.Resolution.Outcome;
import net.knightsandkings.knk.core.navigation.destinations.DestinationEngine.Revalidation;
import net.knightsandkings.knk.core.navigation.destinations.NavigationContext.Siege;
import net.knightsandkings.knk.core.navigation.destinations.Scope.Empty;
import net.knightsandkings.knk.core.navigation.destinations.Scope.Listed;

/**
 * The initial scope table (DESIGN §4.1) over a small world catalogue and a siege match: every scope's
 * {@code applies} and yield, the priority conflicts the decisions settle (E5, E6, E9), and resolution, completion
 * and re-validation inside each scope.
 */
class InitialScopesTest {

    private final Candidate kardenna = Fixtures.town(1, "Kardenna", "world");
    private final Candidate marketTown = Fixtures.town(2, "Market", "world");
    private final Candidate marketDistrict = Fixtures.district(3, "Market", "world");
    private final Candidate keepGate = Fixtures.gate(4, "Keep Gate", "world");
    private final Candidate mill = Fixtures.location(5, "Kardenna Mill", "world");
    private final Candidate keepstreet = Fixtures.street(6, "Keepstreet", "world");
    private final Candidate farTown = Fixtures.town(7, "Far Town", "nether");
    private final Provider catalogue = Provider.fixed("catalogue",
        List.of(kardenna, marketTown, marketDistrict, keepGate, mill, keepstreet, farTown));
    private final Provider compassTargets = Provider.fixed("compass-targets", List.of(Fixtures.node(9, "Crossroads", "world")));

    /** The objectives as the siege would list them for a defender; only for a member of a running match. */
    private final Candidate gateObjective = Fixtures.objective(7, "Keep Gate", "defend, under attack, 62% held", 0);
    private final Candidate wallObjective = Fixtures.objective(8, "North Wall", "capture", 1);
    private final Candidate teamSpawn = Fixtures.teamSpawn(3, "Team spawn", 2);
    private List<Candidate> match = List.of(gateObjective, wallObjective, teamSpawn);
    private final Provider siegeMatch = Provider.of("siege-match", ctx -> ctx.siege().matchRunning() ? match : List.of());

    private final DestinationEngine engine = new DestinationEngine(InitialScopes.create(List.of(catalogue, compassTargets), siegeMatch));
    private final NavigationContext player = Fixtures.player();
    private final NavigationContext inMatch = player.withSiege(Siege.match(1, 1));

    private static List<String> keys(DestinationList list) {
        return list.candidates().stream().map(Candidate::key).toList();
    }

    // ==================== the table ====================

    @Test
    void theTableIsWalkedInTheDesignsOrder() {
        assertEquals(List.of(InitialScopes.ACCOUNT_LOADING, InitialScopes.FROZEN, InitialScopes.BYPASS,
            InitialScopes.NO_PERMISSION, InitialScopes.SIEGE_MATCH, InitialScopes.SIEGE_MATCH_COMPASS_OFF,
            InitialScopes.SIEGE_LOBBY, InitialScopes.WORLD), engine.scopes().stream().map(Scope::id).toList());
        assertEquals(List.of(110, 110, 100, 80, 60, 55, 50, 10), engine.scopes().stream().map(Scope::priority).toList());
    }

    @Test
    void theWorldScopeListsTheCatalogueForAPlainPlayer() {
        DestinationList list = engine.listFor(player);
        assertEquals(InitialScopes.WORLD, list.scopeId());
        assertEquals("the world", list.displayName());
        assertEquals(List.of("node:9", "domain:Town:1", "location:5", "domain:Structure:4", "street:6",
            "domain:District:3", "domain:Town:2"), keys(list));
        assertEquals(List.of(farTown), list.otherWorld());
        assertFalse(list.refused());
        assertFalse(engine.listFor(inMatch).contains("domain:Town:1"), "the world scope is below the match");
    }

    // ==================== loading and frozen (110) ====================

    @Test
    void aLoadingAccountGetsTheLoadingReason() {
        DestinationList list = engine.listFor(player.withLoading(true));
        assertEquals(InitialScopes.ACCOUNT_LOADING, list.scopeId());
        assertEquals(InitialScopes.LOADING_REASON, list.reason());
        assertTrue(list.isEmpty());
        assertInstanceOf(Empty.class, engine.scopes().get(0).yield(player));
    }

    @Test
    void loadingBeatsEverythingBecauseTheModeIsNotKnownYet() {
        NavigationContext loadingOwner = player.withLoading(true).withMode(ActiveMode.OWNER).withPermissions(InitialScopes.BYPASS_NODE);
        assertEquals(InitialScopes.LOADING_REASON, engine.listFor(loadingOwner).reason());
        assertEquals(InitialScopes.LOADING_REASON, engine.listFor(loadingOwner.withFrozen(true)).reason(),
            "loading and frozen share the priority: loading is checked first, as today");
    }

    @Test
    void aFrozenPlayerGetsTheFrozenReason() {
        DestinationList list = engine.listFor(player.withFrozen(true));
        assertEquals(InitialScopes.FROZEN, list.scopeId());
        assertEquals(InitialScopes.FROZEN_REASON, list.reason());
        assertEquals(InitialScopes.FROZEN_REASON, engine.listFor(inMatch.withFrozen(true)).reason(), "also in a match");
    }

    @Test
    void frozenBeatsTheBypass() {
        assertEquals(InitialScopes.FROZEN_REASON, engine.listFor(player.withFrozen(true).withMode(ActiveMode.OWNER)).reason());
        assertEquals(InitialScopes.FROZEN_REASON, engine.listFor(player.withFrozen(true).withMode(ActiveMode.STAFF)).reason());
        assertEquals(InitialScopes.FROZEN_REASON,
            engine.listFor(player.withFrozen(true).withPermissions(InitialScopes.BYPASS_NODE)).reason());
    }

    // ==================== bypass (100) ====================

    @Test
    void ownerAndStaffModeBypassEveryPolicyBelowFreeze() {
        for (ActiveMode mode : List.of(ActiveMode.OWNER, ActiveMode.STAFF)) {
            NavigationContext owner = NavigationContext.of(Fixtures.PLAYER, Fixtures.WORLD).withMode(mode);
            DestinationList list = engine.listFor(owner);
            assertEquals(InitialScopes.BYPASS, list.scopeId(), mode + " without knk.navigate still lists everything");
            assertTrue(list.contains("domain:Town:1") && list.contains("node:9"));
            assertFalse(list.contains("objective:7"), "the match provider has nothing outside a match");
            assertEquals(InitialScopes.BYPASS, engine.listFor(owner.withSiege(Siege.lobby())).scopeId());
            assertEquals(InitialScopes.BYPASS, engine.listFor(owner.withSiege(Siege.match(1, 1).withCompassEnabled(false))).scopeId());
        }
        assertEquals(InitialScopes.WORLD, engine.listFor(player.withMode(ActiveMode.NONE)).scopeId());
    }

    @Test
    void theBypassNodeDoesTheSameAsTheModes() {
        NavigationContext holder = NavigationContext.of(Fixtures.PLAYER, Fixtures.WORLD).withPermissions(InitialScopes.BYPASS_NODE);
        assertEquals(InitialScopes.BYPASS, engine.listFor(holder).scopeId());
        assertEquals(InitialScopes.BYPASS, engine.listFor(holder.withSiege(Siege.match(1, 1))).scopeId());
        assertEquals("knk.navigate.any", InitialScopes.BYPASS_NODE);
        assertEquals("knk.navigate", InitialScopes.NAVIGATE_NODE);
    }

    @Test
    void theBypassListsTheUnionOfEveryProvider() {
        DestinationList list = engine.listFor(inMatch.withMode(ActiveMode.STAFF));
        assertEquals(InitialScopes.BYPASS, list.scopeId());
        assertTrue(list.contains("domain:Town:1") && list.contains("objective:7") && list.contains("spawn:3"));
        assertEquals(List.of("node:9", "domain:Town:1", "location:5", "domain:Structure:4", "objective:7", "street:6",
            "domain:District:3", "domain:Town:2", "objective:8", "spawn:3"), keys(list),
            "candidate priority first (the objectives carry the compass order, the catalogue is all 0), then label");

        Resolution shared = engine.resolve(inMatch.withMode(ActiveMode.STAFF), "Keep Gate", Mode.DEFAULT);
        assertTrue(shared.ambiguous(), "the gate structure and the gate objective share the name");
        assertEquals(List.of("structure:Keep Gate", "objective:Keep Gate"), shared.choiceNames(), "in list order");
        assertSame(keepGate, engine.resolve(inMatch.withMode(ActiveMode.STAFF), "gate:Keep Gate", Mode.DEFAULT).candidate());
        assertSame(gateObjective, engine.resolve(inMatch.withMode(ActiveMode.STAFF), "objective:#7", Mode.DEFAULT).candidate());
    }

    // ==================== no permission (80) ====================

    @Test
    void withoutTheNodeNothingIsListed() {
        NavigationContext nobody = NavigationContext.of(Fixtures.PLAYER, Fixtures.WORLD);
        DestinationList list = engine.listFor(nobody);
        assertEquals(InitialScopes.NO_PERMISSION, list.scopeId());
        assertEquals(InitialScopes.NO_PERMISSION_REASON, list.reason());
        assertEquals(InitialScopes.NO_PERMISSION_REASON, engine.listFor(nobody.withSiege(Siege.match(1, 1))).reason(),
            "the node is checked before the match");
        assertEquals(Outcome.NOT_ALLOWED, engine.resolve(nobody, "Kardenna", Mode.DEFAULT).outcome());
        assertEquals(List.of(), engine.complete(nobody, List.of("")));
    }

    // ==================== siege match (60 / 55) ====================

    @Test
    void aRunningMatchListsOnlyTheMatch() {
        DestinationList list = engine.listFor(inMatch);
        assertEquals(InitialScopes.SIEGE_MATCH, list.scopeId());
        assertEquals("this match", list.displayName());
        assertEquals(List.of("objective:7", "objective:8", "spawn:3"), keys(list), "the compass order");
        assertEquals("defend, under attack, 62% held", list.candidates().get(0).detail());
        assertEquals(List.of(), list.otherWorld());
        assertFalse(list.refused());
    }

    @Test
    void inAMatchNamesResolveAndCompleteAgainstTheMatchOnly() {
        Resolution gate = engine.resolve(inMatch, "keep gate", Mode.DEFAULT);
        assertTrue(gate.found());
        assertSame(gateObjective, gate.candidate());
        assertEquals(InitialScopes.SIEGE_MATCH, gate.destination().scopeId());
        assertSame(wallObjective, engine.resolve(inMatch, "objective:#8", Mode.DEFAULT).candidate());
        assertSame(teamSpawn, engine.resolve(inMatch, "spawn:Team spawn", Mode.DEFAULT).candidate());

        Resolution town = engine.resolve(inMatch, "Kardenna", Mode.DEFAULT);
        assertEquals(Outcome.NOT_FOUND, town.outcome(), "the world catalogue does not exist in a match");
        assertEquals(List.of(), town.suggestions());
        assertEquals(List.of(wallObjective), engine.resolve(inMatch, "nor", Mode.DEFAULT).suggestions());

        assertEquals(List.of("Keep", "North", "Team"), engine.complete(inMatch, List.of("")));
        assertEquals(List.of("Gate"), engine.complete(inMatch, List.of("Keep", "")));
        assertEquals(List.of("objective:Keep", "objective:North"), engine.complete(inMatch, List.of("objective:")));
        assertTrue(engine.isComplete(inMatch, List.of("North", "Wall")));
    }

    @Test
    void aMatchWithoutDestinationsListsNothingWithoutAReason() {
        match = List.of();
        DestinationList list = engine.listFor(inMatch);
        assertEquals(InitialScopes.SIEGE_MATCH, list.scopeId());
        assertTrue(list.isEmpty());
        assertFalse(list.refused(), "KNG-234: a match without destinations - nothing to navigate to, no policy reason");
        assertEquals(Outcome.NOT_FOUND, engine.resolve(inMatch, "Kardenna", Mode.DEFAULT).outcome());
    }

    @Test
    void withTheCompassOffAMatchMemberGetsTheReason() {
        NavigationContext off = player.withSiege(Siege.match(1, 1).withCompassEnabled(false));
        DestinationList list = engine.listFor(off);
        assertEquals(InitialScopes.SIEGE_MATCH_COMPASS_OFF, list.scopeId());
        assertEquals(InitialScopes.COMPASS_OFF_REASON, list.reason());
        assertEquals(InitialScopes.SIEGE_MATCH, engine.listFor(off.withSiege(off.siege().withCompassEnabled(true))).scopeId());
        assertEquals(InitialScopes.WORLD, engine.listFor(player.withSiege(Siege.NONE.withCompassEnabled(false))).scopeId(),
            "the setting means nothing outside a match");
    }

    // ==================== siege lobby (50) ====================

    @Test
    void aLobbyMemberWithoutARunningMatchGetsTheSiegeReason() {
        DestinationList list = engine.listFor(player.withSiege(Siege.lobby()));
        assertEquals(InitialScopes.SIEGE_LOBBY, list.scopeId());
        assertEquals("this siege", list.displayName());
        assertEquals(InitialScopes.IN_SIEGE_REASON, list.reason());
        assertEquals(InitialScopes.SIEGE_MATCH, engine.listFor(player.withSiege(Siege.match(1, 1))).scopeId(),
            "a running match is above the lobby");
        assertEquals(Outcome.NOT_ALLOWED, engine.resolve(player.withSiege(Siege.lobby()), "Kardenna", Mode.DEFAULT).outcome());
    }

    @Test
    void aHubProviderWithDestinationsReplacesTheLobbyReason() {
        Candidate arena = new Candidate("hub:arena", "Arena", "hub", List.of(), 1, null, 0, "hub",
            new Candidate.Place.Point("hub", 0, 64, 0));
        DestinationEngine withHub = new DestinationEngine(InitialScopes.create(List.of(catalogue), siegeMatch,
            List.of(Provider.fixed("hub", List.of(arena)))));
        NavigationContext lobby = player.withSiege(Siege.lobby());

        DestinationList elsewhere = withHub.listFor(lobby);
        assertEquals(InitialScopes.SIEGE_LOBBY, elsewhere.scopeId());
        assertEquals(InitialScopes.IN_SIEGE_REASON, elsewhere.reason(), "the hub place is in the hub world, the player is not");
        DestinationList atHub = withHub.listFor(lobby.withWorld("hub"));
        assertEquals(List.of(arena), atHub.candidates());
        assertNull(atHub.reason());
        assertSame(arena, withHub.resolve(lobby.withWorld("hub"), "arena", Mode.DEFAULT).candidate());
        assertInstanceOf(Listed.class, engine.scopes().get(6).yield(lobby), "the extension point is kept without a hub provider");
    }

    // ==================== re-validation (§8) ====================

    @Test
    void aMatchSessionSurvivesAnObjectiveChangingKindAndEndsWhenItIsCaptured() {
        AllowedDestination handle = engine.resolve(inMatch, "Keep Gate", Mode.DEFAULT).destination();
        assertEquals("objective:7", handle.candidateKey());

        match = List.of(wallObjective, gateObjective.withDetail("capture, 10% held").withPriority(5), teamSpawn);
        Revalidation flipped = engine.revalidate(inMatch, handle);
        assertTrue(flipped.allowed());
        assertEquals("capture, 10% held", flipped.candidate().detail());

        match = List.of(wallObjective, teamSpawn);
        Revalidation captured = engine.revalidate(inMatch, handle);
        assertFalse(captured.allowed());
        assertEquals("Keep Gate is no longer part of this match.", captured.reason());
    }

    @Test
    void aSessionEndsWithTheScopesReasonWhenThePlayerFallsIntoAnEmptyScope() {
        AllowedDestination handle = engine.resolve(inMatch, "Keep Gate", Mode.DEFAULT).destination();
        assertEquals(InitialScopes.IN_SIEGE_REASON, engine.revalidate(inMatch.withSiege(Siege.lobby()), handle).reason(),
            "match ended: lobby again");
        assertEquals(InitialScopes.FROZEN_REASON, engine.revalidate(inMatch.withFrozen(true), handle).reason());
        assertEquals(InitialScopes.COMPASS_OFF_REASON,
            engine.revalidate(inMatch.withSiege(inMatch.siege().withCompassEnabled(false)), handle).reason());
        assertEquals(InitialScopes.NO_PERMISSION_REASON,
            engine.revalidate(NavigationContext.of(Fixtures.PLAYER, Fixtures.WORLD).withSiege(Siege.match(1, 1)), handle).reason());
    }

    @Test
    void aModeToggleLiftsOrAppliesTheBypassAtOnce() {
        AllowedDestination toTown = engine.resolve(player, "Kardenna", Mode.DEFAULT).destination();
        Revalidation joined = engine.revalidate(player.withSiege(Siege.match(1, 1)), toTown);
        assertFalse(joined.allowed(), "a town is not part of the match");
        assertEquals("Kardenna is no longer part of this match.", joined.reason());
        assertTrue(engine.revalidate(player.withSiege(Siege.match(1, 1)).withMode(ActiveMode.OWNER), toTown).allowed(),
            "owner mode: the bypass lists the town again");

        AllowedDestination asStaff = engine.resolve(player.withMode(ActiveMode.STAFF), "Kardenna", Mode.DEFAULT).destination();
        assertEquals(InitialScopes.BYPASS, asStaff.scopeId());
        assertTrue(engine.revalidate(player, asStaff).allowed(), "mode off: the world still lists the town");
        assertEquals(InitialScopes.FROZEN_REASON, engine.revalidate(player.withMode(ActiveMode.STAFF).withFrozen(true), asStaff).reason());
    }

    @Test
    void anotherWorldIsHiddenFromCompletionButResolvesToItsMessage() {
        Resolution away = engine.resolve(player, "Far Town", Mode.DEFAULT);
        assertEquals(Outcome.OTHER_WORLD, away.outcome());
        assertSame(farTown, away.candidate());
        assertFalse(engine.complete(player, List.of("")).contains("Far"));
        assertEquals(List.of(), engine.complete(player, List.of("far")));
    }
}
