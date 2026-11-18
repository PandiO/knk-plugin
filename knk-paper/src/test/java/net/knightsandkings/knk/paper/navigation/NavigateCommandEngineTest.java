package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.bukkit.command.Command;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.navigation.destinations.AllowedDestination;
import net.knightsandkings.knk.core.navigation.destinations.Candidate.Mode;
import net.knightsandkings.knk.core.navigation.destinations.InitialScopes;
import net.knightsandkings.knk.core.navigation.model.ModelFreshness;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * {@code /navigate} and its tab completion with {@code navigation.destination-engine} on (phase 3,
 * PandiO/knk-workspace#69, A07, A08, A17, A18, A20), against the fake engine of {@link EngineFixture}: only the
 * player's list exists, a session starts only from a handle, the messages are today's.
 */
class NavigateCommandEngineTest {

    private final NavigationService service = mock(NavigationService.class);
    private final NavigationDestinations catalogue = mock(NavigationDestinations.class);
    private final Command command = mock(Command.class);
    private EngineFixture f;
    private NavigateCommand navigate;

    @BeforeEach
    void setUp() {
        f = new EngineFixture();
        navigate = new NavigateCommand(() -> service, () -> catalogue, (p, node) -> false, Runnable::run);
        navigate.setEngine(() -> f.engine);
    }

    private void run(String... args) {
        navigate.onCommand(f.player, command, "navigate", args);
    }

    private List<String> messages() {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(f.player, atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues().stream().map(c -> PlainTextComponentSerializer.plainText().serialize(c)).toList();
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private AllowedDestination handle() {
        ArgumentCaptor<AllowedDestination> captor = ArgumentCaptor.forClass(AllowedDestination.class);
        verify(service).navigate(any(), captor.capture());
        return captor.getValue();
    }

    private void noSession() {
        verify(service, never()).navigate(any(), any(AllowedDestination.class));
        verify(service, never()).navigate(any(), any(Destination.class));
    }

    // ==================== outside a siege ====================

    @Test
    void aNameStartsASessionFromAHandleNeverFromARawDestination() {
        run("Kardenna");
        AllowedDestination handle = handle();
        assertEquals("domain:Town:1", handle.candidateKey());
        assertEquals(Mode.DEFAULT, handle.mode());
        assertEquals(InitialScopes.WORLD, handle.scopeId());
        verify(service, never()).navigate(any(), any(Destination.class));
        verifyNoInteractions(catalogue);
    }

    @Test
    void typeNameIdAndTheModeWordsWork() {
        run("town:#1", "spawn");
        assertEquals("domain:Town:1", handle().candidateKey());
        assertEquals(Mode.SPAWN, handle().mode());
    }

    @Test
    void aDomainAskedForWithAModeItLacksGoesToTheOtherAsToday() {
        run("district:Market", "spawn");
        assertEquals(Mode.REGION, handle().mode(), "a district with only a region is led to the region");
    }

    @Test
    void aSharedNameOffersTheTypeNameChoices() {
        run("Market");
        assertEquals(List.of(plain(NavigationMessages.ambiguous("Market", List.of("district:Market", "town:Market")))), messages());
        noSession();
    }

    @Test
    void aMisspeltNameIsUnknownWithDidYouMean() {
        run("Karden");
        assertEquals(List.of(plain(NavigationMessages.unknownDestination("Karden")),
            plain(NavigationMessages.didYouMean(List.of("Kardenna")))), messages());
        noSession();
    }

    @Test
    void aPlaceInAnotherWorldGetsTodaysMessage() {
        run("Farland");
        assertEquals(List.of(plain(NavigationMessages.otherWorld("Farland"))), messages());
        noSession();
    }

    @Test
    void aDomainWithoutAnyPlaceHasNoLocation() {
        run("Empty Lot");
        assertEquals(List.of(plain(NavigationMessages.noLocation("Empty Lot"))), messages());
        noSession();
    }

    @Test
    void aNameNotFoundWhileTheModelIsLimitedCarriesTheNote() {
        f.freshness.set(ModelFreshness.of(null, Instant.now(), Duration.ofDays(14)));
        run("Kardenna Castle");
        assertEquals(plain(NavigationMessages.info(ModelFreshness.LIMITED_TEXT)), messages().get(messages().size() - 1));
    }

    @Test
    void aFrozenPlayerOrALobbyMemberGetsTodaysWords() {
        f.frozen.set(true);
        run("Kardenna");
        assertEquals(List.of(plain(NavigationMessages.frozen())), messages());
        f.frozen.set(false);
        f.inLobby();
        run("Kardenna");
        assertEquals(plain(NavigationMessages.inSiege()), messages().get(messages().size() - 1));
        noSession();
    }

    @Test
    void withoutTheNodeTheNoPermissionScopeRefusesWithTodaysWords() {
        // phase 4 (#62, A22): the scope (80) refuses, not the command's own check
        f.nodes.clear();
        run("Kardenna");
        assertEquals(List.of(plain(NavigationMessages.noPermission())), messages());
        noSession();
        assertEquals(List.of("stop"), navigate.onTabComplete(f.player, command, "navigate", new String[] {""}));
    }

    @Test
    void theBypassNodeAloneIsEnoughWithTheEngine() {
        // phase 4 (#62): the bypass (100) sits above the no-permission scope (80), E5
        f.nodes.clear();
        f.nodes.add(InitialScopes.BYPASS_NODE);
        run("Kardenna");
        assertEquals("domain:Town:1", handle().candidateKey());
        assertTrue(navigate.onTabComplete(f.player, command, "navigate", new String[] {"Kar"}).contains("Kardenna"));
    }

    @Test
    void stopAndStatusAreUnchanged() {
        run("stop");
        verify(service).stop(f.player);
        run();
        verify(service).status(f.player);
    }

    // ==================== in a siege match ====================

    @Test
    void inAMatchTypeNameAndTheStartOfANameWork() {
        f.inMatch();
        run("objective:Keep", "Gate");
        assertEquals("objective:7", handle().candidateKey());
        assertEquals(InitialScopes.SIEGE_MATCH, handle().scopeId());
    }

    @Test
    void inAMatchTheStartOfExactlyOneNamePicksIt() {
        f.inMatch();
        run("outer");
        assertEquals("objective:8", handle().candidateKey());
    }

    @Test
    void inAMatchAWholeNameEndingInSpawnIsTheTeamSpawn() {
        f.inMatch();
        run("Raiders", "spawn");
        assertEquals("spawn:20", handle().candidateKey());
    }

    @Test
    void inAMatchATownIsRefusedNamingTheMatchsDestinations() {
        f.inMatch();
        run("Kardenna");
        assertEquals(List.of(plain(CompassMessages.matchOnly("Kardenna", List.of("Keep Gate", "Outer Wall", "Raiders spawn")))),
            messages());
        noSession();
    }

    @Test
    void inAMatchTheStartOfSeveralNamesIsAChoice() {
        f.inMatch();
        f.match.add(new net.knightsandkings.knk.core.navigation.destinations.Candidate("objective:9", "Keep Tower", "objective",
            List.of(), 9, null, 3, "siege-match",
            new net.knightsandkings.knk.core.navigation.destinations.Candidate.Place.Point(EngineFixture.WORLD, 1, 64, 1)));
        run("keep");
        assertEquals(List.of(plain(CompassMessages.matchAmbiguous("keep", List.of("Keep Gate", "Keep Tower")))), messages());
        noSession();
    }

    @Test
    void withTheCompassOffForSiegesAMatchHasNoDestinations() {
        f.inMatch();
        f.compass.set(new net.knightsandkings.knk.core.navigation.compass.CompassSettings(
            net.knightsandkings.knk.core.navigation.compass.CompassItemRule.defaults(), false, true, List.of()));
        run("Keep Gate");
        assertEquals(List.of(plain(CompassMessages.noMatchDestinations())), messages());
        noSession();
    }

    @Test
    void theBypassListsEverythingInAMatch() {
        f.inMatch();
        f.nodes.add(InitialScopes.BYPASS_NODE);
        run("Kardenna");
        assertEquals("domain:Town:1", handle().candidateKey());
        assertEquals(InitialScopes.BYPASS, handle().scopeId());
    }

    @Test
    void staffModeIsTheBypassToo() {
        f.inMatch();
        f.mode.set(ActiveMode.STAFF);
        run("Kardenna");
        assertEquals(InitialScopes.BYPASS, handle().scopeId());
    }

    // ==================== tab completion ====================

    @Test
    void completionOffersStopAndTheListsNamesButNotAnotherWorld() {
        List<String> out = navigate.onTabComplete(f.player, command, "navigate", new String[] {""});
        assertTrue(out.contains("stop"));
        assertTrue(out.contains("Kardenna"));
        assertTrue(out.contains("Main"));
        assertFalse(out.contains("Farland"), "another world's places are not completed (E11)");
        assertEquals(List.of("Kardenna"), navigate.onTabComplete(f.player, command, "navigate", new String[] {"Kar"}));
        List<String> towns = navigate.onTabComplete(f.player, command, "navigate", new String[] {"town:"});
        assertTrue(towns.contains("town:Kardenna") && towns.contains("town:Market"), towns.toString());
        assertFalse(towns.contains("town:Farland"));
    }

    @Test
    void completionOffersTheModeWordsAfterACompleteName() {
        assertEquals(List.of("spawn", "region"), navigate.onTabComplete(f.player, command, "navigate", new String[] {"Kardenna", ""}));
    }

    @Test
    void inAMatchCompletionOffersOnlyTheMatchsDestinationsAndStop() {
        f.inMatch();
        List<String> out = navigate.onTabComplete(f.player, command, "navigate", new String[] {""});
        assertEquals(List.of("stop", "Keep", "Outer", "Raiders"), out);
        assertEquals(List.of("Gate"), navigate.onTabComplete(f.player, command, "navigate", new String[] {"Keep", ""}),
            "the next word of a multi-word name, no mode words in a match");
    }

    @Test
    void aRefusedListCompletesOnlyStop() {
        f.inLobby();
        assertEquals(List.of("stop"), navigate.onTabComplete(f.player, command, "navigate", new String[] {""}));
    }

    // ==================== the flag off ====================

    @Test
    void withTheFlagOffTheEngineIsNotUsed() {
        navigate.setEngine(() -> null);
        org.mockito.Mockito.when(catalogue.resolve(anyString(), anyString())).thenReturn(
            new NavigationDestinations.Resolution(null, List.of(), List.of()));
        run("Kardenna");
        verify(catalogue).resolve("Kardenna", EngineFixture.WORLD);
        assertEquals(List.of(plain(NavigationMessages.unknownDestination("Kardenna"))), messages());
    }

    @Test
    void withTheFlagOffTheCommandStillChecksTheNodeItself() {
        navigate.setEngine(() -> null);
        f.nodes.clear();
        f.nodes.add(InitialScopes.BYPASS_NODE); // the bypass means nothing without the engine
        run("Kardenna");
        assertEquals(List.of(plain(NavigationMessages.noPermission())), messages());
        noSession();
        assertEquals(List.of(), navigate.onTabComplete(f.player, command, "navigate", new String[] {""}));
    }
}
