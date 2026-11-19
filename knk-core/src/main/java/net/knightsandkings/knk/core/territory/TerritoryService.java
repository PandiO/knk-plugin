package net.knightsandkings.knk.core.territory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.map.MapReportSender;
import net.knightsandkings.knk.core.map.RegionOutline;
import net.knightsandkings.knk.core.map.RegionOutlinePort;
import net.knightsandkings.knk.core.ports.api.TerritoryApi;

/**
 * Territory on the game server (territory DESIGN § 1-4, PandiO/knk-workspace#689): keeps the {@link TerritoryCache} and
 * rebuilds it when its inputs change. Bukkit-free; the platform gives the ports and two executors.
 * <ol>
 *   <li>{@link #refresh}: reads {@code GET api/Territory/inputs} (async), the source regions' shapes through
 *       {@link RegionOutlinePort} (main thread), and builds the cache (async) unless the inputs' {@code version} and the
 *       shapes' fingerprint are unchanged. Overlapping calls are merged: one runs, one more follows.</li>
 *   <li>After a rebuild: the listeners run on the main thread (the player tracker); with {@code production.territory.report}
 *       and the API's {@code productionEnabled}, one full-snapshot report per world ({@code PUT api/Territory/report});
 *       with {@code production.territory.map-outlines}, the outlines over the game hub.</li>
 * </ol>
 */
public final class TerritoryService {

    /** The game hub method for the overview map's territory layer (OVERVIEW_MAP_DESIGN § 1 item 3.3, O15). */
    public static final String OUTLINES_METHOD = "ReportTerritoryOutlines";

    /** A structure as the API lists it: its district (for its town), world and region. */
    public record StructureRef(int id, Integer districtId, String world, String regionId) {
    }

    /** Reads every structure with a region (paged API reads). */
    @FunctionalInterface
    public interface StructureSource {
        CompletableFuture<List<StructureRef>> load();
    }

    /** What one refresh did. */
    public record Outcome(boolean rebuilt, String reason, TerritoryCache cache) {
        public String summary() {
            if (!rebuilt) {
                return "unchanged (version " + shortVersion(cache.version()) + ")";
            }
            return cache.chunkCount() + " chunks (" + cache.splitCount() + " split) in " + cache.worlds().size()
                    + " world(s), version " + shortVersion(cache.version()) + ", " + cache.buildMillis() + " ms"
                    + (cache.warnings().isEmpty() ? "" : ", " + cache.warnings().size() + " warning(s)");
        }
    }

    private record Geometry(TerritoryInputs inputs, List<TerritoryCacheBuilder.PlacedSource> placed, List<String> missing,
                            long fingerprint) {
    }

    private final TerritorySettings settings;
    private final TerritoryApi api;
    private final RegionOutlinePort outlines;
    private final StructureSource structures;
    private final MapReportSender outlineSender;
    private final Executor main;
    private final Executor async;
    private final Logger logger;
    private final List<Consumer<TerritoryCache>> listeners = new CopyOnWriteArrayList<>();
    private final Set<String> reportedWorlds = new LinkedHashSet<>();
    private final Object lock = new Object();

    private volatile TerritoryCache cache = TerritoryCache.EMPTY;
    private volatile String lastReport = "none yet";
    private CompletableFuture<Outcome> inFlight;
    private boolean queued;
    private boolean queuedForce;

    /**
     * @param structures    null: structures are not flagged in the report
     * @param outlineSender null: no outlines are sent
     * @param main          the thread where {@code outlines} may be read and listeners run
     * @param async         where the cache is built
     */
    public TerritoryService(TerritorySettings settings, TerritoryApi api, RegionOutlinePort outlines,
                            StructureSource structures, MapReportSender outlineSender, Executor main, Executor async,
                            Logger logger) {
        this.settings = settings;
        this.api = api;
        this.outlines = outlines;
        this.structures = structures;
        this.outlineSender = outlineSender;
        this.main = main;
        this.async = async;
        this.logger = logger;
    }

    public TerritorySettings settings() {
        return settings;
    }

    public TerritoryCache cache() {
        return cache;
    }

    /** The verdict at block column ({@code x}, {@code z}) of {@code world}, from the current cache. */
    public TerritoryVerdict lookup(String world, int x, int z) {
        return cache.lookup(world, x, z);
    }

    /** Runs on the main thread after every rebuild, with the new cache. */
    public void addListener(Consumer<TerritoryCache> listener) {
        listeners.add(listener);
    }

    /** The last report's outcome, for {@code /knk territory status}. */
    public String lastReport() {
        return lastReport;
    }

    /**
     * Reads the inputs and shapes and rebuilds the cache when they changed ({@code force}: always, and report again). While
     * one refresh runs, a call returns that refresh's future and queues one more.
     */
    public CompletableFuture<Outcome> refresh(String reason, boolean force) {
        CompletableFuture<Outcome> result;
        synchronized (lock) {
            if (inFlight != null) {
                queued = true;
                queuedForce |= force;
                return inFlight;
            }
            result = new CompletableFuture<>();
            inFlight = result;
        }
        run(reason, force).whenComplete((outcome, error) -> {
            boolean again;
            boolean againForce;
            synchronized (lock) {
                inFlight = null;
                again = queued;
                againForce = queuedForce;
                queued = false;
                queuedForce = false;
            }
            if (error != null) {
                logger.log(Level.WARNING, "[KnK Territory] Refresh (" + reason + ") failed: " + rootMessage(error));
                result.completeExceptionally(error);
            } else {
                result.complete(outcome);
            }
            if (again) {
                refresh("queued", againForce);
            }
        });
        return result;
    }

    private CompletableFuture<Outcome> run(String reason, boolean force) {
        return api.fetchInputs()
                .thenApplyAsync(this::readGeometry, main)
                .thenApplyAsync(geometry -> {
                    TerritoryCache current = cache;
                    if (!force && current != TerritoryCache.EMPTY && current.version().equals(geometry.inputs().version())
                            && current.geometryFingerprint() == geometry.fingerprint()
                            && current.inputs().productionEnabled() == geometry.inputs().productionEnabled()) {
                        return new Outcome(false, reason, current);
                    }
                    return new Outcome(true, reason, TerritoryCacheBuilder.build(geometry.inputs(), geometry.placed(),
                            geometry.missing(), geometry.fingerprint(), settings.maxChunksPerWorld()));
                }, async)
                .thenApplyAsync(outcome -> {
                    if (outcome.rebuilt()) {
                        cache = outcome.cache();
                        logger.info("[KnK Territory] Cache rebuilt (" + reason + "): " + outcome.summary());
                        outcome.cache().warnings().stream().limit(10)
                                .forEach(warning -> logger.warning("[KnK Territory] " + warning));
                        for (Consumer<TerritoryCache> listener : listeners) {
                            try {
                                listener.accept(outcome.cache());
                            } catch (RuntimeException e) {
                                logger.log(Level.WARNING, "[KnK Territory] A rebuild listener failed", e);
                            }
                        }
                        sendOutlines(outcome.cache());
                    }
                    if (outcome.rebuilt() || force) {
                        report(outcome.cache());
                    }
                    return outcome;
                }, main);
    }

    /** Main thread: every source's shape, its world, and a fingerprint of all shapes. */
    private Geometry readGeometry(TerritoryInputs inputs) {
        List<TerritoryCacheBuilder.PlacedSource> placed = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        long fingerprint = 1;
        for (TerritoryInputs.Source source : inputs.sources()) {
            if (source.regionId() == null || source.regionId().isBlank()) {
                missing.add(source.label() + ": no region");
                continue;
            }
            List<RegionOutline> found = new ArrayList<>();
            String world = null;
            for (String regionId : source.regionIds()) {
                Optional<RegionOutline> outline = outlines.outline(world != null ? world : source.world(), regionId);
                if (outline.isEmpty()) {
                    if (regionId.equals(source.regionId())) {
                        break;
                    }
                    missing.add(source.label() + ": sub-region " + regionId + " not found (left out)");
                    continue;
                }
                world = outline.get().worldName();
                found.add(outline.get());
                fingerprint = 31 * fingerprint + fingerprintOf(outline.get());
            }
            TerritoryShape shape = found.isEmpty() ? null : TerritoryShape.of(found);
            if (shape == null || world == null) {
                missing.add(source.label() + ": region " + source.regionId() + " not found"
                        + (source.world() != null ? " in " + source.world() : ""));
                continue;
            }
            placed.add(new TerritoryCacheBuilder.PlacedSource(source, world, shape));
        }
        return new Geometry(inputs, placed, missing, fingerprint);
    }

    private static long fingerprintOf(RegionOutline outline) {
        long hash = outline.worldName().hashCode() * 31L + outline.regionId().hashCode();
        for (var point : outline.points()) {
            hash = hash * 31 + point.x();
            hash = hash * 31 + point.z();
        }
        return hash;
    }

    /** Main thread: the full-snapshot report per world, when the plugin and the API both want it. */
    private void report(TerritoryCache built) {
        if (!settings.report()) {
            return;
        }
        if (!built.inputs().productionEnabled()) {
            lastReport = "not sent: the API's Production:Enabled is off";
            logger.fine("[KnK Territory] Report not sent: Production:Enabled is off on the API");
            return;
        }
        CompletableFuture<List<StructureRef>> refs = structures == null
                ? CompletableFuture.completedFuture(List.of())
                : structures.load().exceptionally(error -> {
                    logger.warning("[KnK Territory] Structures could not be read (" + rootMessage(error)
                            + "); the report flags none this time");
                    return List.of();
                });
        refs.thenApplyAsync(list -> placements(built, list), main)
                .thenApplyAsync(placements -> {
                    Set<String> worlds;
                    synchronized (reportedWorlds) {
                        worlds = new LinkedHashSet<>(reportedWorlds);
                    }
                    worlds.addAll(built.worldNames());
                    return TerritoryReportBuilder.build(built, placements, worlds);
                }, async)
                .thenCompose(this::sendReports)
                .exceptionally(error -> {
                    lastReport = "failed: " + rootMessage(error);
                    logger.warning("[KnK Territory] Report failed: " + rootMessage(error));
                    return null;
                });
    }

    private CompletableFuture<Void> sendReports(List<TerritoryReport> reports) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        List<String> lines = new ArrayList<>();
        for (TerritoryReport report : reports) {
            chain = chain.thenCompose(ignored -> api.report(report).handle((result, error) -> {
                if (error != null) {
                    ApiException api = apiException(error);
                    String why = api != null && api.getStatusCode() == 409
                            ? "refused, the API's Production:Enabled is off" : rootMessage(error);
                    lines.add(report.world() + ": " + why);
                    logger.warning("[KnK Territory] Report for " + report.world() + " " + why);
                } else {
                    synchronized (reportedWorlds) {
                        reportedWorlds.add(report.world());
                    }
                    lines.add(report.world() + ": " + report.overlaps().size() + " overlap(s), "
                            + report.structures().size() + " flagged structure(s); " + result.summary());
                    logger.info("[KnK Territory] Report for " + report.world() + ": " + report.overlaps().size()
                            + " overlap(s), " + report.structures().size() + " flagged structure(s); " + result.summary());
                    result.skipped().stream().limit(5)
                            .forEach(skip -> logger.warning("[KnK Territory] Report entry skipped: " + skip));
                }
                return null;
            }));
        }
        return chain.thenRun(() -> lastReport = lines.isEmpty() ? "no worlds" : String.join("; ", lines));
    }

    /** Main thread: each structure at its region's centre column, with its town through its district. */
    private List<TerritoryReportBuilder.StructurePlacement> placements(TerritoryCache built, List<StructureRef> refs) {
        List<TerritoryReportBuilder.StructurePlacement> placements = new ArrayList<>();
        for (StructureRef ref : refs) {
            if (ref.regionId() == null || ref.regionId().isBlank() || ref.districtId() == null) {
                continue;
            }
            Integer town = built.inputs().townOfDistrict(ref.districtId());
            if (town == null) {
                continue;
            }
            Optional<RegionOutline> outline = outlines.outline(ref.world(), ref.regionId());
            if (outline.isEmpty() || outline.get().points().isEmpty()) {
                continue;
            }
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (var point : outline.get().points()) {
                minX = Math.min(minX, point.x());
                maxX = Math.max(maxX, point.x());
                minZ = Math.min(minZ, point.z());
                maxZ = Math.max(maxZ, point.z());
            }
            placements.add(new TerritoryReportBuilder.StructurePlacement(ref.id(), town, outline.get().worldName(),
                    Math.floorDiv(minX + maxX, 2), Math.floorDiv(minZ + maxZ, 2)));
        }
        return placements;
    }

    /** Main thread: the outlines of every world over the hub, one message per area (overview map). */
    private void sendOutlines(TerritoryCache built) {
        if (!settings.mapOutlines() || outlineSender == null) {
            return;
        }
        CompletableFuture.supplyAsync(() -> TerritoryOutlines.build(built), async).thenAccept(all -> {
            int sent = 0;
            int failed = 0;
            Map<String, List<Map<String, Object>>> byWorld = new LinkedHashMap<>();
            for (TerritoryOutlines.Outline outline : all) {
                byWorld.computeIfAbsent(outline.world(), ignored -> new ArrayList<>()).add(outlineJson(built, outline));
            }
            for (String world : built.worldNames()) {
                Map<String, Object> argument = new LinkedHashMap<>();
                argument.put("world", world);
                argument.put("inputsVersion", built.version());
                argument.put("outlines", byWorld.getOrDefault(world, List.of()));
                if (outlineSender.send(OUTLINES_METHOD, argument)) {
                    sent++;
                } else {
                    failed++;
                }
            }
            logger.info("[KnK Territory] Map outlines: " + all.size() + " area(s) in " + sent + " message(s)"
                    + (failed > 0 ? ", " + failed + " not sent (hub not connected)" : ""));
        }).exceptionally(error -> {
            logger.warning("[KnK Territory] Map outlines failed: " + rootMessage(error));
            return null;
        });
    }

    static Map<String, Object> outlineJson(TerritoryCache built, TerritoryOutlines.Outline outline) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("kind", outline.owner() != null ? "Owned" : "Contested");
        List<Map<String, Object>> holders = new ArrayList<>();
        for (TerritoryHolder holder : outline.owner() != null ? List.of(outline.owner()) : outline.contested()) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("kind", holder.kind().apiName());
            h.put("id", holder.id());
            h.put("name", built.inputs().nameOf(holder));
            holders.add(h);
        }
        json.put("holders", holders);
        List<Map<String, Object>> rings = new ArrayList<>();
        for (TerritoryOutlines.Ring ring : outline.rings()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("hole", ring.hole());
            r.put("points", ring.points());
            rings.add(r);
        }
        json.put("rings", rings);
        return json;
    }

    private static ApiException apiException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ApiException api) {
                return api;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return null;
    }

    static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }

    static String shortVersion(String version) {
        return version == null || version.isEmpty() ? "-" : version.length() > 12 ? version.substring(0, 12) : version;
    }
}
