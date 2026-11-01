package net.knightsandkings.knk.paper.utils;

import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;

/**
 * The one place the plugin's board owners ({@link ScoreboardUtil}'s shared rank board, the siege's per-team boards)
 * set and read a player's scoreboard, so a temporary per-viewer board laid over it (PandiO/knk-workspace#169: the
 * flash_chaos name-tag blackout) is neither replaced by nor remembered instead of the real one.
 * <p>
 * Without an overlay this is plain {@link Player#setScoreboard} / {@link Player#getScoreboard}. Main thread.
 */
public final class ViewerScoreboards {

    /** A temporary board shown in place of the player's own one. */
    public interface Overlay {
        /**
         * The player's own board becomes {@code board} while the overlay is shown: return true when the overlay takes
         * it as the board to mirror and hand back later, false to let it be set on the player directly.
         */
        boolean redirect(Player player, Scoreboard board);

        /** The player's own board behind {@code shown} when {@code shown} is the overlay's, else {@code shown}. */
        Scoreboard underlying(Player player, Scoreboard shown);
    }

    private static final Overlay NONE = new Overlay() {
        @Override
        public boolean redirect(Player player, Scoreboard board) {
            return false;
        }

        @Override
        public Scoreboard underlying(Player player, Scoreboard shown) {
            return shown;
        }
    };

    private static volatile Overlay overlay = NONE;

    private ViewerScoreboards() {
    }

    public static void setOverlay(Overlay value) {
        overlay = value != null ? value : NONE;
    }

    /** Gives the player {@code board} as their own board (behind an overlay while one is shown). */
    public static void set(Player player, Scoreboard board) {
        if (player == null || board == null) {
            return;
        }
        if (!overlay.redirect(player, board)) {
            player.setScoreboard(board);
        }
    }

    /** The player's own board, looking through an overlay. */
    public static Scoreboard current(Player player) {
        if (player == null) {
            return null;
        }
        return overlay.underlying(player, player.getScoreboard());
    }
}
