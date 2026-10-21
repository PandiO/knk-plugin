package net.knightsandkings.knk.core.duel;

/**
 * A match's phase (DESIGN §5, §5.1). Negotiation comes before the match and lives in
 * {@link DuelOfferBook}; a {@link DuelStateMachine} starts in {@link #LOCKING} when an offer is accepted.
 *
 * <pre>
 * LOCKING -lock ok-> TRAVELLING -both arrived-> WARMUP -battleground-> BATTLE_COUNTDOWN -> FIGHT -result-> ENDING -linger-> CLOSED
 *    (lock refused, cancel, abort, from any phase before ENDING) -------------------------------------------------------> CLOSED
 * </pre>
 */
public enum DuelPhase {
    /** Items to the spool and the API create + debit (a few hundred ms). */
    LOCKING,
    /** Both lobby teleports (engine kind {@code ARENA}, player warmup). */
    TRAVELLING,
    /** In the lobby; kit choice; then waiting for a free battleground. */
    WARMUP,
    /** On the spawn blocks, frozen, damage locked. */
    BATTLE_COUNTDOWN,
    FIGHT,
    /** The result is in; the winner lingers, then both are sent to the exit. */
    ENDING,
    /** Over; nothing more happens. */
    CLOSED;

    /**
     * A match participant (LOCKING..ENDING): the combat rule, teleport restriction, inventory guards and
     * held deliveries apply (DESIGN §8.1, §8.3, §8.4).
     */
    public boolean isParticipating() {
        return this != CLOSED;
    }

    /** Quitting from here on is a forfeit; before, it cancels with a full refund (D10). */
    public boolean quitForfeits() {
        return this == BATTLE_COUNTDOWN || this == FIGHT;
    }

    /** The duelists are on a battleground (entry exemption and exit confinement, DESIGN §8.2). */
    public boolean onBattleground() {
        return this == BATTLE_COUNTDOWN || this == FIGHT;
    }

    /** Counted against {@code Arena.MaxQueuedMatches} (DESIGN §5.2): accepted, not yet on a battleground. */
    public boolean countsAsQueued() {
        return this == LOCKING || this == TRAVELLING || this == WARMUP;
    }
}
