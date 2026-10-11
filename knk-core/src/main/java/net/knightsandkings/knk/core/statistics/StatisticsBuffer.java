package net.knightsandkings.knk.core.statistics;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import net.knightsandkings.knk.core.domain.statistics.StatisticsBatch;

/**
 * The plugin's in-memory statistics sink between flushes (IMPLEMENTATION_PLAN.md §5.2). Thread-safe
 * (every method is synchronized). Counters are summed and records maxed per
 * {@code (userId, metric, context, minute)}, so a flush sends one entry per player, metric, context and
 * minute however many events happened; session entries, durations and PvP kills are kept in order.
 * <p>
 * {@link #drain} builds one batch of at most {@code maxEntries} entries in the order the API applies
 * them - sessions, durations, counters, records, PvP kills - so a duration is never sent before the
 * start of its session. A summed value larger than its metric's {@link StatisticsMetric#maxPerEntry()}
 * is split over several entries. Whatever doesn't fit stays for the next drain.
 */
public final class StatisticsBuffer {

    private record ValueKey(int userId, StatisticsMetric metric, String context, long minute) {
    }

    /** A summed or maxed value and the instant of its latest contribution. */
    private static final class Value {
        double value;
        Instant latest;

        Value(double value, Instant latest) {
            this.value = value;
            this.latest = latest;
        }
    }

    private final Deque<StatisticsBatch.SessionEntry> sessions = new ArrayDeque<>();
    private final Deque<StatisticsBatch.DurationEntry> durations = new ArrayDeque<>();
    private final Map<ValueKey, Value> counters = new LinkedHashMap<>();
    private final Map<ValueKey, Value> records = new LinkedHashMap<>();
    private final Deque<StatisticsBatch.PvpKillEntry> pvpKills = new ArrayDeque<>();

    public synchronized void addSession(StatisticsBatch.SessionEntry entry) {
        sessions.add(Objects.requireNonNull(entry, "entry"));
    }

    /** Adds an {@code active_playtime}/{@code afk_time} interval; empty intervals are ignored. */
    public synchronized void addDuration(StatisticsBatch.DurationEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (entry.to().isAfter(entry.from())) {
            durations.add(entry);
        }
    }

    /** Sums {@code value} (&gt; 0) into the counter of that player, metric, context and minute. */
    public synchronized void addCounter(int userId, StatisticsMetric metric, StatisticsContext context, double value, Instant at) {
        requireInput(metric, StatisticsMetric.Input.COUNTER);
        if (userId <= 0 || !(value > 0) || Double.isInfinite(value)) {
            return;
        }
        Value existing = counters.get(key(userId, metric, context, at));
        if (existing == null) {
            counters.put(key(userId, metric, context, at), new Value(value, at));
        } else {
            existing.value += value;
            if (at.isAfter(existing.latest)) {
                existing.latest = at;
            }
        }
    }

    /** Keeps the highest {@code value} (&ge; 0) of that player, metric, context and minute. */
    public synchronized void addRecord(int userId, StatisticsMetric metric, StatisticsContext context, double value, Instant at) {
        requireInput(metric, StatisticsMetric.Input.RECORD);
        if (userId <= 0 || !(value >= 0) || Double.isInfinite(value)) {
            return;
        }
        double capped = Math.min(value, metric.maxPerEntry());
        ValueKey key = key(userId, metric, context, at);
        Value existing = records.get(key);
        if (existing == null) {
            records.put(key, new Value(capped, at));
        } else if (capped > existing.value) {
            existing.value = capped;
            existing.latest = at;
        }
    }

    public synchronized void addPvpKill(StatisticsBatch.PvpKillEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (entry.killerUserId() > 0 && entry.victimUserId() > 0 && entry.killerUserId() != entry.victimUserId()) {
            pvpKills.add(entry);
        }
    }

    /** Entries a full drain would produce (counters counted after splitting). */
    public synchronized int size() {
        int count = sessions.size() + durations.size() + pvpKills.size() + records.size();
        for (Map.Entry<ValueKey, Value> entry : counters.entrySet()) {
            count += pieces(entry.getValue().value, entry.getKey().metric().maxPerEntry());
        }
        return count;
    }

    public synchronized boolean isEmpty() {
        return sessions.isEmpty() && durations.isEmpty() && counters.isEmpty() && records.isEmpty() && pvpKills.isEmpty();
    }

    /**
     * Removes up to {@code maxEntries} entries as one batch; null when the buffer is empty.
     *
     * @param batchIds a new random id per batch (injected for tests)
     */
    public synchronized StatisticsBatch drain(int maxEntries, Instant sentAt, Supplier<UUID> batchIds) {
        if (isEmpty()) {
            return null;
        }
        int budget = Math.max(1, Math.min(maxEntries, StatisticsBatch.MAX_ENTRIES));
        List<StatisticsBatch.SessionEntry> outSessions = new ArrayList<>();
        while (budget > 0 && !sessions.isEmpty()) {
            outSessions.add(sessions.poll());
            budget--;
        }
        List<StatisticsBatch.DurationEntry> outDurations = new ArrayList<>();
        while (budget > 0 && !durations.isEmpty()) {
            outDurations.add(durations.poll());
            budget--;
        }
        List<StatisticsBatch.ValueEntry> outCounters = new ArrayList<>();
        Iterator<Map.Entry<ValueKey, Value>> counterIterator = counters.entrySet().iterator();
        while (budget > 0 && counterIterator.hasNext()) {
            Map.Entry<ValueKey, Value> entry = counterIterator.next();
            ValueKey key = entry.getKey();
            Value value = entry.getValue();
            double max = key.metric().maxPerEntry();
            while (budget > 0 && value.value > 0) {
                double piece = Math.min(value.value, max);
                outCounters.add(new StatisticsBatch.ValueEntry(key.userId(), key.metric().key(), key.context(), piece, value.latest));
                value.value -= piece;
                budget--;
            }
            if (!(value.value > 1e-9)) {
                counterIterator.remove();
            }
        }
        List<StatisticsBatch.ValueEntry> outRecords = new ArrayList<>();
        Iterator<Map.Entry<ValueKey, Value>> recordIterator = records.entrySet().iterator();
        while (budget > 0 && recordIterator.hasNext()) {
            Map.Entry<ValueKey, Value> entry = recordIterator.next();
            outRecords.add(new StatisticsBatch.ValueEntry(entry.getKey().userId(), entry.getKey().metric().key(),
                    entry.getKey().context(), entry.getValue().value, entry.getValue().latest));
            recordIterator.remove();
            budget--;
        }
        List<StatisticsBatch.PvpKillEntry> outKills = new ArrayList<>();
        while (budget > 0 && !pvpKills.isEmpty()) {
            outKills.add(pvpKills.poll());
            budget--;
        }
        return new StatisticsBatch(batchIds.get(), sentAt, outSessions, outDurations, outCounters, outRecords, outKills);
    }

    private static ValueKey key(int userId, StatisticsMetric metric, StatisticsContext context, Instant at) {
        StatisticsContext stored = (context == null ? StatisticsContext.NONE : context).forMetric(metric);
        return new ValueKey(userId, metric, stored.key(), Math.floorDiv(at.getEpochSecond(), 60));
    }

    private static int pieces(double value, double max) {
        return (int) Math.max(1, Math.ceil(value / max - 1e-9));
    }

    private static void requireInput(StatisticsMetric metric, StatisticsMetric.Input input) {
        Objects.requireNonNull(metric, "metric");
        if (metric.input() != input) {
            throw new IllegalArgumentException(metric.key() + " is not a " + input + " metric");
        }
    }
}
