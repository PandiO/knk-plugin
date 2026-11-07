package net.knightsandkings.knk.core.survival.mysterybox;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One in-world Mystery Box roll from the charge to its claim or forfeit (DESIGN §8a; S39, S59). Pure state: the Paper side
 * reports the animation end, right-clicks, deaths, leaves, the match end and the clock; this decides what happens.
 * <pre>
 * ROLLING ──animationDone──▶ SHOWN ──claim(roller)──▶ CLAIMED
 *    │                         │
 *    └─ died/left/matchEnded ──┴─ timeout/died/left/matchEnded ──▶ FORFEITED(reason)
 * </pre>
 */
public final class MysteryBoxRollState {

    public enum Phase { ROLLING, SHOWN, CLAIMED, FORFEITED }

    public enum ForfeitReason { TIMEOUT, ROLLER_DIED, ROLLER_LEFT, MATCH_ENDED }

    public enum ClaimOutcome { CLAIMED, NOT_ROLLER, NOT_SHOWN_YET, ALREADY_OVER }

    private final String rollId;
    private final UUID roller;
    private final long claimTimeoutMillis;
    private Phase phase = Phase.ROLLING;
    private long shownAtMillis = -1;
    private ForfeitReason forfeitReason;

    public MysteryBoxRollState(String rollId, UUID roller, long claimTimeoutMillis) {
        this.rollId = Objects.requireNonNull(rollId, "rollId");
        this.roller = Objects.requireNonNull(roller, "roller");
        this.claimTimeoutMillis = claimTimeoutMillis > 0 ? claimTimeoutMillis : MysteryBoxSettings.DEFAULT_CLAIM_TIMEOUT_MILLIS;
    }

    public String rollId() {
        return rollId;
    }

    public UUID roller() {
        return roller;
    }

    public Phase phase() {
        return phase;
    }

    public boolean over() {
        return phase == Phase.CLAIMED || phase == Phase.FORFEITED;
    }

    public Optional<ForfeitReason> forfeitReason() {
        return Optional.ofNullable(forfeitReason);
    }

    public long claimTimeoutMillis() {
        return claimTimeoutMillis;
    }

    /** The reel stopped on the result: the claim window opens. */
    public boolean animationDone(long nowMillis) {
        if (phase != Phase.ROLLING) return false;
        phase = Phase.SHOWN;
        shownAtMillis = nowMillis;
        return true;
    }

    /** Milliseconds left to claim (0 when not shown or over). */
    public long remainingClaimMillis(long nowMillis) {
        if (phase != Phase.SHOWN) return 0;
        return Math.max(0, shownAtMillis + claimTimeoutMillis - nowMillis);
    }

    public ClaimOutcome claim(UUID by, long nowMillis) {
        if (over()) return ClaimOutcome.ALREADY_OVER;
        if (!roller.equals(by)) return ClaimOutcome.NOT_ROLLER;
        if (phase != Phase.SHOWN) return ClaimOutcome.NOT_SHOWN_YET;
        if (nowMillis >= shownAtMillis + claimTimeoutMillis) {
            forfeit(ForfeitReason.TIMEOUT);
            return ClaimOutcome.ALREADY_OVER;
        }
        phase = Phase.CLAIMED;
        return ClaimOutcome.CLAIMED;
    }

    /** The clock moved: a shown item past its timeout is forfeited. */
    public Optional<ForfeitReason> tick(long nowMillis) {
        if (phase == Phase.SHOWN && nowMillis >= shownAtMillis + claimTimeoutMillis) {
            forfeit(ForfeitReason.TIMEOUT);
            return Optional.of(ForfeitReason.TIMEOUT);
        }
        return Optional.empty();
    }

    /** The roller died (became a ghost), left, or the match ended: forfeited in ROLLING and SHOWN alike (S59). */
    public boolean forfeit(ForfeitReason reason) {
        if (over()) return false;
        phase = Phase.FORFEITED;
        forfeitReason = Objects.requireNonNull(reason, "reason");
        return true;
    }

    /** A death/leave of someone else leaves the roll alone. */
    public boolean concerns(UUID player) {
        return roller.equals(player);
    }
}
