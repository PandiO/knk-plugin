package net.knightsandkings.knk.api.mapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import net.knightsandkings.knk.api.dto.StatisticsDtos;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.statistics.PlayerStatistics;
import net.knightsandkings.knk.core.domain.statistics.TitleChange;
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

    public static PlayerStatistics fromDto(StatisticsDtos.PlayerStatistics dto) {
        if (dto == null) {
            return null;
        }
        StatisticsDtos.Profile p = dto.profile();
        PlayerStatistics.Profile profile = p == null ? null : new PlayerStatistics.Profile(p.titleName(),
                orZero(p.experience()), orZero(p.coins()), orZero(p.gems()), instant(p.firstJoinedAt()),
                p.activePlaytimeSeconds() == null ? 0L : p.activePlaytimeSeconds(), p.afkSeconds() == null ? 0L : p.afkSeconds());
        List<PlayerStatistics.Metric> metrics = dto.metrics() == null ? List.of() : dto.metrics().stream()
                .filter(m -> m.key() != null)
                .map(m -> new PlayerStatistics.Metric(m.key(), m.settingKey(), m.value(), m.unit(), m.aggregation(),
                        m.contexts() == null ? List.of() : m.contexts().stream()
                                .filter(c -> c.context() != null)
                                .map(c -> new PlayerStatistics.ContextValue(c.context(), c.value() == null ? 0d : c.value()))
                                .toList()))
                .toList();
        StatisticsDtos.Economy e = dto.economy();
        StatisticsDtos.Discoveries d = dto.discoveries();
        return new PlayerStatistics(orZero(dto.userId()), dto.username(), dto.period(), date(dto.periodStart()),
                date(dto.periodEndExclusive()), dto.timeZone(), dto.viewer(), profile, metrics,
                e == null ? null : new PlayerStatistics.Economy(orZero(e.coinsEarned()), orZero(e.coinsSpent()),
                        orZero(e.gemsEarned()), orZero(e.gemsSpent())),
                d == null ? null : new PlayerStatistics.Discoveries(orZero(d.total()), orZero(d.towns()), orZero(d.districts()),
                        orZero(d.structures())));
    }

    public static Page<TitleChange> fromDto(StatisticsDtos.TitleHistoryPage dto) {
        if (dto == null || dto.items() == null) {
            return new Page<>(List.of(), 0, 1, 0);
        }
        List<TitleChange> items = dto.items().stream()
                .map(t -> new TitleChange(instant(t.changedAt()), t.fromTitleName(), t.toTitleName(), t.direction()))
                .toList();
        return new Page<>(items, dto.totalCount() == null ? items.size() : dto.totalCount(),
                dto.pageNumber() == null ? 1 : dto.pageNumber(), dto.pageSize() == null ? items.size() : dto.pageSize());
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    /** API dates are {@code yyyy-MM-dd}; null or unparseable → null. */
    static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** API instants are ISO-8601 UTC, with or without the offset (DateTime Kind.Utc → "Z"). */
    static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return java.time.LocalDateTime.parse(value).toInstant(java.time.ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    public static StatisticsDtos.VisibilityUpdate toDto(List<StatisticsVisibilitySettings.Change> changes) {
        return new StatisticsDtos.VisibilityUpdate(changes.stream().map(c -> new StatisticsDtos.VisibilityChange(
                c.settingKey(), c.context(), c.expected().apiName(), c.visibility().apiName())).toList());
    }
}
