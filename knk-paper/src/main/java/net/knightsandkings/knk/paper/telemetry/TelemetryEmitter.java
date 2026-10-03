package net.knightsandkings.knk.paper.telemetry;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Pattern;

import net.knightsandkings.knk.core.telemetry.TelemetryBuffer;
import net.knightsandkings.knk.core.telemetry.TelemetryClientConfig;
import net.knightsandkings.knk.core.telemetry.TelemetryCorrelation;
import net.knightsandkings.knk.core.telemetry.TelemetryEvent;
import net.knightsandkings.knk.core.telemetry.TelemetryEventNames;

/**
 * Builds diagnostic events and puts them in the bounded {@link TelemetryBuffer} (KNG-34 link 6,
 * IMPLEMENTATION_PLAN.md §5.2). Cheap and non-blocking: no I/O, safe from any thread. Whether an
 * event is kept follows the API's {@link TelemetryClientConfig} (switch, enhanced players/test runs,
 * known names); the current test run and the action's correlation id are stamped automatically.
 * <p>
 * Never put free text here: payload values are ids, codes, counts and coarse positions. String
 * values that aren't stable tokens are cut to 64 characters, and the API allowlists keys per name.
 */
public final class TelemetryEmitter {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.:@/\\-]{1,64}");

    private final TelemetryBuffer buffer;
    private final Clock clock;
    private final String serverName;
    private final String appVersion;
    private final AtomicLong sequence = new AtomicLong();
    private volatile TelemetryClientConfig config = TelemetryClientConfig.initial();
    private volatile Function<UUID, Integer> userIds = id -> null;
    private volatile Function<UUID, UUID> sessionKeys = id -> null;

    public TelemetryEmitter(TelemetryBuffer buffer, Clock clock, String serverName, String appVersion) {
        this.buffer = buffer;
        this.clock = clock;
        this.serverName = truncate(serverName == null || serverName.isBlank() ? "paper" : serverName, 64);
        this.appVersion = truncate(appVersion == null ? "" : appVersion, 32);
    }

    public TelemetryBuffer buffer() {
        return buffer;
    }

    public TelemetryClientConfig config() {
        return config;
    }

    /** The API's latest emitter config (poller thread). */
    public void updateConfig(TelemetryClientConfig config) {
        if (config != null) {
            this.config = config;
        }
    }

    /** How a player's user id is found (the plugin's user cache). */
    public void setUserIds(Function<UUID, Integer> userIds) {
        this.userIds = userIds == null ? id -> null : userIds;
    }

    /** How a player's statistics session key is found (null when statistics are off). */
    public void setSessionKeys(Function<UUID, UUID> sessionKeys) {
        this.sessionKeys = sessionKeys == null ? id -> null : sessionKeys;
    }

    public Integer userIdOf(UUID playerId) {
        try {
            return playerId == null ? null : userIds.apply(playerId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Whether enhanced events are on for this player (owner-picked, or an enhanced test run is running). */
    public boolean isEnhanced(UUID playerId) {
        return config.isEnhanced(userIdOf(playerId));
    }

    /** Starts an event; nothing is recorded until {@link Event#emit()}. */
    public Event event(String name) {
        return new Event(name);
    }

    /** Adds the summary of drops since the last flush (flush task). */
    void emitDropped(long dropped, String reason) {
        TelemetryEvent drop = new TelemetryEvent(UUID.randomUUID(), TelemetryEventNames.TELEMETRY_DROPPED, 1, clock.instant(),
            serverName, sequence.incrementAndGet(), appVersion, TelemetryEvent.Level.BASELINE, null, null,
            config.currentTestRunId(), null, null, "telemetry", "dropped", TelemetryEvent.Outcome.INFO, reason, null, null,
            Map.of("dropped", dropped, "reason", reason));
        buffer.add(drop);
    }

    /** A stable code, or null when {@code value} is free text. */
    static String code(String value) {
        return value != null && CODE.matcher(value).matches() ? value : null;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** One event under construction. Not thread-safe; build and emit on one thread. */
    public final class Event {
        private final String name;
        private final TelemetryEvent.Level level;
        private UUID playerId;
        private Integer userId;
        private Integer matchId;
        private String correlationId;
        private TelemetryEvent.Outcome outcome = TelemetryEvent.Outcome.INFO;
        private String reasonCode;
        private String objectType;
        private String objectId;
        private String action;
        private final Map<String, Object> payload = new LinkedHashMap<>();

        private Event(String name) {
            this.name = name;
            this.level = TelemetryEventNames.levelOf(name);
        }

        /** The acting player: user id (from the cache) and session key are filled in. */
        public Event player(UUID playerId) {
            this.playerId = playerId;
            return this;
        }

        /** The user id when it is known without a lookup (overrides {@link #player}'s). */
        public Event user(Integer userId) {
            this.userId = userId;
            return this;
        }

        public Event match(Integer matchId) {
            this.matchId = matchId;
            return this;
        }

        public Event correlation(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Event outcome(TelemetryEvent.Outcome outcome) {
            this.outcome = outcome == null ? TelemetryEvent.Outcome.INFO : outcome;
            return this;
        }

        public Event reason(String reasonCode) {
            this.reasonCode = code(reasonCode);
            return this;
        }

        public Event object(String type, Object id) {
            this.objectType = code(type);
            this.objectId = id == null ? null : code(String.valueOf(id));
            return this;
        }

        public Event action(String action) {
            this.action = code(action);
            return this;
        }

        /** A scalar payload value (String, Number, Boolean); null values are skipped. */
        public Event put(String key, Object value) {
            if (key == null || value == null) {
                return this;
            }
            if (value instanceof String s) {
                payload.put(key, s.length() <= 64 ? s : s.substring(0, 64));
            } else if (value instanceof Number || value instanceof Boolean) {
                payload.put(key, value);
            } else if (value instanceof Enum<?> e) {
                payload.put(key, e.name().toLowerCase(Locale.ROOT));
            }
            return this;
        }

        /** Records the event when the config allows it; true when it was buffered. */
        public boolean emit() {
            Integer user = userId != null ? userId : userIdOf(playerId);
            TelemetryClientConfig current = config;
            if (!current.allows(name, level, user)) {
                return false;
            }
            UUID sessionKey = null;
            if (playerId != null) {
                try {
                    sessionKey = sessionKeys.apply(playerId);
                } catch (RuntimeException ignored) {
                    // statistics not running for this player
                }
            }
            String correlation = correlationId != null ? correlationId : TelemetryCorrelation.current();
            int dot = name.indexOf('.');
            buffer.add(new TelemetryEvent(UUID.randomUUID(), name, 1, clock.instant(), serverName, sequence.incrementAndGet(),
                appVersion, level, user, sessionKey, current.currentTestRunId(), matchId, code(correlation),
                dot < 0 ? name : name.substring(0, dot), action != null ? action : name.substring(dot + 1), outcome,
                reasonCode, objectType, objectId, payload));
            return true;
        }
    }
}
