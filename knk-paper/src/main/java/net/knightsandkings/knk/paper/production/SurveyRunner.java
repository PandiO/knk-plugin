package net.knightsandkings.knk.paper.production;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.ProductionSurveyApi;
import net.knightsandkings.knk.core.production.survey.AreaShape;
import net.knightsandkings.knk.core.production.survey.AreaSpec;
import net.knightsandkings.knk.core.production.survey.BlockSnapshotSource;
import net.knightsandkings.knk.core.production.survey.CapturedChunks;
import net.knightsandkings.knk.core.production.survey.PlaceholderOverlay;
import net.knightsandkings.knk.core.production.survey.SurveyCounter;
import net.knightsandkings.knk.core.production.survey.SurveyDue;
import net.knightsandkings.knk.core.production.survey.SurveyProfile;
import net.knightsandkings.knk.core.production.survey.SurveySettings;

/**
 * Runs the surveys of the API's queue one at a time (PandiO/knk-workspace#679, PRODUCTION_KINDS § 3.2, A10).
 * <ol>
 *   <li><b>Main thread, budgeted:</b> {@link #tick} (every server tick) captures at most {@code chunks-per-tick} chunk
 *       snapshots of the current area; unloaded chunks are loaded without generating them, except for a
 *       {@code Scheduled} survey unless {@code scheduled-loads-chunks} (P-D2: "when the chunks are loaded"), which is
 *       skipped and stays due. Entities of the area are read on the main thread (cheap).</li>
 *   <li><b>Off the main thread:</b> {@link SurveyCounter} counts the snapshots on the counting executor and the report
 *       goes to the API.</li>
 * </ol>
 * Areas over the caps ({@code max-area-blocks}, {@code max-area-chunks}) are skipped with the reason. Main thread only,
 * except the listeners, which are told on the main thread too.
 */
public final class SurveyRunner {

    /** Loads in flight per area at most. */
    static final int MAX_LOADS_IN_FLIGHT = 8;
    /** A survey stuck this long in its capture is given up. */
    static final long CAPTURE_TIMEOUT_MILLIS = 120_000;
    static final int RECENT = 10;

    public enum Status { REPORTED, SKIPPED, FAILED }

    /** How a survey ended. */
    public record Outcome(SurveyDue due, Status status, String detail, Instant at, SurveyCounter.Result result,
                          ProductionSurveyApi.ReportResult report) {
        public String summary() {
            String label = due.area().label();
            return switch (status) {
                case REPORTED -> label + ": " + result.units().size() + " rows, " + result.totalUnits() + " units ("
                        + result.note() + ")" + (report == null ? "" : "; survey #" + report.surveyId() + ", lines +"
                        + report.linesCreated() + " ~" + report.linesUpdated());
                case SKIPPED -> label + ": skipped, " + detail;
                case FAILED -> label + ": failed, " + detail;
            };
        }
    }

    private static final class Job {
        final SurveyDue due;
        final String trigger;
        SurveyWorld world;
        AreaShape shape;
        List<SurveyCounter.SharedArea> shared = List.of();
        boolean mayLoad;
        final ArrayDeque<long[]> pending = new ArrayDeque<>();
        final Map<Long, BlockSnapshotSource.ChunkBlocks> captured = new HashMap<>();
        int loading;
        int absent;
        boolean counting;
        long startedAt;

        Job(SurveyDue due, String trigger) {
            this.due = due;
            this.trigger = trigger;
        }
    }

    private final SurveySettings settings;
    private final ProductionSurveyApi api;
    private final Function<String, SurveyWorld> worlds;
    private final AreaSpec.RegionShapes regions;
    private final PlaceholderOverlay overlay;
    private final Executor counting;
    private final Executor mainThread;
    private final Logger logger;
    private final Clock clock;
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private final Set<Integer> queued = new HashSet<>();
    private final Set<Integer> notable = new HashSet<>();
    private final ArrayDeque<Outcome> recent = new ArrayDeque<>();
    private final List<Consumer<Outcome>> listeners = new CopyOnWriteArrayList<>();
    private Job current;
    private long chunksCaptured;
    private long surveysReported;

    public SurveyRunner(SurveySettings settings, ProductionSurveyApi api, Function<String, SurveyWorld> worlds,
                        AreaSpec.RegionShapes regions, PlaceholderOverlay overlay, Executor counting, Executor mainThread,
                        Logger logger, Clock clock) {
        this.settings = settings;
        this.api = api;
        this.worlds = worlds;
        this.regions = regions == null ? AreaSpec.RegionShapes.NONE : regions;
        this.overlay = overlay == null ? PlaceholderOverlay.NONE : overlay;
        this.counting = counting;
        this.mainThread = mainThread;
        this.logger = logger;
        this.clock = clock;
    }

    /** Queues a due area unless it is queued or running already. Returns whether it was queued. */
    public boolean enqueue(SurveyDue due) {
        if (due == null || !queued.add(due.area().areaId())) {
            return false;
        }
        String trigger = notable.remove(due.area().areaId()) ? "NotableChange" : null;
        queue.add(new Job(due, trigger));
        return true;
    }

    /** The area's next survey is reported as {@code NotableChange} (it was requested after a large change). */
    public void markNotable(int areaId) {
        notable.add(areaId);
    }

    /** The request after a notable change was refused: the next survey of the area is an ordinary one. */
    public void unmarkNotable(int areaId) {
        notable.remove(areaId);
    }

    public boolean idle() {
        return current == null && queue.isEmpty();
    }

    public int queueSize() {
        return queue.size();
    }

    public void addListener(Consumer<Outcome> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<Outcome> listener) {
        listeners.remove(listener);
    }

    public List<Outcome> recent() {
        return List.copyOf(recent);
    }

    public long chunksCaptured() {
        return chunksCaptured;
    }

    public long surveysReported() {
        return surveysReported;
    }

    /** What runs now: the area and its progress, or null. */
    public String currentDescription() {
        Job job = current;
        if (job == null) {
            return null;
        }
        long total = job.shape == null ? 0 : job.shape.chunkCount();
        return job.due.area().label() + (job.counting ? " (counting)" : " (captured " + job.captured.size() + "/" + total + " chunks)");
    }

    /** Clears the queue (shutdown). */
    public void clear() {
        queue.clear();
        queued.clear();
        current = null;
    }

    /** Main thread, every tick. */
    public void tick() {
        if (current == null) {
            Job next = queue.poll();
            if (next == null) {
                return;
            }
            current = next;
            if (!start(next)) {
                return;
            }
        }
        Job job = current;
        if (job.counting) {
            return;
        }
        if (clock.millis() - job.startedAt > CAPTURE_TIMEOUT_MILLIS) {
            finish(job, Status.FAILED, "capture timed out (" + job.captured.size() + " chunks captured)", null, null);
            return;
        }
        int budget = settings.chunksPerTick();
        Iterator<long[]> it = job.pending.iterator();
        while (budget > 0 && it.hasNext()) {
            long[] chunk = it.next();
            int cx = (int) chunk[0];
            int cz = (int) chunk[1];
            if (job.world.isLoaded(cx, cz)) {
                it.remove();
                job.captured.put(CapturedChunks.key(cx, cz), job.world.capture(cx, cz));
                chunksCaptured++;
                budget--;
            } else if (job.mayLoad) {
                if (job.loading >= MAX_LOADS_IN_FLIGHT) {
                    break;
                }
                it.remove();
                job.loading++;
                budget--;
                job.world.loadAndCapture(cx, cz).whenComplete((blocks, error) -> mainThread.execute(() -> {
                    job.loading--;
                    if (blocks == null) {
                        job.absent++;
                    } else {
                        job.captured.put(CapturedChunks.key(cx, cz), blocks);
                        chunksCaptured++;
                    }
                }));
            } else {
                finish(job, Status.SKIPPED, "chunk " + cx + " " + cz + " is not loaded (Scheduled surveys wait for loaded"
                        + " chunks; production.surveys.scheduled-loads-chunks)", null, null);
                return;
            }
        }
        if (job.pending.isEmpty() && job.loading == 0) {
            count(job);
        }
    }

    /** Resolves world and shapes; false when the job ended at once. */
    private boolean start(Job job) {
        job.startedAt = clock.millis();
        AreaSpec area = job.due.area();
        SurveyWorld world = area.worldName() == null ? null : worlds.apply(area.worldName());
        if (world == null) {
            finish(job, Status.SKIPPED, "world " + area.worldName() + " is not loaded on this server", null, null);
            return false;
        }
        job.world = world;
        try {
            job.shape = area.resolve(regions, world.minY(), world.maxY());
        } catch (IllegalArgumentException e) {
            finish(job, Status.FAILED, e.getMessage(), null, null);
            return false;
        }
        String refusal = settings.refusal(job.shape);
        if (refusal != null) {
            finish(job, Status.SKIPPED, "the area " + refusal, null, null);
            return false;
        }
        List<SurveyCounter.SharedArea> shared = new ArrayList<>();
        for (AreaSpec overlap : job.due.overlaps()) {
            try {
                shared.add(new SurveyCounter.SharedArea(overlap.areaId(), overlap.structureId(),
                        overlap.resolve(regions, world.minY(), world.maxY()), job.due.unitTypesOf(overlap.areaId())));
            } catch (IllegalArgumentException e) {
                logger.warning("Survey of area " + area.label() + ": overlapping area #" + overlap.areaId()
                        + " left out of the shared split: " + e.getMessage());
            }
        }
        job.shared = shared;
        job.mayLoad = !job.due.scheduled() || settings.scheduledLoadsChunks();
        int unloaded = 0;
        for (int cx = job.shape.minX() >> 4; cx <= job.shape.maxX() >> 4; cx++) {
            for (int cz = job.shape.minZ() >> 4; cz <= job.shape.maxZ() >> 4; cz++) {
                job.pending.add(new long[] {cx, cz});
                if (!job.mayLoad && !world.isLoaded(cx, cz)) {
                    unloaded++;
                }
            }
        }
        if (unloaded > 0) {
            finish(job, Status.SKIPPED, unloaded + " of " + job.pending.size() + " chunks not loaded (a Scheduled survey"
                    + " waits for loaded chunks; production.surveys.scheduled-loads-chunks)", null, null);
            return false;
        }
        return true;
    }

    private void count(Job job) {
        job.counting = true;
        SurveyProfile profile = SurveyProfile.parse(job.due.profileJson(), job.due.profileVersion());
        if (profile.unitTypes().isEmpty()) {
            finish(job, Status.FAILED, "the kind's survey profile counts nothing" + (profile.warnings().isEmpty() ? ""
                    : " (" + String.join("; ", profile.warnings()) + ")"), null, null);
            return;
        }
        List<SurveyCounter.EntitySighting> entities = profile.counts(SurveyProfile.UnitType.ENTITY)
                ? job.world.entities(job.shape) : List.of();
        SurveyCounter.Input input = new SurveyCounter.Input(job.world.name(), job.due.area().structureId(), job.shape,
                profile, new CapturedChunks(job.world.minY(), job.world.maxY(), job.captured), overlay, entities, job.shared);
        Instant surveyedAt = clock.instant();
        CompletableFuture.supplyAsync(() -> SurveyCounter.count(input), counting)
                .thenCompose(result -> api.report(job.due.area().areaId(), new ProductionSurveyApi.Report(surveyedAt,
                                job.due.profileVersion(), job.trigger, result.contentHash(), note(result, job, profile), result.units()))
                        .thenApply(report -> new Object[] {result, report}))
                .whenComplete((pair, error) -> mainThread.execute(() -> {
                    if (error != null) {
                        finish(job, Status.FAILED, describe(error), null, null);
                    } else {
                        surveysReported++;
                        finish(job, Status.REPORTED, null, (SurveyCounter.Result) pair[0],
                                (ProductionSurveyApi.ReportResult) pair[1]);
                    }
                }));
    }

    private static String note(SurveyCounter.Result result, Job job, SurveyProfile profile) {
        String note = "plugin survey: " + result.note() + (job.absent > 0 ? ", chunks never generated " + job.absent : "")
                + (profile.warnings().isEmpty() ? "" : "; profile: " + String.join("; ", profile.warnings()));
        return note.length() > 500 ? note.substring(0, 500) : note;
    }

    public static String describe(Throwable error) {
        Throwable cause = error;
        while (cause != null && !(cause instanceof ApiException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof ApiException api && api.getStatusCode() > 0) {
            String body = api.getResponseBody();
            return "API " + api.getStatusCode() + (body == null || body.isBlank() ? "" : ": "
                    + (body.length() > 200 ? body.substring(0, 200) : body));
        }
        return String.valueOf(cause == null ? error : cause.getMessage());
    }

    private void finish(Job job, Status status, String detail, SurveyCounter.Result result,
                        ProductionSurveyApi.ReportResult report) {
        if (current == job) {
            current = null;
        }
        queued.remove(job.due.area().areaId());
        Outcome outcome = new Outcome(job.due, status, detail, clock.instant(), result, report);
        recent.addFirst(outcome);
        while (recent.size() > RECENT) {
            recent.removeLast();
        }
        if (status != Status.FAILED) {
            logger.info("Survey " + outcome.summary());
        } else {
            logger.warning("Survey " + outcome.summary());
        }
        for (Consumer<Outcome> listener : listeners) {
            try {
                listener.accept(outcome);
            } catch (RuntimeException e) {
                logger.warning("Survey listener failed: " + e);
            }
        }
    }
}
