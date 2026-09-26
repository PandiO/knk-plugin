package net.knightsandkings.knk.api.mapper;

import net.knightsandkings.knk.api.dto.SiegeMatchDtos;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.Completion;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.Participant;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.ParticipantReward;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.RewardSummary;
import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Siege Phase 6: core match records ↔ {@code /api/siege-matches} DTOs. */
public final class SiegeMatchMapper {
    private SiegeMatchMapper() {}

    public static SiegeMatchDtos.StartRequest toStartRequest(List<Participant> participants) {
        List<SiegeMatchDtos.ParticipantStart> list = participants == null ? List.of() : participants.stream()
                .filter(Objects::nonNull)
                .map(p -> new SiegeMatchDtos.ParticipantStart(p.userId(), p.siegeTeamId()))
                .toList();
        return new SiegeMatchDtos.StartRequest(list);
    }

    public static SiegeMatchDtos.LeftRequest toLeftRequest(Instant leftAt) {
        return new SiegeMatchDtos.LeftRequest(iso(leftAt));
    }

    public static SiegeMatchDtos.CompleteRequest toCompleteRequest(Completion completion) {
        Objects.requireNonNull(completion, "completion");
        return new SiegeMatchDtos.CompleteRequest(
                completion.endReason().apiName(),
                completion.winningAllianceGroup(),
                completion.participants().stream()
                        .map(p -> new SiegeMatchDtos.ParticipantResult(p.userId(), p.siegeTeamId(), p.kills(), p.deaths(),
                                p.highestKillStreak(), p.captures()))
                        .toList(),
                completion.objectives().stream()
                        .map(o -> new SiegeMatchDtos.ObjectiveResult(o.objectiveId(), o.finalHolderTeamId(),
                                o.capturedByUserId(), iso(o.capturedAt())))
                        .toList());
    }

    public static SiegeMatchDtos.AbortRequest toAbortRequest(SiegeEndReason reason) {
        return new SiegeMatchDtos.AbortRequest((reason == null ? SiegeEndReason.SERVER_RESTART : reason).apiName());
    }

    /** The response's match id, falling back to the requested one when the body omits it. */
    public static RewardSummary toCore(SiegeMatchDtos.ResultResponse dto, long requestedMatchId) {
        if (dto == null) return new RewardSummary(requestedMatchId, List.of());
        List<ParticipantReward> rewards = dto.rewards() == null ? List.of() : dto.rewards().stream()
                .filter(r -> r != null && r.userId() != null)
                .map(r -> new ParticipantReward(
                        r.userId(),
                        r.presentAtEnd() == null || r.presentAtEnd(),
                        Boolean.TRUE.equals(r.won()),
                        orZero(r.holdingCount()),
                        orZero(r.captureCount()),
                        orZero(r.coins()),
                        orZero(r.experience()),
                        orZero(r.gems()),
                        r.baseCoins() == null ? orZero(r.coins()) : r.baseCoins(),
                        UsersMapper.mapRewardMultipliers(r.coinMultipliers())))
                .toList();
        long matchId = dto.matchId() == null ? requestedMatchId : dto.matchId();
        return new RewardSummary(matchId, Boolean.TRUE.equals(dto.alreadyCompleted()), rewards);
    }

    public static List<Long> toCore(SiegeMatchDtos.AbortUnfinishedResponse dto) {
        if (dto == null || dto.abortedMatchIds() == null) return List.of();
        return dto.abortedMatchIds().stream().filter(Objects::nonNull).map(Integer::longValue).toList();
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
