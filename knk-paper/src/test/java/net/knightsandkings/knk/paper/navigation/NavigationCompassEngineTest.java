package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.navigation.compass.CompassItemRule;
import net.knightsandkings.knk.core.navigation.compass.CompassSettings;
import net.knightsandkings.knk.core.navigation.destinations.AllowedDestination;
import net.knightsandkings.knk.core.navigation.destinations.Candidate.Mode;
import net.knightsandkings.knk.core.navigation.destinations.InitialScopes;
import net.knightsandkings.knk.core.navigation.model.ModelFreshness;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * The navigation compass with {@code navigation.destination-engine} on (phase 3, PandiO/knk-workspace#69, A18, A19):
 * it cycles the engine's list, keeps its own wording per reason code (E15), and a resume resolves the stored key
 * again. Against the fake engine of {@link EngineFixture}.
 */
class NavigationCompassEngineTest {

    private final NavigationService navigation = mock(NavigationService.class);
    private final NavigationDestinations catalogue = mock(NavigationDestinations.class);
    private final AtomicLong clock = new AtomicLong(10_000);
    private EngineFixture f;
    private NavigationCompassService compass;

    @BeforeEach
    void setUp() {
        f = new EngineFixture();
        compass = new NavigationCompassService(f.compass::get, () -> navigation, () -> catalogue, () -> f.siege,
            (p, node) -> false, Runnable::run, clock::get, mock(CompassItems.class));
        compass.setEngine(() -> f.engine);
    }

    private void click(boolean sneaking) {
        clock.addAndGet(NavigationCompassService.CLICK_COOLDOWN_MILLIS + 1);
        assertTrue(compass.use(f.player, sneaking));
    }

    private List<String> messages() {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(f.player, atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues().stream().map(c -> PlainTextComponentSerializer.plainText().serialize(c)).toList();
    }

    private String lastMessage() {
        List<String> all = messages();
        return all.get(all.size() - 1);
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private List<AllowedDestination> handles(int times) {
        ArgumentCaptor<AllowedDestination> captor = ArgumentCaptor.forClass(AllowedDestination.class);
        verify(navigation, times(times)).navigate(any(), captor.capture());
        return captor.getAllValues();
    }

    // ==================== in a siege match ====================

    @Test
    void inAMatchTheCompassCyclesTheMatchsListInItsOrder() {
        f.inMatch();
        click(false);
        assertEquals(plain(CompassMessages.selected(1, 3, "Keep Gate", "capture")), lastMessage());
        click(false);
        click(false);
        click(false);
        assertEquals(List.of("objective:7", "objective:8", "spawn:20", "objective:7"),
            handles(4).stream().map(AllowedDestination::candidateKey).toList());
        assertEquals(InitialScopes.SIEGE_MATCH, handles(4).get(0).scopeId());
        verify(navigation, never()).navigate(any(), any(Destination.class));
    }

    @Test
    void aResumeResolvesTheStoredKeyAgain() {
        f.inMatch();
        click(false);
        when(navigation.isNavigating(f.id)).thenReturn(true);
        click(true);
        verify(navigation).stop(f.player);
        when(navigation.isNavigating(f.id)).thenReturn(false);
        click(true);
        assertEquals(List.of("objective:7", "objective:7"), handles(2).stream().map(AllowedDestination::candidateKey).toList());
    }

    @Test
    void aDestinationThatLeftTheListIsNotResumed() {
        f.inMatch();
        click(false);
        f.match.removeIf(c -> c.key().equals("objective:7"));
        click(true);
        handles(1);
        assertEquals(plain(CompassMessages.nothingToResume()), lastMessage());
    }

    @Test
    void withTheCompassOffForSiegesItSaysSo() {
        f.inMatch();
        f.compass.set(new CompassSettings(CompassItemRule.defaults(), false, true, List.of()));
        click(false);
        assertEquals(List.of(plain(CompassMessages.disabledInSiege())), messages());
        verify(navigation, never()).navigate(any(), any(AllowedDestination.class));
    }

    @Test
    void inTheLobbyItWaitsForTheMatch() {
        f.inLobby();
        click(false);
        assertEquals(List.of(plain(CompassMessages.waitForMatch())), messages());
    }

    @Test
    void forTheBypassInAMatchItCyclesTheMatchNotTheWorld() {
        f.inMatch();
        f.mode.set(ActiveMode.OWNER);
        click(false);
        assertEquals(plain(CompassMessages.selected(1, 3, "Keep Gate", "capture")), lastMessage());
        assertEquals(InitialScopes.BYPASS, handles(1).get(0).scopeId());
    }

    @Test
    void forTheBypassInTheLobbyItStillWaitsForTheMatch() {
        f.inLobby();
        f.nodes.add(InitialScopes.BYPASS_NODE);
        click(false);
        assertEquals(List.of(plain(CompassMessages.waitForMatch())), messages());
    }

    // ==================== outside a siege ====================

    @Test
    void outsideASiegeItCyclesTheConfiguredTargetsThatAreListed() {
        click(false);
        assertEquals(plain(CompassMessages.selected(1, 2, "Main Street", null)), lastMessage());
        click(false);
        assertEquals(plain(CompassMessages.selected(2, 2, "Kardenna", null)), lastMessage());
        List<AllowedDestination> handles = handles(2);
        assertEquals("street:5", handles.get(0).candidateKey());
        assertEquals("domain:Town:1", handles.get(1).candidateKey());
        assertEquals(Mode.SPAWN, handles.get(1).mode(), "the line's mode word");
    }

    @Test
    void withTheGeneralSwitchOffItHasNoDestinationsHere() {
        f.compass.set(new CompassSettings(CompassItemRule.defaults(), true, false, List.of("Kardenna")));
        click(false);
        assertEquals(List.of(plain(CompassMessages.disabledHere())), messages());
    }

    @Test
    void withNothingListedWhileTheModelIsLimitedItAddsTheNote() {
        f.compass.set(new CompassSettings(CompassItemRule.defaults(), true, true, List.of("Nowhere")));
        f.freshness.set(ModelFreshness.of(null, Instant.now(), Duration.ofDays(14)));
        click(false);
        assertEquals(List.of(plain(CompassMessages.disabledHere()), plain(NavigationMessages.info(ModelFreshness.LIMITED_TEXT))),
            messages());
    }

    @Test
    void aFrozenPlayerIsToldSo() {
        f.frozen.set(true);
        click(false);
        assertEquals(List.of(plain(NavigationMessages.frozen())), messages());
    }

    @Test
    void aMatchCursorDoesNotCarryOverToTheWorld() {
        f.inMatch();
        click(false);
        f.outOfSiege();
        click(true);
        // the stored key belongs to the match: a sneak-click outside it starts the general cycle afresh
        assertEquals("street:5", handles(2).get(1).candidateKey());
    }

    @Test
    void withoutTheNodeTheCompassKeepsItsNoPermissionWords() {
        // phase 4 (#62, A22): the no-permission scope (80) refuses; the compass's own words for the code (E15)
        f.nodes.clear();
        click(false);
        assertEquals(plain(CompassMessages.noPermission()), lastMessage());
        verify(navigation, never()).navigate(any(), any(AllowedDestination.class));
    }

    @Test
    void theBypassNodeAloneIsEnoughForTheCompass() {
        f.nodes.clear();
        f.nodes.add(InitialScopes.BYPASS_NODE);
        f.inMatch();
        click(false);
        assertEquals("objective:7", handles(1).get(0).candidateKey());
    }

    @Test
    void withTheFlagOffTheEngineIsNotUsed() {
        compass.setEngine(() -> null);
        when(catalogue.resolve(any(), any())).thenReturn(new NavigationDestinations.Resolution(null, List.of(), List.of()));
        click(false);
        // today's general cycle resolves through the catalogue
        verify(catalogue, atLeastOnce()).resolve(any(), any());
    }
}
