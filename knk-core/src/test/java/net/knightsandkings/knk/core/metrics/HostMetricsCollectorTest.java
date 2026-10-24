package net.knightsandkings.knk.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostMetricsCollectorTest {

    @TempDir
    Path dir;

    @Test
    void reportsTheJvmProcessAndContainerFigures() throws IOException {
        Path cgroup = dir.resolve("cgroup");
        Files.createDirectories(cgroup);
        Files.writeString(cgroup.resolve("memory.current"), "2000\n");
        Files.writeString(cgroup.resolve("memory.max"), "4000\n");
        Files.writeString(cgroup.resolve("memory.stat"), "inactive_file 500\n");
        Path status = dir.resolve("status");
        Files.writeString(status, "Name:\tjava\nVmRSS:\t   2048 kB\n");

        PrometheusWriter out = new PrometheusWriter();
        new HostMetricsCollector(new CgroupReader(cgroup), status).collect(out);
        String text = out.render();

        assertTrue(text.contains("knk_jvm_memory_bytes{area=\"heap\",kind=\"used\"}"), text);
        assertTrue(text.contains("knk_jvm_memory_bytes{area=\"nonheap\",kind=\"committed\"}"), text);
        assertTrue(text.contains("knk_jvm_buffer_pool_bytes{pool=\"direct\",kind=\"used\"}"), text);
        assertTrue(text.contains("knk_jvm_gc_seconds_total{gc="), text);
        assertTrue(text.contains("knk_jvm_threads "), text);
        assertTrue(text.contains("knk_process_cpu_seconds_total "), text);
        assertTrue(text.contains("knk_process_resident_memory_bytes 2097152"), text);
        assertTrue(text.contains("knk_cgroup_memory_usage_bytes 2000"), text);
        assertTrue(text.contains("knk_cgroup_memory_working_set_bytes 1500"), text);
        assertTrue(text.contains("knk_cgroup_memory_limit_bytes 4000"), text);
    }

    @Test
    void withoutProcOrCgroupTheOtherFiguresStillWork() {
        PrometheusWriter out = new PrometheusWriter();
        new HostMetricsCollector(new CgroupReader(dir.resolve("none")), dir.resolve("none-status")).collect(out);
        String text = out.render();

        assertTrue(text.contains("knk_jvm_memory_bytes"));
        assertEquals(false, text.contains("knk_process_resident_memory_bytes"));
        assertEquals(false, text.contains("knk_cgroup_"));
    }
}
