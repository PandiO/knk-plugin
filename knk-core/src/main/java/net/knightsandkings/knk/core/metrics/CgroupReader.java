package net.knightsandkings.knk.core.metrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the memory and CPU counters of the container this JVM runs in (cgroup v2; v1 for memory and CPU time).
 * A container limit counts reclaimable file cache too, so compare the limit with the <em>working set</em>
 * (usage minus inactive file cache, what cAdvisor reports), not with raw usage
 * (docs/guides/performance-metrics-and-capacity.md, knk-workspace). {@link #read()} is empty outside a cgroup.
 */
public final class CgroupReader {
    public static final Path DEFAULT_ROOT = Path.of("/sys/fs/cgroup");

    /** One read; negative values mean "not available". */
    public record Snapshot(long memoryUsageBytes, long memoryWorkingSetBytes, long memoryLimitBytes,
                           double cpuUsageSeconds, double cpuThrottledSeconds, long cpuThrottledPeriods) {
    }

    private final Path root;

    public CgroupReader() {
        this(DEFAULT_ROOT);
    }

    public CgroupReader(Path root) {
        this.root = root;
    }

    public Optional<Snapshot> read() {
        try {
            if (Files.exists(root.resolve("memory.current"))) {
                return Optional.of(readV2());
            }
            if (Files.exists(root.resolve("memory/memory.usage_in_bytes"))) {
                return Optional.of(readV1());
            }
        } catch (IOException | RuntimeException e) {
            // unreadable or odd contents: report nothing rather than fail a scrape
        }
        return Optional.empty();
    }

    private Snapshot readV2() throws IOException {
        long usage = readLong("memory.current");
        long limit = readLong("memory.max"); // "max" parses to -1: unlimited
        Map<String, Long> mem = readKeyed("memory.stat");
        long working = usage >= 0 && mem.containsKey("inactive_file") ? Math.max(0, usage - mem.get("inactive_file")) : usage;
        Map<String, Long> cpu = readKeyed("cpu.stat");
        return new Snapshot(usage, working, limit,
            cpu.containsKey("usage_usec") ? cpu.get("usage_usec") / 1e6 : -1,
            cpu.containsKey("throttled_usec") ? cpu.get("throttled_usec") / 1e6 : -1,
            cpu.getOrDefault("nr_throttled", -1L));
    }

    private Snapshot readV1() throws IOException {
        long usage = readLong("memory/memory.usage_in_bytes");
        long limit = readLong("memory/memory.limit_in_bytes");
        if (limit >= (1L << 60)) {
            limit = -1; // v1 says "no limit" with a huge number
        }
        Map<String, Long> mem = readKeyed("memory/memory.stat");
        long working = usage >= 0 && mem.containsKey("total_inactive_file")
            ? Math.max(0, usage - mem.get("total_inactive_file")) : usage;
        long cpuNanos = readLong("cpuacct/cpuacct.usage");
        return new Snapshot(usage, working, limit, cpuNanos < 0 ? -1 : cpuNanos / 1e9, -1, -1);
    }

    private long readLong(String relative) throws IOException {
        Path path = root.resolve(relative);
        if (!Files.exists(path)) {
            return -1;
        }
        String text = Files.readString(path).trim();
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private Map<String, Long> readKeyed(String relative) throws IOException {
        Map<String, Long> values = new HashMap<>();
        Path path = root.resolve(relative);
        if (!Files.exists(path)) {
            return values;
        }
        for (String line : Files.readAllLines(path)) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2) {
                try {
                    values.put(parts[0], Long.parseLong(parts[1]));
                } catch (NumberFormatException ignored) {
                    // a non-numeric line
                }
            }
        }
        return values;
    }
}
