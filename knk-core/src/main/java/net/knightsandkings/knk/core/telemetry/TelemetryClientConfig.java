package net.knightsandkings.knk.core.telemetry;

import java.util.List;
import java.util.Set;

/**
 * What the API wants the plugin to emit ({@code GET api/telemetry/config}, KNG-34 link 6): the
 * switch, owner-picked enhanced players and test runs, and the known event names. Polled every
 * {@code telemetry.config-poll-seconds}; until the first answer the plugin uses {@link #initial()}
 * (baseline on, nothing enhanced).
 *
 * @param activeTestRunIds   running test runs, newest first; the first is stamped on every event
 * @param enhancedTestRunIds active test runs whose enhanced events are on for everyone online
 * @param baselineEventNames empty = accept every baseline name (before the first poll)
 */
public record TelemetryClientConfig(
    boolean enabled,
    Set<Integer> enhancedUserIds,
    List<Integer> activeTestRunIds,
    Set<Integer> enhancedTestRunIds,
    Set<String> baselineEventNames,
    Set<String> enhancedEventNames
) {
    public TelemetryClientConfig {
        enhancedUserIds = enhancedUserIds == null ? Set.of() : Set.copyOf(enhancedUserIds);
        activeTestRunIds = activeTestRunIds == null ? List.of() : List.copyOf(activeTestRunIds);
        enhancedTestRunIds = enhancedTestRunIds == null ? Set.of() : Set.copyOf(enhancedTestRunIds);
        baselineEventNames = baselineEventNames == null ? Set.of() : Set.copyOf(baselineEventNames);
        enhancedEventNames = enhancedEventNames == null ? Set.of() : Set.copyOf(enhancedEventNames);
    }

    /** Before the first poll: baseline events on, nothing enhanced, no test run. */
    public static TelemetryClientConfig initial() {
        return new TelemetryClientConfig(true, Set.of(), List.of(), Set.of(), Set.of(), Set.of());
    }

    /** The test run to stamp on events, or null. */
    public Integer currentTestRunId() {
        return activeTestRunIds.isEmpty() ? null : activeTestRunIds.get(0);
    }

    /** Enhanced events for this user: named directly, or an enhanced test run is running. */
    public boolean isEnhanced(Integer userId) {
        if (!enabled) {
            return false;
        }
        if (userId != null && enhancedUserIds.contains(userId)) {
            return true;
        }
        Integer run = currentTestRunId();
        return run != null && enhancedTestRunIds.contains(run);
    }

    /** Whether an event of this name and level may be emitted for this user. */
    public boolean allows(String name, TelemetryEvent.Level level, Integer userId) {
        if (!enabled || name == null) {
            return false;
        }
        if (level == TelemetryEvent.Level.ENHANCED) {
            return isEnhanced(userId) && (enhancedEventNames.isEmpty() || enhancedEventNames.contains(name));
        }
        return baselineEventNames.isEmpty() || baselineEventNames.contains(name);
    }
}
