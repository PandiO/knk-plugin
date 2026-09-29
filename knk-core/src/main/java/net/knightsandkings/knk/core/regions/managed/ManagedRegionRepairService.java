package net.knightsandkings.knk.core.regions.managed;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The startup (and on-demand) repair: read the domain data off the main thread, then reconcile every domain-backed region
 * - plus the config-declared extras - on {@code mainThread}, where WorldGuard may be touched. Logs one summary line.
 * Safe to run any number of times; overlapping runs are refused rather than interleaved.
 */
public class ManagedRegionRepairService {

    private static final int MAX_LOGGED_WARNINGS = 10;

    private final ManagedRegionSpecSource source;
    private final ManagedRegionReconciler reconciler;
    private final ManagedRegionStore store;
    private final List<ManagedRegionSpec> extraRegions;
    private final Executor mainThread;
    private final Logger logger;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Set<String> managedRegionKeys = Set.of();
    private volatile boolean completedOnce;

    public ManagedRegionRepairService(ManagedRegionSpecSource source, ManagedRegionReconciler reconciler,
                                      ManagedRegionStore store, List<ManagedRegionSpec> extraRegions,
                                      Executor mainThread, Logger logger) {
        this.source = source;
        this.reconciler = reconciler;
        this.store = store;
        this.extraRegions = extraRegions != null ? List.copyOf(extraRegions) : List.of();
        this.mainThread = mainThread;
        this.logger = logger;
    }

    /** Completes with the report; completes exceptionally when the domain data cannot be read or a run is already going. */
    public CompletableFuture<RepairReport> run() {
        if (!running.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IllegalStateException("a managed-region repair is already running"));
        }
        CompletableFuture<DomainRegionSpecs.Result> loading;
        try {
            loading = source.load();
        } catch (RuntimeException e) {
            running.set(false);
            return CompletableFuture.failedFuture(e);
        }
        return loading.thenApplyAsync(loaded -> {
            List<ManagedRegionSpec> specs = new ArrayList<>(loaded.specs());
            specs.addAll(extraRegions);
            RepairReport report = reconciler.reconcile(specs, store);
            loaded.skipped().forEach(skipped -> report.addSkipped(skipped.label(), skipped.reason()));
            loaded.warnings().forEach(report::addWarning);
            Set<String> keys = new HashSet<>();
            specs.forEach(spec -> keys.add(spec.key()));
            managedRegionKeys = Set.copyOf(keys);
            completedOnce = true;
            log(report);
            return report;
        }, mainThread).whenComplete((report, error) -> running.set(false));
    }

    /** Whether at least one run has read the domain data and finished (before that, {@link #isManaged} knows nothing). */
    public boolean hasCompleted() {
        return completedOnce;
    }

    /** Whether the last run knew {@code regionId} as a Knights and Kings region (guards e.g. the temp-region cleanup). */
    public boolean isManaged(String regionId) {
        return managedRegionKeys.contains(ManagedRegionSpec.key(regionId));
    }

    private void log(RepairReport report) {
        logger.info("[KnK Regions] Managed-region repair: " + report.summary());
        for (RepairReport.Entry entry : report.entries()) {
            if (entry.outcome() == RepairReport.Outcome.FAILED) {
                logger.log(Level.WARNING, "[KnK Regions] " + entry.regionId() + ": " + entry.detail());
            } else if (entry.outcome() != RepairReport.Outcome.UNCHANGED) {
                logger.fine("[KnK Regions] " + entry.outcome() + " " + entry.regionId() + ": " + entry.detail());
            }
        }
        List<String> warnings = report.warnings();
        for (int i = 0; i < Math.min(MAX_LOGGED_WARNINGS, warnings.size()); i++) {
            logger.warning("[KnK Regions] " + warnings.get(i));
        }
        if (warnings.size() > MAX_LOGGED_WARNINGS) {
            logger.warning("[KnK Regions] ... and " + (warnings.size() - MAX_LOGGED_WARNINGS) + " more warnings");
        }
    }
}
