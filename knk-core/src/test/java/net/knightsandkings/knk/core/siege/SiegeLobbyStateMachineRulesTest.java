package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.siege.SiegeEffect.CancelMatchmakingEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.CancelReason;
import net.knightsandkings.knk.core.siege.SiegeEffect.FinalCountdownEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.StartMatchEffect;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static net.knightsandkings.knk.core.siege.SiegeTestData.CONFIG;
import static net.knightsandkings.knk.core.siege.SiegeTestData.lobby;
import static net.knightsandkings.knk.core.siege.SiegeTestData.twoTeamScenario;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Survival S5: the per-scenario mode rules on the lobby state machine; PVP keeps the old behaviour. */
class SiegeLobbyStateMachineRulesTest {

    private final KnkSiegeScenario cinix = twoTeamScenario(1, 5);

    private SiegeLobbyStateMachine machine(SiegeScenarioRules rules) {
        SiegeLobbyStateMachine m = new SiegeLobbyStateMachine(lobby(1, 300, 900, 1, false, cinix), CONFIG, new SiegeRuntimeLocks(), new Random(1));
        m.setScenarioRules(rules);
        return m;
    }

    private static List<SiegeEffect> runToStart(SiegeLobbyStateMachine m, int members) {
        List<SiegeEffect> last = List.of();
        m.start();
        while (m.phase() == SiegePhase.MATCHMAKING || m.phase() == SiegePhase.HUB) {
            last = m.tick(members);
        }
        return last;
    }

    @Test
    void pvpDefaultsStillNeedTheScenarioMinimumAndExpire() {
        SiegeLobbyStateMachine m = machine(null);
        assertEquals(SiegeScenarioRules.PVP, m.scenarioRules());
        List<SiegeEffect> last = runToStart(m, 1);
        assertTrue(last.stream().anyMatch(e -> e instanceof CancelMatchmakingEffect c && c.reason() == CancelReason.NOT_ENOUGH_PLAYERS));
        assertEquals(SiegePhase.COOLDOWN, m.phase());
    }

    @Test
    void aSurvivalScenarioStartsWithOnePlayerAndNeverExpires() {
        SiegeLobbyStateMachine m = machine(new SiegeScenarioRules() {
            @Override
            public int playersMin(KnkSiegeScenario scenario) {
                return 1;
            }

            @Override
            public boolean timed(KnkSiegeScenario scenario) {
                return false;
            }
        });
        List<SiegeEffect> last = runToStart(m, 1);
        assertTrue(last.stream().anyMatch(StartMatchEffect.class::isInstance));
        assertEquals(SiegePhase.IN_PROGRESS, m.phase());
        int duration = m.secondsRemaining();
        for (int i = 0; i < duration + 60; i++) {
            List<SiegeEffect> effects = m.tick(1);
            assertFalse(effects.stream().anyMatch(FinalCountdownEffect.class::isInstance), "no final countdown when untimed");
        }
        assertEquals(SiegePhase.IN_PROGRESS, m.phase(), "the match never ends on TIME_EXPIRED");
        assertEquals(duration, m.secondsRemaining(), "the clock doesn't run down");
        assertTrue(m.secondsElapsed() > duration);
    }

    @Test
    void anUnstartableScenarioCancelsTheRound() {
        SiegeLobbyStateMachine m = machine(new SiegeScenarioRules() {
            @Override
            public boolean startable(KnkSiegeScenario scenario) {
                return false;
            }
        });
        List<SiegeEffect> last = runToStart(m, 4);
        assertTrue(last.stream().anyMatch(e -> e instanceof CancelMatchmakingEffect c && c.reason() == CancelReason.NO_SCENARIO_AVAILABLE));
        assertEquals(SiegePhase.COOLDOWN, m.phase());
    }
}
