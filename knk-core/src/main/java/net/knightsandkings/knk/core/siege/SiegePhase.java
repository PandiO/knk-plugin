package net.knightsandkings.knk.core.siege;

/**
 * A lobby's phase (DESIGN §5.4). Replaces v2's five independent booleans
 * ({@code matchmaking}/{@code progress}/{@code inHub}/{@code cooldown}/{@code finished}).
 *
 * <pre>
 * DISABLED -start-> MATCHMAKING -T-hub-> HUB -T-0-> IN_PROGRESS -end-> POST_MATCH -T-0-> COOLDOWN -T-0-> MATCHMAKING ...
 *                                                             \-end (no post-match time)-> ENDING -> COOLDOWN
 *                   (not enough players / no scenario at the draw or start) ----------> COOLDOWN
 * </pre>
 * {@link #HUB} is the last {@code hubSecondsBeforeStart} seconds of matchmaking: members are at the
 * hub and joining is closed. {@link #POST_MATCH} (alpha run 1, PandiO/knk-workspace#198) follows a match that
 * ended normally: members wait at the hub with the result for {@code postMatchSeconds} and are then restored.
 */
public enum SiegePhase {
    DISABLED,
    MATCHMAKING,
    HUB,
    IN_PROGRESS,
    ENDING,
    /**
     * After a match that ended normally (#198): members are back at the scenario hub with the result, still
     * snapshotted (inventory guards, command filter, no combat), until the post-match time runs out or they
     * {@code /siege leave}. The match is already recorded and its rewards granted.
     */
    POST_MATCH,
    COOLDOWN;

    /** Players may join only while matchmaking, before the hub teleport (DESIGN §6.2). */
    public boolean isJoinable() {
        return this == MATCHMAKING;
    }

    /**
     * Members are away from their normal state: snapshot taken, command filter, inventory guards
     * and area lockdown apply (DESIGN §6.9, §8.5, §9.3). Includes the post-match wait at the hub (#198):
     * members still carry their match inventory until they are restored.
     */
    public boolean isMatchActive() {
        return this == HUB || this == IN_PROGRESS || this == POST_MATCH;
    }

    /**
     * Members discover no domains (domain-discovery DESIGN.md §3.6): from the hub teleport until they
     * are restored after the match. Queued members (MATCHMAKING) are still in the normal world and
     * keep discovering (developer decision 2026-09-27).
     */
    public boolean blocksDiscovery() {
        return this == HUB || this == IN_PROGRESS || this == ENDING || this == POST_MATCH || this == COOLDOWN;
    }

    /** No match is being prepared or played: new configuration may be applied (DESIGN §5.1). */
    public boolean isBetweenMatches() {
        return this == DISABLED || this == COOLDOWN;
    }
}
