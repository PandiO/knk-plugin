package net.knightsandkings.knk.core.domain.statistics;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One plugin flush of player statistics (KNG-34): the domain mirror of knk-web-api's
 * {@code StatisticsBatchDto} ({@code POST api/statistics/batches}). Idempotent by {@link #batchId()}:
 * a replay of the same id applies nothing. The API applies session starts, then durations, then
 * session ends, counters, records and PvP kills, so a session's start may travel in the same batch
 * as its first duration.
 */
public record StatisticsBatch(
        UUID batchId,
        Instant sentAt,
        List<SessionEntry> sessions,
        List<DurationEntry> durations,
        List<ValueEntry> counters,
        List<ValueEntry> records,
        List<PvpKillEntry> pvpKills
) {
    /** The API's limit on the entries of one batch (all lists together). */
    public static final int MAX_ENTRIES = 2_000;

    public StatisticsBatch {
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(sentAt, "sentAt");
        sessions = sessions == null ? List.of() : List.copyOf(sessions);
        durations = durations == null ? List.of() : List.copyOf(durations);
        counters = counters == null ? List.of() : List.copyOf(counters);
        records = records == null ? List.of() : List.copyOf(records);
        pvpKills = pvpKills == null ? List.of() : List.copyOf(pvpKills);
    }

    public int entryCount() {
        return sessions.size() + durations.size() + counters.size() + records.size() + pvpKills.size();
    }

    public boolean isEmpty() {
        return entryCount() == 0;
    }

    public enum SessionType {
        START("start"),
        END("end");

        private final String apiName;

        SessionType(String apiName) {
            this.apiName = apiName;
        }

        public String apiName() {
            return apiName;
        }

        public static SessionType fromApiName(String name) {
            for (SessionType type : values()) {
                if (type.apiName.equalsIgnoreCase(name)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown session entry type '" + name + "'");
        }
    }

    /** Why a session ended; the names are the API's {@code PlayerSessionEndReason} values the plugin may send. */
    public enum EndReason {
        Quit,
        ServerStop,
        Kick
    }

    /** {@code endReason} only on an {@link SessionType#END} entry. */
    public record SessionEntry(SessionType type, UUID sessionKey, int userId, Instant at, EndReason endReason) {
        public SessionEntry {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(sessionKey, "sessionKey");
            Objects.requireNonNull(at, "at");
            if (type == SessionType.START) {
                endReason = null;
            } else if (endReason == null) {
                endReason = EndReason.Quit;
            }
        }

        public static SessionEntry start(UUID sessionKey, int userId, Instant at) {
            return new SessionEntry(SessionType.START, sessionKey, userId, at, null);
        }

        public static SessionEntry end(UUID sessionKey, int userId, Instant at, EndReason reason) {
            return new SessionEntry(SessionType.END, sessionKey, userId, at, reason);
        }
    }

    /** An {@code active_playtime} or {@code afk_time} interval {@code [from, to)} of a session. */
    public record DurationEntry(UUID sessionKey, int userId, String metric, Instant from, Instant to) {
        public DurationEntry {
            Objects.requireNonNull(sessionKey, "sessionKey");
            Objects.requireNonNull(metric, "metric");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    /** A counter (summed) or record (max) value; {@code context} is {@code ""} for non-contextual metrics. */
    public record ValueEntry(int userId, String metric, String context, double value, Instant occurredAt) {
        public ValueEntry {
            Objects.requireNonNull(metric, "metric");
            Objects.requireNonNull(occurredAt, "occurredAt");
            context = context == null ? "" : context;
        }
    }

    public record PvpKillEntry(int killerUserId, int victimUserId, String context, Instant occurredAt) {
        public PvpKillEntry {
            Objects.requireNonNull(occurredAt, "occurredAt");
            context = context == null ? "" : context;
        }
    }
}
