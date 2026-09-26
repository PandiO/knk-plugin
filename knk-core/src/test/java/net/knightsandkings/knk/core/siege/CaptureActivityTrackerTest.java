package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import net.knightsandkings.knk.core.siege.CaptureActivityTracker.Activity;
import net.knightsandkings.knk.core.siege.CaptureActivityTracker.Started;
import net.knightsandkings.knk.core.siege.CaptureActivityTracker.Update;
import net.knightsandkings.knk.core.siege.ObjectiveState.Presence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static net.knightsandkings.knk.core.siege.SiegeTestData.CONFIG;
import static net.knightsandkings.knk.core.siege.SiegeTestData.at;
import static net.knightsandkings.knk.core.siege.SiegeTestData.objective;
import static net.knightsandkings.knk.core.siege.SiegeTestData.player;
import static net.knightsandkings.knk.core.siege.SiegeTestData.team;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smoke test 2026-09-26: capture feedback - who began attacking/defending, and who is at it. */
class CaptureActivityTrackerTest {

    private static final int DEF = 1;
    private static final int ATT = 2;
    private static final int GATE = 2;

    private static KnkSiegeScenario scenario(boolean allowRecapture) {
        return SiegeTestData.scenario(1, 5,
                List.of(team(DEF, SiegeTeamRole.DEFENDER, 1, "Defenders"), team(ATT, SiegeTeamRole.ATTACKER, 2, "Attackers")),
                List.of(objective(1, true, DEF), objective(GATE, false, DEF, 20)),
                allowRecapture);
    }

    private final KnkSiegeScenario scenario = scenario(false);
    private final AllianceResolver alliances = AllianceResolver.of(scenario);
    private final SiegeObjectiveBoard board = new SiegeObjectiveBoard(scenario, new CaptureCalculator(CONFIG), alliances);

    private Update step(CaptureActivityTracker tracker, SiegeObjectiveBoard b, AllianceResolver a, Presence... present) {
        Map<Integer, List<Presence>> presence = Map.of(GATE, List.of(present));
        return tracker.step(b.step(presence).steps(), presence, b::objective, a);
    }

    private Update step(CaptureActivityTracker tracker, Presence... present) {
        return step(tracker, board, alliances, present);
    }

    @Test
    void attackersBeginningACaptureAreAnnouncedOnceAndReportedEverySecond() {
        CaptureActivityTracker tracker = new CaptureActivityTracker();

        Update first = step(tracker, at(12, ATT, 2.0), at(11, ATT, 0.5));
        Started started = first.started().get(0);
        assertEquals(Activity.ATTACKING, started.activity());
        assertEquals(List.of(player(11), player(12)), started.actors()); // closest first
        assertEquals(List.of(ATT), started.actorTeamIds());
        assertEquals(DEF, started.holderTeamId());
        assertFalse(started.retake());
        assertTrue(started.capturePercent() > 0);

        Update second = step(tracker, at(11, ATT, 0.5));
        assertTrue(second.started().isEmpty());
        assertEquals(List.of(player(11)), second.ongoing().get(0).actors());
    }

    @Test
    void defendersPushingBackAreAnnounced_andAQuickReturnIsNotReannounced() {
        CaptureActivityTracker tracker = new CaptureActivityTracker();
        step(tracker, at(11, ATT, 1));

        Update defend = step(tracker, at(1, DEF, 1), at(2, DEF, 1.5));
        assertEquals(Activity.DEFENDING, defend.started().get(0).activity());
        assertEquals(List.of(player(1), player(2)), defend.started().get(0).actors());

        Update back = step(tracker, at(11, ATT, 1));
        assertTrue(back.started().isEmpty(), "attack announced 2 s ago");
        assertEquals(Activity.ATTACKING, back.ongoing().get(0).activity());

        CaptureActivityTracker eager = new CaptureActivityTracker(0);
        step(eager, at(11, ATT, 1));
        step(eager, at(1, DEF, 1), at(2, DEF, 1));
        assertEquals(Activity.ATTACKING, step(eager, at(11, ATT, 1)).started().get(0).activity());
    }

    @Test
    void aLoneDefenderOnAFullObjectiveAndAStandoffAreNotActivity() {
        CaptureActivityTracker tracker = new CaptureActivityTracker();
        Update idle = step(tracker, at(1, DEF, 1));
        assertTrue(idle.started().isEmpty());
        assertTrue(idle.ongoing().isEmpty());
    }

    @Test
    void theCaptureSecondReportsNothing_andTheFormerHoldersAttackIsARetake() {
        KnkSiegeScenario recapture = scenario(true);
        AllianceResolver a = AllianceResolver.of(recapture);
        SiegeObjectiveBoard b = new SiegeObjectiveBoard(recapture, new CaptureCalculator(CONFIG), a);
        CaptureActivityTracker tracker = new CaptureActivityTracker();

        Update last = null;
        for (int i = 0; i < 10 && b.objective(GATE).holderTeamId() == DEF; i++) {
            last = step(tracker, b, a, at(11, ATT, 1));
        }
        assertEquals(ATT, b.objective(GATE).holderTeamId());
        assertTrue(last.started().isEmpty());
        assertTrue(last.ongoing().isEmpty());

        Update retake = step(tracker, b, a, at(1, DEF, 1));
        Started started = retake.started().get(0);
        assertEquals(Activity.ATTACKING, started.activity());
        assertEquals(ATT, started.holderTeamId());
        assertTrue(started.retake());
    }
}
