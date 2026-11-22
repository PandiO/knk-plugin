package net.knightsandkings.knk.paper.realtime;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.knightsandkings.knk.api.realtime.RealtimeEnvelope;
import net.knightsandkings.knk.api.realtime.RegionQueryEvents;
import net.knightsandkings.knk.api.realtime.RegionQueryEvents.RegionQuery;
import net.knightsandkings.knk.core.realtime.RecentActionIds;
import net.knightsandkings.knk.core.regions.query.RegionQueryAnswers;
import net.knightsandkings.knk.core.regions.query.RegionShape;
import net.knightsandkings.knk.core.regions.query.RegionShapeLookup;

/**
 * PandiO/knk-workspace#988: answers one kind of the API's region queries over the game hub, as
 * {@link GateCommandRealtimeSync} answers gate commands. Parses the query and skips a query id seen before on the
 * realtime thread, finds the region with {@link RegionShapeLookup} and builds the answer on {@code mainThread}
 * (WorldGuard and block reads), then sends it ({@link RealtimeService#send} only queues it).
 * {@link #geometry} is the generic {@code RegionGeometryQuery} (always registered); the siege weapons module builds the
 * {@code SpotSiteCheckQuery} one with {@link #of}, only with {@code siege.weapons.enabled}.
 */
public final class RegionQueryRealtimeSync {

    private static final String TAG = "[KnK Regions] ";

    /** Builds the answer body for a found region. Main thread. */
    @FunctionalInterface
    public interface Answer {
        Map<String, Object> answer(String queryId, RegionShape shape);
    }

    private final String queryType;
    private final String resultMethod;
    private final Executor mainThread;
    private final RegionShapeLookup lookup;
    private final Answer answer;
    private final BiPredicate<String, Object> hubSend;
    private final RecentActionIds handled;
    private final Logger logger;

    private RegionQueryRealtimeSync(String queryType, String resultMethod, Executor mainThread, RegionShapeLookup lookup,
                                    Answer answer, BiPredicate<String, Object> hubSend, RecentActionIds handled, Logger logger) {
        this.queryType = Objects.requireNonNull(queryType, "queryType");
        this.resultMethod = Objects.requireNonNull(resultMethod, "resultMethod");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.answer = Objects.requireNonNull(answer, "answer");
        this.hubSend = Objects.requireNonNull(hubSend, "hubSend");
        this.handled = handled == null ? new RecentActionIds() : handled;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** The generic region geometry query: world, kind, min and max corner, points. */
    public static RegionQueryRealtimeSync geometry(Executor mainThread, RegionShapeLookup lookup,
                                                   BiPredicate<String, Object> hubSend, Logger logger) {
        return of(RegionQueryEvents.REGION_GEOMETRY_QUERY, RegionQueryEvents.REGION_GEOMETRY_RESULT, mainThread, lookup,
                RegionQueryAnswers::geometry, hubSend, logger);
    }

    public static RegionQueryRealtimeSync of(String queryType, String resultMethod, Executor mainThread,
                                             RegionShapeLookup lookup, Answer answer, BiPredicate<String, Object> hubSend,
                                             Logger logger) {
        return new RegionQueryRealtimeSync(queryType, resultMethod, mainThread, lookup, answer, hubSend,
                new RecentActionIds(), logger);
    }

    /** Registers the handler (before the client starts: it listens for the registered types). */
    public void register(RealtimeDispatcher dispatcher) {
        dispatcher.register(queryType, 1, Runnable::run, this::onQuery);
    }

    public String queryType() {
        return queryType;
    }

    /** Realtime thread. */
    void onQuery(RealtimeEnvelope envelope) {
        RegionQuery query = RegionQuery.parse(envelope);
        if (query == null) {
            logger.warning(TAG + queryType + " (seq " + (envelope == null ? "?" : envelope.seq())
                    + ") has no usable query id or region id; ignored");
            return;
        }
        if (!handled.firstTime(query.queryId())) {
            logger.fine(TAG + queryType + " " + query.queryId() + " was answered already; skipped");
            return;
        }
        mainThread.execute(() -> run(query));
    }

    /** Main thread. */
    void run(RegionQuery query) {
        Map<String, Object> body;
        try {
            Optional<RegionShape> shape = lookup.find(query.regionId(), query.world());
            body = shape.isPresent() ? answer.answer(query.queryId(), shape.get())
                    : RegionQueryAnswers.notFound(query.queryId(), query.regionId(), query.world());
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, TAG + queryType + " " + query.queryId() + " for " + query.regionId() + " failed", e);
            body = RegionQueryAnswers.error(query.queryId(), "The game server failed to read the region: " + e.getMessage());
        }
        logger.fine(TAG + queryType + " " + query.regionId() + ": " + body.get("result"));
        if (!hubSend.test(resultMethod, body)) {
            logger.warning(TAG + "Answer to " + queryType + " " + query.queryId()
                    + " not sent (hub not connected); the API reports it couldn't check");
        }
    }
}
