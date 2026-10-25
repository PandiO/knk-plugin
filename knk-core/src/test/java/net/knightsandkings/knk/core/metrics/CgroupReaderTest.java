package net.knightsandkings.knk.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CgroupReaderTest {

    @TempDir
    Path root;

    private void write(String relative, String content) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    @Test
    void v2WorkingSetSubtractsTheInactiveFileCache() throws IOException {
        write("memory.current", "4000000000\n");
        write("memory.max", "5368709120\n");
        write("memory.stat", "anon 1500000000\nfile 2400000000\ninactive_file 2000000000\n");
        write("cpu.stat", "usage_usec 12500000\nthrottled_usec 2000000\nnr_throttled 7\n");

        CgroupReader.Snapshot s = new CgroupReader(root).read().orElseThrow();

        assertEquals(4_000_000_000L, s.memoryUsageBytes());
        assertEquals(2_000_000_000L, s.memoryWorkingSetBytes());
        assertEquals(5_368_709_120L, s.memoryLimitBytes());
        assertEquals(12.5, s.cpuUsageSeconds(), 1e-9);
        assertEquals(2.0, s.cpuThrottledSeconds(), 1e-9);
        assertEquals(7, s.cpuThrottledPeriods());
    }

    @Test
    void v2UnlimitedMemoryReportsNoLimit() throws IOException {
        write("memory.current", "100\n");
        write("memory.max", "max\n");

        CgroupReader.Snapshot s = new CgroupReader(root).read().orElseThrow();

        assertEquals(-1, s.memoryLimitBytes());
        assertEquals(100, s.memoryWorkingSetBytes());
        assertEquals(-1, s.cpuUsageSeconds());
    }

    @Test
    void v1FallbackTreatsAHugeLimitAsUnlimited() throws IOException {
        write("memory/memory.usage_in_bytes", "3000\n");
        write("memory/memory.limit_in_bytes", "9223372036854771712\n");
        write("memory/memory.stat", "total_inactive_file 1000\n");
        write("cpuacct/cpuacct.usage", "2000000000\n");

        CgroupReader.Snapshot s = new CgroupReader(root).read().orElseThrow();

        assertEquals(3000, s.memoryUsageBytes());
        assertEquals(2000, s.memoryWorkingSetBytes());
        assertEquals(-1, s.memoryLimitBytes());
        assertEquals(2.0, s.cpuUsageSeconds(), 1e-9);
    }

    @Test
    void outsideAContainerThereIsNothing() {
        assertTrue(new CgroupReader(root).read().isEmpty());
    }
}
