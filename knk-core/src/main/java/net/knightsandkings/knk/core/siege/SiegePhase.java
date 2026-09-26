package net.knightsandkings.knk.core.siege;

/**
 * A lobby's phase (DESIGN §5.4). Replaces v2's five independent booleans
 * ({@code matchmaking}/{@code progress}/{@code inHub}/{@code cooldown}/{@code finished}).
 *
 * <pre>
 * DISABLED -start-> MATCHMAKING -T-hub-> HUB -T-0-> IN_PROGRESS -end-> ENDING -> COOLDOWN -T-0-> MATCHMAKING ...
 *                   (not enough players / no scenario at the draw or start) ----------> COOLDOWN
 * </pre>
 * {@link #HUB} is the last {@code hubSecondsBeforeStart} seconds of matchmaking: members are at the
 * hub and joining is closed.
 */
public enum SiegePhase {
    DISABLED,
    MATCHMAKING,
    HUB,
    IN_PROGRESS,
    ENDING,
    COOLDOWN;

    /** Players may join only while matchmaking, before the hub teleport (DESIGN §6.2). */
    public boolean isJoinable() {
        return this == MATCHMAKING;
    }

    /**
     * Members are away from their normal state: snapshot taken, command filter, inventory guards
     * and area lockdown apply (DESIGN §6.9, §8.5, §9.3).
     */
    public boolean isMatchActive() {
        return this == HUB || this == IN_PROGRESS;
    }

    /** No match is being prepared or played: new configuration may be applied (DESIGN §5.1). */
    public boolean isBetweenMatches() {
        return this == DISABLED || this == COOLDOWN;
    }
}
