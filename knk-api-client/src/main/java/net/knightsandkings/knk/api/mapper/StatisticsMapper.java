package net.knightsandkings.knk.api.mapper;

import java.util.List;
import java.util.UUID;

import net.knightsandkings.knk.api.dto.StatisticsDtos;
import net.knightsandkings.knk.core.domain.statistics.StatisticVisibility;
import net.knightsandkings.knk.core.domain.statistics.StatisticsBatch;
import net.knightsandkings.knk.core.domain.statistics.StatisticsBatchResult;
import net.knightsandkings.knk.core.domain.statistics.StatisticsCatalog;
import net.knightsandkings.knk.core.domain.statistics.StatisticsVisibilitySettings;

/** Statistics DTOs ↔ domain (KNG-34). */
public final class StatisticsMapper {
    private StatisticsMapper() {}

    public static StatisticsDtos.Batch toDto(StatisticsBatch batch, String serverName, String pluginVersion) {
        return new StatisticsDtos.Batch(
                batch.batchId().toString(),
                serverName,
                pluginVersion,
                batch.sentAt().toString(),
                batch.sessions().stream().map(s -> new StatisticsDtos.SessionEntry(s.type().apiName(), s.sessionKey().toString(),
                        s.userId(), s.at().toString(), s.endReason() == null ? null : s.endReason().name())).toList(),
                batch.durations().stream().map(d -> new StatisticsDtos.DurationEntry(d.sessionKey().toString(), d.userId(),
                        d.metric(), d.from().toString(), d.to().toString())).toList(),
                batch.counters().stream().map(StatisticsMapper::value).toList(),
                batch.records().stream().map(StatisticsMapper::value).toList(),
                batch.pvpKills().stream().map(k -> new StatisticsDtos.PvpKillEntry(k.killerUserId(), k.victimUserId(),
                        k.context(), k.occurredAt().toString())).toList());
    }

    private static StatisticsDtos.ValueEntry value(StatisticsBatch.ValueEntry v) {
        return new StatisticsDtos.ValueEntry(v.userId(), v.metric(), v.context(), round4(v.value()), v.occurredAt().toString());
    }

    /** The API keeps four decimals; sending more only adds noise to the request. */
    static double round4(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    public static StatisticsBatchResult fromDto(StatisticsDtos.BatchResult dto, UUID sentBatchId) {
        if (dto == null) {
            return new StatisticsBatchResult(sentBatchId, false, 0, List.of());
        }
        UUID batchId = sentBatchId;
        if (dto.batchId() != null) {
            try {
                batchId = UUID.fromString(dto.batchId());
            } catch (IllegalArgumentException ignored) {
                // keep the id that was sent
            }
        }
        List<StatisticsBatchResult.Rejection> rejected = dto.rejected() == null ? List.of() : dto.rejected().stream()
                .map(r -> new StatisticsBatchResult.Rejection(r.section(), r.index() == null ? -1 : r.index(), r.code()))
                .toList();
        return new StatisticsBatchResult(batchId, Boolean.TRUE.equals(dto.duplicate()),
                dto.accepted() == null ? 0 : dto.accepted(), rejected);
    }

    public static StatisticsCatalog fromDto(StatisticsDtos.Catalog dto) {
        if (dto == null) {
            return new StatisticsCatalog(null, List.of(), List.of(), List.of());
        }
        return new StatisticsCatalog(dto.timeZone(),
                dto.contexts() == null ? List.of() : dto.contexts(),
                dto.settings() == null ? List.of() : dto.settings().stream().map(s -> new StatisticsCatalog.Setting(
                        s.settingKey(), s.group(), s.label(), Boolean.TRUE.equals(s.contextual()))).toList(),
                dto.groups() == null ? List.of() : dto.groups().stream().map(g -> new StatisticsCatalog.Group(
                        g.key(), g.label(), g.settingKeys())).toList());
    }

    public static StatisticsVisibilitySettings fromDto(StatisticsDtos.Visibility dto) {
        if (dto == null) {
            return null;
        }
        return new StatisticsVisibilitySettings(dto.userId() == null ? 0 : dto.userId(),
                Boolean.TRUE.equals(dto.friendsAvailable()),
                dto.settings() == null ? List.of() : dto.settings().stream().map(s -> new StatisticsVisibilitySettings.Setting(
                        s.settingKey(), s.group(), s.label(), Boolean.TRUE.equals(s.contextual()),
                        StatisticVisibility.fromApiName(s.visibility()),
                        s.contexts() == null ? List.of() : s.contexts().stream().map(c -> new StatisticsVisibilitySettings.ContextValue(
                                c.context(), StatisticVisibility.fromApiName(c.visibility()), Boolean.TRUE.equals(c.isOverride())))
                                .toList()))
                        .toList());
    }

    public static StatisticsDtos.VisibilityUpdate toDto(List<StatisticsVisibilitySettings.Change> changes) {
        return new StatisticsDtos.VisibilityUpdate(changes.stream().map(c -> new StatisticsDtos.VisibilityChange(
                c.settingKey(), c.context(), c.expected().apiName(), c.visibility().apiName())).toList());
    }
}
