package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.paper.siege.SiegeLobbyRuntime;
import net.knightsandkings.knk.paper.siege.SiegeMatch;
import net.knightsandkings.knk.paper.siege.SiegeService;

/** Who is refused navigation because of a siege (plan D2, relaxed for a running match by KNG-234). */
class NavigationEligibilityTest {

    private final SiegeService siege = mock(SiegeService.class);
    private final UUID id = UUID.randomUUID();

    @Test
    void aMemberOfAnyLobbyPhaseIsRefusedByTheOriginalRule() {
        when(siege.lobbyOf(id)).thenReturn(Optional.of(mock(SiegeLobbyRuntime.class)));
        when(siege.runningMatchOf(id)).thenReturn(Optional.of(mock(SiegeMatch.class)));

        assertTrue(NavigationEligibility.siegeMembers(() -> siege).test(id));
    }

    @Test
    void aMemberWhoseMatchIsRunningMayNavigate() {
        when(siege.lobbyOf(id)).thenReturn(Optional.of(mock(SiegeLobbyRuntime.class)));
        when(siege.runningMatchOf(id)).thenReturn(Optional.of(mock(SiegeMatch.class)));

        assertFalse(NavigationEligibility.siegeMembersOutsideMatch(() -> siege).test(id));
    }

    @Test
    void aMemberInTheLobbyOrAtTheHubStillMayNot() {
        when(siege.lobbyOf(id)).thenReturn(Optional.of(mock(SiegeLobbyRuntime.class)));
        when(siege.runningMatchOf(id)).thenReturn(Optional.empty());

        assertTrue(NavigationEligibility.siegeMembersOutsideMatch(() -> siege).test(id));
    }

    @Test
    void someoneOutsideAnyLobbyMayNavigate() {
        when(siege.lobbyOf(id)).thenReturn(Optional.empty());
        when(siege.runningMatchOf(id)).thenReturn(Optional.empty());

        assertFalse(NavigationEligibility.siegeMembersOutsideMatch(() -> siege).test(id));
    }

    @Test
    void withoutASiegeServiceNobodyIsRefused() {
        Predicate<UUID> rule = NavigationEligibility.siegeMembersOutsideMatch(() -> null);

        assertFalse(rule.test(id));
    }
}
