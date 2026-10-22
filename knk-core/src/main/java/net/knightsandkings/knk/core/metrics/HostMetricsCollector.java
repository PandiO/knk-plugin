package net.knightsandkings.knk.core.metrics;

import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * JVM, process and container figures for sizing the Minecraft server (KNG-232): heap and non-heap, memory pools,
 * direct/mapped buffers (off-heap memory {@code -Xmx} does not cap), GC, threads, process CPU and resident
 * memory, system load, and the container's cgroup memory and CPU. Pure JDK: no Bukkit.
 */
public final class HostMetricsCollector implements MetricsCollector {

    private final CgroupReader cgroup;
    private final Path procStatus;

    public HostMetricsCollector() {
        this(new CgroupReader(), Path.of("/proc/self/status"));
    }

    public HostMetricsCollector(CgroupReader cgroup, Path procStatus) {
        this.cgroup = cgroup;
        this.procStatus = procStatus;
    }

    @Override
    public void collect(PrometheusWriter out) {
        memory(out);
        gc(out);
        process(out);
        container(out);
    }

    private static void memory(PrometheusWriter out) {
        String help = "JVM memory by area (heap, nonheap) and kind (used, committed, max).";
        usage(out, "knk_jvm_memory_bytes", help, "heap", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage());
        usage(out, "knk_jvm_memory_bytes", help, "nonheap", ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage());
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage usage = pool.getUsage();
            if (usage != null) {
                out.gauge("knk_jvm_memory_pool_bytes", "JVM memory pool sizes.", usage.getUsed(), "pool", pool.getName(), "kind", "used");
                out.gauge("knk_jvm_memory_pool_bytes", "JVM memory pool sizes.", usage.getCommitted(), "pool", pool.getName(), "kind", "committed");
            }
            MemoryUsage afterGc = pool.getCollectionUsage();
            if (afterGc != null) {
                out.gauge("knk_jvm_memory_pool_after_gc_bytes", "Pool usage right after its last collection (the live set).",
                    afterGc.getUsed(), "pool", pool.getName());
            }
        }
        for (BufferPoolMXBean buffers : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            out.gauge("knk_jvm_buffer_pool_bytes", "Direct and mapped buffer memory (off-heap).",
                buffers.getMemoryUsed(), "pool", buffers.getName(), "kind", "used");
            out.gauge("knk_jvm_buffer_pool_bytes", "Direct and mapped buffer memory (off-heap).",
                buffers.getTotalCapacity(), "pool", buffers.getName(), "kind", "capacity");
        }
    }

    private static void usage(PrometheusWriter out, String name, String help, String area, MemoryUsage usage) {
        out.gauge(name, help, usage.getUsed(), "area", area, "kind", "used");
        out.gauge(name, help, usage.getCommitted(), "area", area, "kind", "committed");
        if (usage.getMax() >= 0) {
            out.gauge(name, help, usage.getMax(), "area", area, "kind", "max");
        }
    }

    private static void gc(PrometheusWriter out) {
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (collector.getCollectionCount() >= 0) {
                out.counter("knk_jvm_gc_collections_total", "Garbage collections by collector.",
                    collector.getCollectionCount(), "gc", collector.getName());
            }
            if (collector.getCollectionTime() >= 0) {
                out.counter("knk_jvm_gc_seconds_total", "Time spent collecting, by collector.",
                    collector.getCollectionTime() / 1000.0, "gc", collector.getName());
            }
        }
    }

    private void process(PrometheusWriter out) {
        out.gauge("knk_jvm_threads", "Live JVM threads.", ManagementFactory.getThreadMXBean().getThreadCount());
        out.gauge("knk_jvm_classes_loaded", "Loaded classes.", ManagementFactory.getClassLoadingMXBean().getLoadedClassCount());
        out.gauge("knk_system_load1", "One-minute system load average (-1 when unavailable).",
            ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            out.counter("knk_process_cpu_seconds_total", "CPU time used by the server process.", os.getProcessCpuTime() / 1e9);
            double processLoad = os.getProcessCpuLoad();
            if (processLoad >= 0) {
                out.gauge("knk_process_cpu_ratio", "Recent CPU use of the server process, 0..1 of the CPUs available to the JVM.", processLoad);
            }
            double systemLoad = os.getCpuLoad();
            if (systemLoad >= 0) {
                out.gauge("knk_system_cpu_ratio", "Recent CPU use of the whole system as the JVM sees it, 0..1.", systemLoad);
            }
        }
        out.gauge("knk_jvm_available_processors", "CPUs available to the JVM.", Runtime.getRuntime().availableProcessors());
        residentBytes().ifPresent(rss -> out.gauge("knk_process_resident_memory_bytes",
            "Resident set size of the server process (heap plus all native memory).", rss));
    }

    private void container(PrometheusWriter out) {
        cgroup.read().ifPresent(s -> {
            if (s.memoryUsageBytes() >= 0) {
                out.gauge("knk_cgroup_memory_usage_bytes", "Container memory including reclaimable file cache.", s.memoryUsageBytes());
            }
            if (s.memoryWorkingSetBytes() >= 0) {
                out.gauge("knk_cgroup_memory_working_set_bytes",
                    "Container memory minus inactive file cache: compare this with the limit.", s.memoryWorkingSetBytes());
            }
            if (s.memoryLimitBytes() >= 0) {
                out.gauge("knk_cgroup_memory_limit_bytes", "Container memory limit (absent when unlimited).", s.memoryLimitBytes());
            }
            if (s.cpuUsageSeconds() >= 0) {
                out.counter("knk_cgroup_cpu_seconds_total", "Container CPU time.", s.cpuUsageSeconds());
            }
            if (s.cpuThrottledSeconds() >= 0) {
                out.counter("knk_cgroup_cpu_throttled_seconds_total", "Time the container was throttled by its CPU limit.", s.cpuThrottledSeconds());
            }
            if (s.cpuThrottledPeriods() >= 0) {
                out.counter("knk_cgroup_cpu_throttled_periods_total", "Scheduling periods in which the container was throttled.", s.cpuThrottledPeriods());
            }
        });
    }

    /** {@code VmRSS} from {@code /proc/self/status} in bytes, empty when unreadable (not Linux). */
    Optional<Long> residentBytes() {
        try {
            for (String line : Files.readAllLines(procStatus)) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.split("\\s+");
                    return Optional.of(Long.parseLong(parts[1]) * 1024);
                }
            }
        } catch (IOException | RuntimeException e) {
            // not Linux, or unreadable
        }
        return Optional.empty();
    }
}
