package net.knightsandkings.knk.paper.realtime;

import net.knightsandkings.knk.api.realtime.ConfigEvents;
import net.knightsandkings.knk.core.realtime.Coalescer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code GateChanged} on the game server (PandiO/knk-workspace#182, DESIGN §6.2): a gate edited in the web app is
 * reloaded on its own (the {@code /gate reload} path, one structure), a deleted one is dropped from the cache. A gate a
 * running siege match holds is not touched until that match released it (decision Q12): the event waits and is applied
 * after the gates were restored ({@link #matchReleased()}). Bursts for one structure (a structure and its doors in one
 * form) are merged by the {@link Coalescer}, whose scheduler runs on the main thread.
 */
public final class GateRealtimeSync {

    private final Coalescer coalescer;
    private final IntPredicate lockedByMatch;
    private final IntFunction<CompletableFuture<?>> reloadStructure;
    private final IntConsumer removeStructure;
    private final Logger logger;
    /** Structure id → deleted? Main thread only (the coalescer's actions and the siege observer run there). */
    private final Map<Integer, Boolean> deferred = new LinkedHashMap<>();
    /** Latest op per structure while a request waits in the coalescer; any thread. */
    private final Map<Integer, Boolean> latest = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * @param lockedByMatch   whether a running siege match holds the structure (main thread)
     * @param reloadStructure re-reads one structure with its doors and snapshots and replaces the cached one
     * @param removeStructure drops a deleted structure and its doors from the cache
     */
    public GateRealtimeSync(Coalescer coalescer, IntPredicate lockedByMatch, IntFunction<CompletableFuture<?>> reloadStructure,
                            IntConsumer removeStructure, Logger logger) {
        this.coalescer = Objects.requireNonNull(coalescer, "coalescer");
        this.lockedByMatch = lockedByMatch == null ? id -> false : lockedByMatch;
        this.reloadStructure = Objects.requireNonNull(reloadStructure, "reloadStructure");
        this.removeStructure = Objects.requireNonNull(removeStructure, "removeStructure");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Realtime thread: the event; applied once the burst is over. */
    public void onGateChanged(ConfigEvents.GateChanged event) {
        if (event == null) {
            return;
        }
        int id = event.gateStructureId();
        latest.put(id, event.deleted());
        coalescer.request("gate:" + id, () -> apply(id, Boolean.TRUE.equals(latest.remove(id))));
    }

    /** Main thread: a siege round released its gates (after their restore); waiting edits are applied now. */
    public void matchReleased() {
        for (Map.Entry<Integer, Boolean> entry : List.copyOf(deferred.entrySet())) {
            if (!lockedByMatch.test(entry.getKey())) {
                deferred.remove(entry.getKey());
                apply(entry.getKey(), entry.getValue());
            }
        }
    }

    /** Structures whose edit waits for the end of a match. */
    public List<Integer> deferred() {
        return List.copyOf(deferred.keySet());
    }

    /** Main thread. */
    void apply(int gateStructureId, boolean deleted) {
        if (lockedByMatch.test(gateStructureId)) {
            // A later edit of the same gate replaces the waiting one; a delete stays a delete.
            deferred.merge(gateStructureId, deleted, (before, now) -> before || now);
            logger.info("Realtime: gate structure " + gateStructureId + " was edited in the web app during a siege match; "
                + "applied after the match");
            return;
        }
        if (deleted) {
            removeStructure.accept(gateStructureId);
            logger.info("Realtime: gate structure " + gateStructureId + " was deleted in the web app; removed from the cache");
            return;
        }
        try {
            reloadStructure.apply(gateStructureId).whenComplete((ignored, error) -> {
                if (error != null) {
                    logger.log(Level.WARNING, "Realtime: reloading gate structure " + gateStructureId
                        + " failed; /gate reload or the next restart picks the edit up", error);
                }
            });
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Realtime: reloading gate structure " + gateStructureId + " failed", e);
        }
    }
}
