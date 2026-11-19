package net.knightsandkings.knk.core.items.identity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * The conversion log (PandiO/knk-workspace#141): counts per hook, outcome and material, safe to call from the main
 * thread on every event (one map lookup and an add). {@link #drain} hands the counts since the last drain to the
 * reporter, which sends them to knk-web-api and logs a summary. {@link #firstUnresolved} tells the caller to log an
 * unresolved item once per reason and material (until the set is reset), so a busy farm doesn't flood the log.
 */
public final class ItemConversionLog {

    /** The conversion hooks, as the API stores them. */
    public enum Source {
        PICKUP("pickup"),
        BLOCK_DROP("block_drop"),
        ENTITY_DEATH("entity_death"),
        FISHING("fishing"),
        CRAFT("craft"),
        TRADE("trade"),
        CONTAINER_OPEN("container_open");

        private final String wire;

        Source(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /** One count line. */
    public record Count(String source, String outcome, String materialKey, long count) {
    }

    private record Key(String source, String outcome, String materialKey) {
    }

    /** Bounds the once-per-key log memory. */
    static final int MAX_LOGGED_KEYS = 10_000;

    private final Map<Key, LongAdder> counts = new ConcurrentHashMap<>();
    private final Set<String> loggedUnresolved = ConcurrentHashMap.newKeySet();

    /** Counts {@code amount} items (the stack size) for the hook and the resolution's outcome. */
    public void record(Source source, ItemIdentityResolution resolution, String materialKey, int amount) {
        String key = MaterialBlueprintMap.normalize(materialKey);
        if (source == null || resolution == null || key == null || amount <= 0) {
            return;
        }
        counts.computeIfAbsent(new Key(source.wire(), resolution.outcome(), key), k -> new LongAdder()).add(amount);
    }

    /** True the first time an unresolved {@code outcome} is seen for {@code materialKey}: log it then, not again. */
    public boolean firstUnresolved(ItemIdentityResolution resolution, String materialKey) {
        if (resolution == null || resolution.resolved()) {
            return false;
        }
        if (loggedUnresolved.size() >= MAX_LOGGED_KEYS) {
            loggedUnresolved.clear();
        }
        return loggedUnresolved.add(resolution.outcome() + "|" + MaterialBlueprintMap.normalize(materialKey));
    }

    /** The counts since the last drain (sorted, zero lines left out); the log starts again from zero. */
    public List<Count> drain() {
        List<Count> lines = new ArrayList<>();
        for (Key key : List.copyOf(counts.keySet())) {
            LongAdder adder = counts.remove(key);
            long value = adder != null ? adder.sumThenReset() : 0;
            if (value > 0) {
                lines.add(new Count(key.source(), key.outcome(), key.materialKey(), value));
            }
        }
        lines.sort(Comparator.comparing(Count::source).thenComparing(Count::outcome).thenComparing(Count::materialKey));
        return lines;
    }

    /** Puts drained counts back (a report that failed), so the next report sends them. */
    public void restore(List<Count> lines) {
        if (lines == null) {
            return;
        }
        for (Count line : lines) {
            if (line != null && line.count() > 0) {
                counts.computeIfAbsent(new Key(line.source(), line.outcome(), line.materialKey()), k -> new LongAdder()).add(line.count());
            }
        }
    }

    /** One log line: total items per outcome, e.g. {@code vanilla_match=120, unresolved_custom_name=3}. */
    public static String summary(List<Count> lines) {
        Map<String, Long> perOutcome = new java.util.TreeMap<>();
        for (Count line : lines) {
            perOutcome.merge(line.outcome(), line.count(), Long::sum);
        }
        StringBuilder text = new StringBuilder();
        perOutcome.forEach((outcome, total) -> text.append(text.isEmpty() ? "" : ", ").append(outcome).append('=').append(total));
        return text.isEmpty() ? "none" : text.toString();
    }
}
