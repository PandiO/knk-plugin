package net.knightsandkings.knk.core.statistics;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The open-world killstreak (KNG-34, DESIGN.md §F.7, D2, L1-11): consecutive PvP kills without dying.
 * Any death resets it, and so does quitting the server. Siege streaks are the match roster's and are
 * projected by the API, so the combat listener only feeds open-world kills here; deaths in any context
 * still reset the streak. Main thread only.
 */
public final class KillstreakTracker {

    private final Map<UUID, Integer> streaks = new HashMap<>();

    /** A PvP kill by {@code killer}; returns the new streak (the value recorded as {@code highest_killstreak}). */
    public int kill(UUID killer) {
        if (killer == null) {
            return 0;
        }
        return streaks.merge(killer, 1, Integer::sum);
    }

    /** The player died (any cause, any context): the streak starts over. */
    public void death(UUID player) {
        if (player != null) {
            streaks.remove(player);
        }
    }

    /** The player left the server (L1-11): the streak starts over. */
    public void quit(UUID player) {
        death(player);
    }

    public int current(UUID player) {
        return player == null ? 0 : streaks.getOrDefault(player, 0);
    }

    /** Players with a running streak (tests, diagnostics). */
    public int size() {
        return streaks.size();
    }
}
