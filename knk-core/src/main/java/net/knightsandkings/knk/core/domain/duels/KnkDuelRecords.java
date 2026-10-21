package net.knightsandkings.knk.core.domain.duels;

import net.knightsandkings.knk.core.duel.GearMode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the plugin sends to and reads from knk-web-api's {@code /api/DuelMatches} (arena duels DESIGN.md §4.3,
 * §6.5, §7, §10.3; KNG-213). Plain records for {@link net.knightsandkings.knk.core.ports.api.DuelMatchesApi};
 * the api-client maps them to the wire DTOs. Coin amounts are longs; ids are the API's ints.
 */
public final class KnkDuelRecords {
    private KnkDuelRecords() {}

    /** One staked (or spoils) stack, as an audit summary: the spool keeps the item itself (DESIGN §7.2). */
    public record ItemSummary(int ownerUserId, String material, int amount, String name, String grade, Long instanceId) {
        public ItemSummary {
            if (material == null || material.isBlank()) throw new IllegalArgumentException("material is required");
            if (amount < 1) throw new IllegalArgumentException("amount must be at least 1");
        }
    }

    /**
     * Lock-in (DESIGN §6.5 step 3), also used for the advisory {@code check}. {@code token} is the match token;
     * the API is idempotent on it (Idempotency-Key {@code duel-lock:<token>}). Gems can't be staked, so there
     * is no gem field; the API refuses gem amounts anyway.
     */
    public record LockRequest(UUID token, int arenaId, int challengerUserId, int opponentUserId, GearMode gearMode,
                              Integer fixedKitId, long challengerCoins, long opponentCoins, List<ItemSummary> stakeItems,
                              int offerCount, Instant lockedAt) {
        public LockRequest {
            if (token == null) throw new IllegalArgumentException("token is required");
            if (gearMode == null) throw new IllegalArgumentException("gearMode is required");
            stakeItems = stakeItems == null ? List.of() : List.copyOf(stakeItems);
        }
    }

    /** One side's fight counters; the gear fields are measured at fight start in own-gear modes (null otherwise). */
    public record SideTelemetry(double damageDealt, double damageTaken, int hits, int attackAttempts,
                                Integer armorPoints, String mainHand, Boolean hadWeapon) {}

    /** DESIGN §10.3: the API derives the review signals from this. */
    public record Telemetry(double durationSeconds, SideTelemetry challenger, SideTelemetry opponent) {}

    /** A decisive result. {@code forfeit}: the loser quit from BATTLE_COUNTDOWN on (D10). */
    public record Completion(int winnerUserId, boolean forfeit, Telemetry telemetry, List<ItemSummary> spoils, Instant endedAt) {
        public Completion {
            spoils = spoils == null ? List.of() : List.copyOf(spoils);
        }
    }

    /** Created → InProgress; the kits are each side's KIT_CHOICE pick (null otherwise). */
    public record FightStart(int battlegroundId, Integer challengerKitId, Integer opponentKitId, Instant startedAt) {}

    /** The API's match status. */
    public enum Status { CREATED, IN_PROGRESS, COMPLETED, CANCELLED, ABORTED, UNKNOWN }

    /** Staff review of a decisive value-moving result (DESIGN §10.3). */
    public enum Review { NONE, FLAGGED, HELD, RELEASED, VOIDED, UNKNOWN }

    /** What to do with stakes and spoils the spool hasn't delivered yet after a staff action. */
    public enum UndeliveredItems { NONE, RETURN_TO_OWNERS, DESTROY, UNKNOWN }

    /**
     * The money side as the plugin acts on it: PAID → give the winner the items (and spoils); HELD → keep
     * everything in the spool (HELD state) and tell both players it is under review; REFUNDED → each side gets
     * its own items back; NONE → the match is still open.
     */
    public enum Settlement { NONE, PAID, HELD, REFUNDED, UNKNOWN }

    /** A match as the API stores it. {@code endReason} is the API's PascalCase name (see DuelEndReason.apiName()). */
    public record MatchRecord(int id, UUID token, int arenaId, Integer battlegroundId, int challengerUserId, int opponentUserId,
                              GearMode gearMode, Integer fixedKitId, Integer challengerKitId, Integer opponentKitId,
                              Status status, String endReason, Integer winnerUserId, long challengerCoins, long opponentCoins,
                              int feePercent, long feeCoins, boolean valueMoving, Review review, Integer reviewTier,
                              List<String> reviewSignals, UndeliveredItems undeliveredItems, List<ItemSummary> stakeItems,
                              List<ItemSummary> spoilsItems, Instant createdAt, Instant endedAt) {
        public MatchRecord {
            reviewSignals = reviewSignals == null ? List.of() : List.copyOf(reviewSignals);
            stakeItems = stakeItems == null ? List.of() : List.copyOf(stakeItems);
            spoilsItems = spoilsItems == null ? List.of() : List.copyOf(spoilsItems);
        }

        /** The open states, in which the stakes sit in SYS_DUEL and the spool. */
        public boolean isOpen() {
            return status == Status.CREATED || status == Status.IN_PROGRESS;
        }
    }

    /** What a revert or confiscation recovered (DESIGN §10.3). */
    public record RevertSummary(boolean confiscated, long payoutCoins, long recoveredCoins, long feeReversedCoins,
                                long returnedToLoserCoins, long returnedToWinnerCoins, long confiscatedCoins, long missingCoins,
                                UndeliveredItems undeliveredItems) {}

    /** The answer to complete, cancel and the staff actions. {@code alreadyDone}: a retry; nothing changed. */
    public record Result(MatchRecord match, Settlement settlement, boolean alreadyDone, RevertSummary revert) {}

    /** One rule a duel breaks, with the API's code (e.g. {@code PairCooldown}) and a player-facing message. */
    public record Refusal(String code, String message) {}

    /** {@code POST check}: whether a lock-in with this body would pass now. */
    public record CheckResult(boolean allowed, boolean valueMoving, long feeCoins, int feePercent, List<Refusal> refusals) {
        public CheckResult {
            refusals = refusals == null ? List.of() : List.copyOf(refusals);
        }
    }
}
