package net.knightsandkings.knk.core.minigame;

import java.util.Objects;
import java.util.UUID;

/**
 * A player's membership of one minigame right now (arena duels DESIGN §11), as the plugin-wide hooks see it.
 *
 * @param playerId   the member
 * @param minigame   the minigame's id ({@link MinigameParticipation#id()}), e.g. {@code siege}
 * @param matchToken the runtime id of the match the member is in, or null before a match exists (a siege lobby in
 *                   matchmaking or at its hub)
 * @param stage      how far the member has left the open world
 */
public record Participation(UUID playerId, String minigame, String matchToken, Stage stage) {

    /**
     * How far a member has left the open world. The hooks read these instead of a minigame's own phases:
     * <ul>
     *   <li>{@link #QUEUED}: signed up, still playing in the open world (a siege lobby in matchmaking). Only
     *       navigation leaves them out.</li>
     *   <li>{@link #STAGING}: taken out of the open world, gear held, not fighting (a siege lobby at its hub).</li>
     *   <li>{@link #PLAYING}: in a running match; statistics go to the minigame's context.</li>
     *   <li>{@link #FINISHING}: the match is over and the member waits to be restored (siege ENDING, COOLDOWN).</li>
     * </ul>
     */
    public enum Stage {
        QUEUED,
        STAGING,
        PLAYING,
        FINISHING;

        /**
         * Away from their normal state, with their gear in a vault: teleports, {@code /back} after a death, the
         * own-scoreboard rule and held loot deliveries apply (siege {@code isMatchActive}: HUB, IN_PROGRESS).
         */
        public boolean isAway() {
            return this == STAGING || this == PLAYING;
        }

        /**
         * Out of the open world from the moment they are taken away until they are restored: no discovery, no
         * {@code /hub}, no domain-access respawn correction (siege {@code blocksDiscovery}).
         */
        public boolean hasLeftOpenWorld() {
            return this != QUEUED;
        }
    }

    public Participation {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(minigame, "minigame");
        Objects.requireNonNull(stage, "stage");
    }
}
