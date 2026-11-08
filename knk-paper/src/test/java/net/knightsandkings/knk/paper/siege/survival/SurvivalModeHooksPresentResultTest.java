package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;
import net.knightsandkings.knk.core.siege.WinResolver;
import net.knightsandkings.knk.paper.siege.SiegeLobbyRuntime;
import net.knightsandkings.knk.paper.siege.SiegeMatch;
import net.knightsandkings.knk.paper.siege.SiegeModeHooks;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Link S8b: the PvP default stays; only a running survival match with a presenter takes the result over. */
class SurvivalModeHooksPresentResultTest {

    private final SurvivalConfigurationCache cache = new SurvivalConfigurationCache(null, Runnable::run, Logger.getLogger("test"));
    private final SurvivalMatch survival = mock(SurvivalMatch.class);
    private final WinResolver.Result result = new WinResolver.Result(SiegeEndReason.TEAM_ELIMINATED, OptionalInt.of(2), WinResolver.Decision.LAST_ALLIANCE_STANDING);

    private SiegeLobbyRuntime lobby(int id, KnkSiegeScenario drawn) {
        SiegeLobbyRuntime rt = mock(SiegeLobbyRuntime.class);
        when(rt.id()).thenReturn(id);
        when(rt.key()).thenReturn("l" + id);
        when(rt.drawnScenario()).thenReturn(Optional.ofNullable(drawn));
        return rt;
    }

    @Test
    void thePvpDefaultPresentsNothing() {
        assertFalse(SiegeModeHooks.NONE.presentResult(lobby(1, null), mock(SiegeMatch.class), result));
        assertTrue(SiegeModeHooks.NONE.resolveResult(lobby(1, null), mock(SiegeMatch.class), SiegeEndReason.TIME_EXPIRED).isEmpty());
    }

    @Test
    void withoutAPresenterOrASurvivalMatchThePvpScreenStays() {
        SurvivalModeHooks hooks = new SurvivalModeHooks(cache, id -> id == 1 ? Optional.of(survival) : Optional.empty(), id -> Optional.empty(), null);
        assertFalse(hooks.presentResult(lobby(1, null), mock(SiegeMatch.class), result), "no presenter installed");
        List<String> presented = new ArrayList<>();
        hooks.setResultPresenter((lobby, match, r, m) -> {
            presented.add(lobby.key());
            return true;
        });
        assertFalse(hooks.presentResult(lobby(2, SurvivalModeHooksTest.scenario(1)), mock(SiegeMatch.class), result), "PvP lobby");
        assertTrue(hooks.presentResult(lobby(1, null), mock(SiegeMatch.class), result), "survival lobby");
        assertEquals(List.of("l1"), presented);
        hooks.setResultPresenter((lobby, match, r, m) -> {
            throw new IllegalStateException("boom");
        });
        assertFalse(hooks.presentResult(lobby(1, null), mock(SiegeMatch.class), result), "a failing presenter falls back to the PvP screen");
        hooks.setResultPresenter(null);
        assertFalse(hooks.presentResult(lobby(1, null), mock(SiegeMatch.class), result));
    }
}
