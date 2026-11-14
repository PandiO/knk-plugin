package net.knightsandkings.knk.paper.navigation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PandiO/knk-workspace#506: {@code /knk cache refresh} forgets the region → domain map (the "region domains" hook) and
 * then tells road navigation that availability may have changed (the "navigation routes" hook). The NPC rule checks,
 * a group's follower lanes among them, answer from that map without asking the API, and a region of unknown domain
 * is open to them; so right after a refresh every district looked open until something warmed the map again (the
 * road tags' next pass, up to a minute later), and a group walked into a district whose entry had just been removed.
 * This hook runs after both: it warms the map again for the regions of every world's road network and then tells
 * road navigation once more, on the main thread, so the checks hear of the change with the current rules in the map.
 * A failed warm-up still sends the second event.
 */
public final class DomainCacheRewarm implements Runnable {

    private final Supplier<? extends Collection<String>> worlds;
    private final Function<String, Set<String>> regionsOf;
    private final BiFunction<String, Collection<String>, CompletableFuture<Void>> warm;
    private final Executor mainThread;
    private final Runnable changed;
    private final Logger logger;

    /**
     * @param worlds     the loaded worlds' names
     * @param regionsOf  the WorldGuard regions a world's road network passes (paper: the road snapshot's regions)
     * @param warm       asks the API about the regions the map does not know (paper: {@code warmCache})
     * @param mainThread runs {@code changed}
     * @param changed    road navigation's availability event
     */
    public DomainCacheRewarm(Supplier<? extends Collection<String>> worlds, Function<String, Set<String>> regionsOf,
                             BiFunction<String, Collection<String>, CompletableFuture<Void>> warm, Executor mainThread,
                             Runnable changed, Logger logger) {
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.regionsOf = Objects.requireNonNull(regionsOf, "regionsOf");
        this.warm = Objects.requireNonNull(warm, "warm");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.changed = Objects.requireNonNull(changed, "changed");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void run() {
        List<CompletableFuture<Void>> warming = new ArrayList<>();
        for (String world : worlds.get()) {
            Set<String> regions = regionsOf.apply(world);
            if (regions == null || regions.isEmpty()) {
                continue;
            }
            try {
                CompletableFuture<Void> done = warm.apply(world, regions);
                if (done != null) {
                    warming.add(done.exceptionally(ex -> null));
                }
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "[Navigation] Warming the region domains of " + world + " failed", e);
            }
        }
        if (warming.isEmpty()) {
            return; // no road regions: the first event had nothing more to learn
        }
        CompletableFuture.allOf(warming.toArray(new CompletableFuture[0]))
            .whenComplete((v, ex) -> mainThread.execute(changed));
    }
}
