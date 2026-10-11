package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire DTOs for knk-web-api's {@code /api/statistics} endpoints (KNG-34), mirroring
 * {@code Dtos/StatisticsDtos.cs}. JSON is camelCase; enums go as the API's names ({@code "Nobody"},
 * {@code "ServerStop"}), session types as {@code "start"}/{@code "end"}; instants go as ISO-8601
 * strings (the client's ObjectMapper would otherwise write numeric timestamps, which the API's
 * DateTime binding rejects). Response types use boxed fields so a missing value maps to the
 * mapper's default.
 */
public final class StatisticsDtos {
    private StatisticsDtos() {}

    // ---- POST api/statistics/batches ----

    public record Batch(
            @JsonProperty("batchId") String batchId,
            @JsonProperty("serverName") String serverName,
            @JsonProperty("pluginVersion") String pluginVersion,
            @JsonProperty("sentAt") String sentAt,
            @JsonProperty("sessions") List<SessionEntry> sessions,
            @JsonProperty("durations") List<DurationEntry> durations,
            @JsonProperty("counters") List<ValueEntry> counters,
            @JsonProperty("records") List<ValueEntry> records,
            @JsonProperty("pvpKills") List<PvpKillEntry> pvpKills
    ) {}

    public record SessionEntry(
            @JsonProperty("type") String type,
            @JsonProperty("sessionKey") String sessionKey,
            @JsonProperty("userId") int userId,
            @JsonProperty("at") String at,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("endReason") String endReason
    ) {}

    public record DurationEntry(
            @JsonProperty("sessionKey") String sessionKey,
            @JsonProperty("userId") int userId,
            @JsonProperty("metric") String metric,
            @JsonProperty("from") String from,
            @JsonProperty("to") String to
    ) {}

    public record ValueEntry(
            @JsonProperty("userId") int userId,
            @JsonProperty("metric") String metric,
            @JsonProperty("context") String context,
            @JsonProperty("value") double value,
            @JsonProperty("occurredAt") String occurredAt
    ) {}

    public record PvpKillEntry(
            @JsonProperty("killerUserId") int killerUserId,
            @JsonProperty("victimUserId") int victimUserId,
            @JsonProperty("context") String context,
            @JsonProperty("occurredAt") String occurredAt
    ) {}

    public record BatchResult(
            @JsonProperty("batchId") String batchId,
            @JsonProperty("duplicate") Boolean duplicate,
            @JsonProperty("accepted") Integer accepted,
            @JsonProperty("rejected") List<RejectedEntry> rejected
    ) {}

    public record RejectedEntry(
            @JsonProperty("section") String section,
            @JsonProperty("index") Integer index,
            @JsonProperty("code") String code
    ) {}

    // ---- GET api/statistics/catalog ----

    public record Catalog(
            @JsonProperty("timeZone") String timeZone,
            @JsonProperty("contexts") List<String> contexts,
            @JsonProperty("settings") List<CatalogSetting> settings,
            @JsonProperty("groups") List<CatalogGroup> groups
    ) {}

    public record CatalogSetting(
            @JsonProperty("settingKey") String settingKey,
            @JsonProperty("group") String group,
            @JsonProperty("label") String label,
            @JsonProperty("contextual") Boolean contextual
    ) {}

    public record CatalogGroup(
            @JsonProperty("key") String key,
            @JsonProperty("label") String label,
            @JsonProperty("settingKeys") List<String> settingKeys
    ) {}

    // ---- GET/PUT api/statistics/users/{id}/visibility ----

    public record Visibility(
            @JsonProperty("userId") Integer userId,
            @JsonProperty("friendsAvailable") Boolean friendsAvailable,
            @JsonProperty("settings") List<VisibilitySetting> settings
    ) {}

    public record VisibilitySetting(
            @JsonProperty("settingKey") String settingKey,
            @JsonProperty("group") String group,
            @JsonProperty("label") String label,
            @JsonProperty("contextual") Boolean contextual,
            @JsonProperty("visibility") String visibility,
            @JsonProperty("contexts") List<VisibilityContext> contexts
    ) {}

    public record VisibilityContext(
            @JsonProperty("context") String context,
            @JsonProperty("visibility") String visibility,
            @JsonProperty("isOverride") Boolean isOverride
    ) {}

    public record VisibilityUpdate(@JsonProperty("changes") List<VisibilityChange> changes) {}

    public record VisibilityChange(
            @JsonProperty("settingKey") String settingKey,
            @JsonProperty("context") String context,
            @JsonProperty("expected") String expected,
            @JsonProperty("visibility") String visibility
    ) {}

    // ---- GET api/statistics/users/{id} (viewer-filtered) ----

    public record PlayerStatistics(
            @JsonProperty("userId") Integer userId,
            @JsonProperty("username") String username,
            @JsonProperty("period") String period,
            @JsonProperty("periodStart") String periodStart,
            @JsonProperty("periodEndExclusive") String periodEndExclusive,
            @JsonProperty("timeZone") String timeZone,
            @JsonProperty("viewer") String viewer,
            @JsonProperty("profile") Profile profile,
            @JsonProperty("metrics") List<Metric> metrics,
            @JsonProperty("economy") Economy economy,
            @JsonProperty("discoveries") Discoveries discoveries
    ) {}

    public record Profile(
            @JsonProperty("titleName") String titleName,
            @JsonProperty("experience") Integer experience,
            @JsonProperty("coins") Integer coins,
            @JsonProperty("gems") Integer gems,
            @JsonProperty("firstJoinedAt") String firstJoinedAt,
            @JsonProperty("activePlaytimeSeconds") Long activePlaytimeSeconds,
            @JsonProperty("afkSeconds") Long afkSeconds
    ) {}

    public record Metric(
            @JsonProperty("key") String key,
            @JsonProperty("settingKey") String settingKey,
            @JsonProperty("value") Double value,
            @JsonProperty("unit") String unit,
            @JsonProperty("aggregation") String aggregation,
            @JsonProperty("contexts") List<MetricContext> contexts
    ) {}

    public record MetricContext(
            @JsonProperty("context") String context,
            @JsonProperty("value") Double value
    ) {}

    public record Economy(
            @JsonProperty("coinsEarned") Long coinsEarned,
            @JsonProperty("coinsSpent") Long coinsSpent,
            @JsonProperty("gemsEarned") Long gemsEarned,
            @JsonProperty("gemsSpent") Long gemsSpent
    ) {}

    public record Discoveries(
            @JsonProperty("total") Integer total,
            @JsonProperty("towns") Integer towns,
            @JsonProperty("districts") Integer districts,
            @JsonProperty("structures") Integer structures
    ) {}

    // ---- GET api/statistics/users/{id}/title-history ----

    public record TitleHistoryPage(
            @JsonProperty("items") List<TitleChange> items,
            @JsonProperty("totalCount") Integer totalCount,
            @JsonProperty("pageNumber") Integer pageNumber,
            @JsonProperty("pageSize") Integer pageSize
    ) {}

    public record TitleChange(
            @JsonProperty("changedAt") String changedAt,
            @JsonProperty("fromTitleName") String fromTitleName,
            @JsonProperty("toTitleName") String toTitleName,
            @JsonProperty("direction") String direction
    ) {}

    /** The 409 body: {@code { error: "VisibilityConflict", message, current }}. */
    public record VisibilityConflict(
            @JsonProperty("error") String error,
            @JsonProperty("message") String message,
            @JsonProperty("current") Visibility current
    ) {}
}
