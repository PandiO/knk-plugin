package net.knightsandkings.knk.api.mapper;

import net.knightsandkings.knk.api.dto.DuelMatchDtos;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.ItemSummary;
import net.knightsandkings.knk.core.duel.DuelEndReason;
import net.knightsandkings.knk.core.duel.GearMode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Core duel records ↔ {@link DuelMatchDtos} (KNG-213). Unknown enum strings map to UNKNOWN, never throw. */
public final class DuelMatchMapper {
    private DuelMatchMapper() {}

    // ---- requests ----

    public static DuelMatchDtos.CreateRequest toCreateRequest(KnkDuelRecords.LockRequest r) {
        return new DuelMatchDtos.CreateRequest(r.token().toString(), r.arenaId(), r.challengerUserId(), r.opponentUserId(),
                r.gearMode().name(), r.fixedKitId(), r.challengerCoins(), r.opponentCoins(), toItems(r.stakeItems()),
                r.offerCount(), iso(r.lockedAt()));
    }

    public static DuelMatchDtos.StartFightRequest toStartRequest(KnkDuelRecords.FightStart s) {
        return new DuelMatchDtos.StartFightRequest(s.battlegroundId(), s.challengerKitId(), s.opponentKitId(), iso(s.startedAt()));
    }

    public static DuelMatchDtos.CompleteRequest toCompleteRequest(KnkDuelRecords.Completion c) {
        return new DuelMatchDtos.CompleteRequest(c.winnerUserId(),
                (c.forfeit() ? DuelEndReason.FORFEIT : DuelEndReason.WIN).apiName(),
                toTelemetry(c.telemetry()), c.spoils().isEmpty() ? null : toItems(c.spoils()), iso(c.endedAt()));
    }

    public static DuelMatchDtos.CancelRequest toCancelRequest(DuelEndReason reason, KnkDuelRecords.Telemetry telemetry) {
        if (reason == null || reason.isDecisive() || reason.apiName() == null) {
            throw new IllegalArgumentException(reason + " doesn't end a match through cancel");
        }
        return new DuelMatchDtos.CancelRequest(reason.apiName(), toTelemetry(telemetry), null);
    }

    public static DuelMatchDtos.Telemetry toTelemetry(KnkDuelRecords.Telemetry t) {
        if (t == null) return null;
        return new DuelMatchDtos.Telemetry(t.durationSeconds(), toSide(t.challenger()), toSide(t.opponent()));
    }

    private static DuelMatchDtos.Side toSide(KnkDuelRecords.SideTelemetry s) {
        if (s == null) return null;
        return new DuelMatchDtos.Side(s.damageDealt(), s.damageTaken(), s.hits(), s.attackAttempts(), s.armorPoints(), s.mainHand(), s.hadWeapon());
    }

    private static List<DuelMatchDtos.Item> toItems(List<ItemSummary> items) {
        return items.stream()
                .map(i -> new DuelMatchDtos.Item(i.ownerUserId(), i.material(), i.amount(), i.name(), i.grade(), i.instanceId()))
                .toList();
    }

    // ---- responses ----

    public static KnkDuelRecords.MatchRecord toCore(DuelMatchDtos.Match m) {
        if (m == null || m.id() == null) return null;
        return new KnkDuelRecords.MatchRecord(m.id(), uuid(m.publicToken()), orZero(m.arenaId()), m.battlegroundId(),
                orZero(m.challengerUserId()), orZero(m.opponentUserId()), gearMode(m.gearMode()), m.fixedKitId(),
                m.challengerKitId(), m.opponentKitId(), parse(KnkDuelRecords.Status.class, m.status(), KnkDuelRecords.Status.UNKNOWN),
                m.endReason(), m.winnerUserId(), orZero(m.challengerCoins()), orZero(m.opponentCoins()), orZero(m.feePercent()),
                orZero(m.feeCoins()), Boolean.TRUE.equals(m.valueMoving()),
                parse(KnkDuelRecords.Review.class, m.review(), KnkDuelRecords.Review.UNKNOWN), m.reviewTier(), m.reviewSignals(),
                parse(KnkDuelRecords.UndeliveredItems.class, m.undeliveredItems(), KnkDuelRecords.UndeliveredItems.UNKNOWN),
                fromItems(m.stakeItems()), fromItems(m.spoilsItems()), instant(m.createdAt()), instant(m.endedAt()));
    }

    public static KnkDuelRecords.Result toCore(DuelMatchDtos.Result r) {
        if (r == null) return null;
        KnkDuelRecords.RevertSummary revert = null;
        if (r.revert() != null) {
            var v = r.revert();
            revert = new KnkDuelRecords.RevertSummary(Boolean.TRUE.equals(v.confiscated()), orZero(v.payoutCoins()),
                    orZero(v.recoveredCoins()), orZero(v.feeReversedCoins()), orZero(v.returnedToLoserCoins()),
                    orZero(v.returnedToWinnerCoins()), orZero(v.confiscatedCoins()), orZero(v.missingCoins()),
                    parse(KnkDuelRecords.UndeliveredItems.class, v.undeliveredItems(), KnkDuelRecords.UndeliveredItems.UNKNOWN));
        }
        return new KnkDuelRecords.Result(toCore(r.match()),
                parse(KnkDuelRecords.Settlement.class, r.settlement(), KnkDuelRecords.Settlement.UNKNOWN),
                Boolean.TRUE.equals(r.alreadyDone()), revert);
    }

    public static KnkDuelRecords.CheckResult toCore(DuelMatchDtos.CheckResult c) {
        if (c == null) return new KnkDuelRecords.CheckResult(false, false, 0, 0, List.of());
        var refusals = c.refusals() == null ? List.<KnkDuelRecords.Refusal>of()
                : c.refusals().stream().map(r -> new KnkDuelRecords.Refusal(r.code(), r.message())).toList();
        return new KnkDuelRecords.CheckResult(Boolean.TRUE.equals(c.allowed()), Boolean.TRUE.equals(c.valueMoving()),
                orZero(c.feeCoins()), orZero(c.feePercent()), refusals);
    }

    private static List<ItemSummary> fromItems(List<DuelMatchDtos.Item> items) {
        if (items == null) return List.of();
        return items.stream()
                .filter(i -> i.material() != null && !i.material().isBlank() && i.amount() != null && i.amount() > 0)
                .map(i -> new ItemSummary(orZero(i.ownerUserId()), i.material(), i.amount(), i.name(), i.grade(), i.instanceId()))
                .toList();
    }

    /** "InProgress" → IN_PROGRESS, "OWN_GEAR_KEEP" stays; unknown → fallback. */
    static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) return fallback;
        String constant = value.contains("_") ? value.toUpperCase(Locale.ROOT)
                : value.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(type, constant);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static GearMode gearMode(String value) {
        try {
            return value == null ? GearMode.OWN_GEAR_KEEP : GearMode.valueOf(value);
        } catch (IllegalArgumentException e) {
            return GearMode.OWN_GEAR_KEEP;
        }
    }

    private static UUID uuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant instant(String value) {
        try {
            return value == null ? null : Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String iso(Instant value) {
        return value == null ? null : value.toString();
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static long orZero(Long value) {
        return value == null ? 0 : value;
    }
}
