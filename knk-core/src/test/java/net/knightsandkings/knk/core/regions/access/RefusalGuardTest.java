package net.knightsandkings.knk.core.regions.access;

import net.knightsandkings.knk.core.regions.access.RefusalGuard.Action;
import net.knightsandkings.knk.core.regions.access.RefusalGuard.Outcome;
import net.knightsandkings.knk.core.regions.access.RefusalGuard.Settings;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KNG-56: deny messages at most every 2 s; spawn/kick only under a refusal rate no honest player reaches. */
class RefusalGuardTest {

    private final UUID player = UUID.randomUUID();

    @Test
    void theDenyMessageIsShownAtMostOncePerInterval() {
        RefusalGuard guard = new RefusalGuard(Settings.defaults());

        assertTrue(guard.onRefusal(player, 0).showMessage());
        assertFalse(guard.onRefusal(player, 500).showMessage());
        assertFalse(guard.onRefusal(player, 1999).showMessage());
        assertTrue(guard.onRefusal(player, 2000).showMessage());
    }

    @Test
    void aPlayerHoldingWIntoTheBorderIsNeverPunished() {
        RefusalGuard guard = new RefusalGuard(Settings.defaults());

        // ~5 refusals a second (sprinting into the border, pushed back each time) for a whole minute
        for (long t = 0; t < 60_000; t += 200) {
            assertEquals(Action.NONE, guard.onRefusal(player, t).action(), "at " + t + " ms");
        }
    }

    @Test
    void aSustainedFloodIsSentToSpawnThenKicked() {
        RefusalGuard guard = new RefusalGuard(Settings.defaults());  // > 20/s over 3 s

        Action first = Action.NONE;
        long t = 0;
        for (; first == Action.NONE; t += 10) {  // 100 refusals a second
            first = guard.onRefusal(player, t).action();
        }
        assertEquals(Action.TELEPORT_TO_SPAWN, first);

        Action second = Action.NONE;
        for (; second == Action.NONE; t += 10) {
            second = guard.onRefusal(player, t).action();
        }
        assertEquals(Action.KICK, second);
    }

    @Test
    void aFloodLongAfterTheTeleportStartsOverAtSpawn() {
        RefusalGuard guard = new RefusalGuard(new Settings(2000, true, 20, 3000, 60_000));
        long t = flood(guard, 0);

        assertEquals(Action.TELEPORT_TO_SPAWN, actionOfNextFlood(guard, t + 120_000));
    }

    @Test
    void theLoadGuardCanBeSwitchedOff() {
        RefusalGuard guard = new RefusalGuard(new Settings(2000, false, 20, 3000, 60_000));

        for (long t = 0; t < 10_000; t += 1) {
            assertEquals(Action.NONE, guard.onRefusal(player, t).action());
        }
    }

    @Test
    void playersAreTrackedSeparately() {
        RefusalGuard guard = new RefusalGuard(Settings.defaults());
        UUID other = UUID.randomUUID();

        Outcome mine = guard.onRefusal(player, 0);
        Outcome theirs = guard.onRefusal(other, 1);

        assertTrue(mine.showMessage());
        assertTrue(theirs.showMessage());
    }

    // ---- KNG-74: chat once per refusal episode, action-bar hold ----

    private static final String ENTER = "You are not allowed to enter Old Quarter.";
    private static final String LEAVE = "You are not allowed to leave Jail.";

    @Test
    void theFirstRefusalOfAnEpisodeGoesToChatAndTheActionBar() {
        RefusalGuard guard = new RefusalGuard(Settings.defaults());

        Outcome first = guard.onRefusal(player, 0, ENTER);

        assertTrue(first.showMessage());
        assertTrue(first.showInChat());
    }

    @Test
    void refusalsWithinTheQuietPeriodAreNotRepeatedInChat() {
        RefusalGuard guard = new RefusalGuard(Settings.defaults());  // 2 s throttle, 10 s quiet period
        guard.onRefusal(player, 0, ENTER);

        // pushing the border five times a second for a whole minute: one episode
        int shown = 0;
        for (long t = 200; t < 60_000; t += 200) {
            Outcome outcome = guard.onRefusal(player, t, ENTER);
            assertFalse(outcome.showInChat(), "at " + t + " ms");
            if (outcome.showMessage()) {
                shown++;
            }
        }
        assertTrue(shown >= 29, "the action bar keeps repeating it every 2 s, was " + shown);
    }

    @Test
    void afterTheQuietPeriodTheNextRefusalGoesToChatAgain() {
        RefusalGuard guard = new RefusalGuard(Settings.defaults());
        guard.onRefusal(player, 0, ENTER);
        guard.onRefusal(player, 3_000, ENTER);

        assertFalse(guard.onRefusal(player, 12_999, ENTER).showInChat(), "only 9.999 s quiet");
        Outcome again = guard.onRefusal(player, 22_999, ENTER);
        assertTrue(again.showMessage());
        assertTrue(again.showInChat(), "10 s without a refusal");
    }

    @Test
    void aDifferentRefusalStartsANewEpisodeButKeepsTheThrottle() {
        RefusalGuard guard = new RefusalGuard(Settings.defaults());
        guard.onRefusal(player, 0, ENTER);

        Outcome throttled = guard.onRefusal(player, 1_000, LEAVE);
        assertFalse(throttled.showMessage());
        assertFalse(throttled.showInChat());

        assertTrue(guard.onRefusal(player, 2_000, LEAVE).showInChat(), "another domain: said in chat");
        assertFalse(guard.onRefusal(player, 4_000, LEAVE).showInChat());
    }

    @Test
    void aZeroQuietPeriodSendsEveryShownMessageToChat() {
        RefusalGuard guard = new RefusalGuard(new Settings(2000, true, 20, 3000, 60_000, 0, 3000));

        assertTrue(guard.onRefusal(player, 0, ENTER).showInChat());
        assertFalse(guard.onRefusal(player, 500, ENTER).showInChat(), "still throttled");
        assertTrue(guard.onRefusal(player, 2_000, ENTER).showInChat());
    }

    @Test
    void aShownMessageHoldsTheActionBarForAWhile() {
        RefusalGuard guard = new RefusalGuard(Settings.defaults());  // 3 s hold

        assertFalse(guard.holdsActionBar(player, 0), "nothing shown yet");
        guard.onRefusal(player, 1_000, ENTER);
        assertTrue(guard.holdsActionBar(player, 1_000));
        assertTrue(guard.holdsActionBar(player, 3_999));
        guard.onRefusal(player, 2_500, ENTER);  // throttled: not shown, does not extend the hold
        assertFalse(guard.holdsActionBar(player, 4_000));
        assertFalse(guard.holdsActionBar(UUID.randomUUID(), 1_000), "per player");
    }

    private long flood(RefusalGuard guard, long start) {
        long t = start;
        while (guard.onRefusal(player, t).action() == Action.NONE) {
            t += 10;
        }
        return t;
    }

    private Action actionOfNextFlood(RefusalGuard guard, long start) {
        long t = start;
        Action action;
        do {
            action = guard.onRefusal(player, t).action();
            t += 10;
        } while (action == Action.NONE);
        return action;
    }
}
