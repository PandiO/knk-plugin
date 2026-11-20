package net.knightsandkings.knk.core.production.transport;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.metrics.PluginMetrics;
import net.knightsandkings.knk.core.ports.api.TransportLanesApi;

/**
 * One measuring run (PandiO/knk-workspace#681), the headless job's and {@code /knk logistics lanes measure}'s:
 * <ol>
 *   <li>reads the API's lane list ({@code batch} pairs);</li>
 *   <li>measures each pair with the {@link LaneMeasurer} on the {@code measuring} executor (never the main thread;
 *       timed as {@code production.lanes.measure});</li>
 *   <li>reports the measured lanes, at most 500 per call; skipped pairs are not reported and stay due;</li>
 *   <li>with {@code roadConnections}, judges every structure of the measured lanes in the {@link RoadConnectionBook} and
 *       sends the states that changed; a 404 marks a structure that is not a producer (a warehouse).</li>
 * </ol>
 * One run at a time: a second call while one runs gets the running one's result.
 */
public final class LaneJob {

    /** What a run did. {@code error} is set when it stopped early (the lane list or a report failed). */
    public record Summary(String why, Instant at, int due, int reachable, int unreachable, int skipped, int created,
                          int updated, Map<Integer, RoadConnection> connectionsSent, List<String> notes, String error) {
        public Summary {
            connectionsSent = connectionsSent == null ? Map.of() : Map.copyOf(connectionsSent);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }

        public int measured() {
            return reachable + unreachable;
        }

        public String line() {
            if (error != null && due == 0) {
                return "lane list not read: " + error;
            }
            return due + " due, " + reachable + " reachable, " + unreachable + " without a road, " + skipped
                    + " skipped; API created " + created + ", updated " + updated + "; road connections sent "
                    + connectionsSent.size() + (error == null ? "" : "; stopped: " + error);
        }
    }

    /** At most this many notes (per-lane lines) are kept for the command and the log. */
    static final int MAX_NOTES = 50;

    private final TransportLanesApi api;
    private final LaneMeasurer measurer;
    private final RoadConnectionBook book;
    private final LaneSettings settings;
    private final Executor measuring;
    private final Logger logger;
    private final Clock clock;
    private CompletableFuture<Summary> running;
    private volatile Summary last;

    public LaneJob(TransportLanesApi api, LaneMeasurer measurer, RoadConnectionBook book, LaneSettings settings,
                   Executor measuring, Logger logger, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.measurer = Objects.requireNonNull(measurer, "measurer");
        this.book = Objects.requireNonNull(book, "book");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.measuring = Objects.requireNonNull(measuring, "measuring");
        this.logger = logger == null ? Logger.getLogger(LaneJob.class.getName()) : logger;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** The last finished run, or null. */
    public Summary last() {
        return last;
    }

    public synchronized boolean isRunning() {
        return running != null && !running.isDone();
    }

    public RoadConnectionBook book() {
        return book;
    }

    /** Starts a run, or returns the one running now. Never completes exceptionally: errors are in the summary. */
    public synchronized CompletableFuture<Summary> run(String why) {
        if (isRunning()) {
            return running;
        }
        Instant at = clock.instant();
        CompletableFuture<Summary> run;
        try {
            run = api.due(settings.batch())
                    .thenApplyAsync(due -> measureAll(due), measuring)
                    .thenCompose(measured -> report(why, at, measured))
                    .exceptionally(e -> {
                        String reason = reason(e);
                        logger.warning("[Lanes] Run (" + why + ") stopped: " + reason);
                        return new Summary(why, at, 0, 0, 0, 0, 0, 0, Map.of(), List.of(), reason);
                    });
        } catch (RuntimeException e) {
            run = CompletableFuture.completedFuture(new Summary(why, at, 0, 0, 0, 0, 0, 0, Map.of(), List.of(), reason(e)));
        }
        running = run.thenApply(summary -> {
            last = summary;
            logger.info("[Lanes] Run (" + why + "): " + summary.line());
            return summary;
        });
        return running;
    }

    private record Measured(List<LaneDue> due, List<LaneMeasurer.Outcome> outcomes) {
    }

    private Measured measureAll(List<LaneDue> due) {
        List<LaneMeasurer.Outcome> outcomes = new ArrayList<>(due.size());
        PluginMetrics.timed("production.lanes.measure", () -> {
            for (LaneDue pair : due) {
                try {
                    outcomes.add(measurer.measure(pair));
                } catch (RuntimeException e) {
                    logger.log(Level.WARNING, "[Lanes] Measuring " + pair.label() + " failed", e);
                    outcomes.add(new LaneMeasurer.Outcome(pair, null, "measuring failed: " + e.getMessage()));
                }
            }
        }).run();
        return new Measured(due, outcomes);
    }

    private CompletableFuture<Summary> report(String why, Instant at, Measured m) {
        List<LaneMeasurement> lanes = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int reachable = 0;
        int unreachable = 0;
        int skipped = 0;
        for (LaneMeasurer.Outcome outcome : m.outcomes()) {
            if (!outcome.measured()) {
                skipped++;
                note(notes, outcome.due().label() + ": skipped, " + outcome.skipped());
                continue;
            }
            LaneMeasurement lane = outcome.measurement();
            lanes.add(lane);
            if (lane.reachable()) {
                reachable++;
                note(notes, outcome.due().label() + ": " + lane.roadDistance() + " blocks by road");
            } else {
                unreachable++;
                note(notes, outcome.due().label() + ": no road (" + lane.why() + ")");
            }
        }
        int dueCount = m.due().size();
        int r = reachable;
        int u = unreachable;
        int s = skipped;
        CompletableFuture<int[]> sent = CompletableFuture.completedFuture(new int[2]);
        for (int from = 0; from < lanes.size(); from += LaneSettings.MAX_REPORT) {
            List<LaneMeasurement> chunk = lanes.subList(from, Math.min(lanes.size(), from + LaneSettings.MAX_REPORT));
            sent = sent.thenCompose(total -> api.report(new ArrayList<>(chunk)).thenApply(result -> {
                total[0] += result.created();
                total[1] += result.updated();
                return total;
            }));
        }
        return sent.handle((total, error) -> {
            if (error != null) {
                return CompletableFuture.completedFuture(new Summary(why, at, dueCount, r, u, s, 0, 0, Map.of(), notes,
                        "report failed: " + reason(error)));
            }
            lanes.forEach(book::record);
            if (!settings.roadConnections() || lanes.isEmpty()) {
                return CompletableFuture.completedFuture(
                        new Summary(why, at, dueCount, r, u, s, total[0], total[1], Map.of(), notes, null));
            }
            return connections(lanes, notes).thenApply(connections ->
                    new Summary(why, at, dueCount, r, u, s, total[0], total[1], connections, notes, null));
        }).thenCompose(f -> f);
    }

    /** Sends the changed road connections one after another (a handful per run) and returns what was sent. */
    private CompletableFuture<Map<Integer, RoadConnection>> connections(List<LaneMeasurement> lanes, List<String> notes) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (LaneMeasurement lane : lanes) {
            ids.add(lane.fromStructureId());
            ids.add(lane.toStructureId());
        }
        Map<Integer, RoadConnection> sent = new LinkedHashMap<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Map.Entry<Integer, RoadConnection> judged : book.judge(ids).entrySet()) {
            int id = judged.getKey();
            RoadConnection state = judged.getValue();
            if (!book.shouldSend(id, state)) {
                continue;
            }
            chain = chain.thenCompose(v -> api.setRoadConnection(id, state).handle((ok, error) -> {
                if (error == null) {
                    book.sent(id, state);
                    synchronized (sent) {
                        sent.put(id, state);
                    }
                    note(notes, "#" + id + " road connection: " + state);
                } else if (status(error) == 404) {
                    book.notAProducer(id); // a warehouse: no road connection of its own
                } else {
                    note(notes, "#" + id + " road connection not sent: " + reason(error));
                    logger.warning("[Lanes] Road connection of #" + id + " not sent: " + reason(error));
                }
                return null;
            }));
        }
        return chain.thenApply(v -> {
            synchronized (sent) {
                return new LinkedHashMap<>(sent);
            }
        });
    }

    private static void note(List<String> notes, String note) {
        synchronized (notes) {
            if (notes.size() < MAX_NOTES) {
                notes.add(note);
            }
        }
    }

    private static Throwable unwrap(Throwable e) {
        Throwable t = e;
        while (t instanceof CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    static int status(Throwable e) {
        return unwrap(e) instanceof ApiException api ? api.getStatusCode() : 0;
    }

    static String reason(Throwable e) {
        Throwable t = unwrap(e);
        if (t instanceof ApiException api && api.getStatusCode() > 0) {
            return "HTTP " + api.getStatusCode() + (t.getMessage() == null ? "" : " " + t.getMessage());
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
