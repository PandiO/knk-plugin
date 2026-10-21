package net.knightsandkings.knk.core.duel.view;

import java.util.List;

/** The {@code duels} root of {@code duel_matches}: the header tile's counts. */
public final class DuelServerView {

    private final int liveMatches;
    private final int arenas;
    private final boolean running;

    private DuelServerView(int liveMatches, int arenas, boolean running) {
        this.liveMatches = liveMatches;
        this.arenas = arenas;
        this.running = running;
    }

    public static DuelServerView of(int liveMatches, int arenas) {
        return new DuelServerView(liveMatches, arenas, true);
    }

    /** Duels are off or haven't started. */
    public static DuelServerView off() {
        return new DuelServerView(0, 0, false);
    }

    public List<String> getLines() {
        if (!running) return List.of("&cDuels aren't running right now.");
        return List.of(
                "&7Live duels: &a" + liveMatches,
                "&7Arenas: &a" + arenas,
                "",
                "&7Click a duelist to see the stakes",
                "&7and both players' statistics.");
    }

    public String getLiveCountOrOne() {
        return String.valueOf(Math.max(1, Math.min(64, liveMatches)));
    }
}
