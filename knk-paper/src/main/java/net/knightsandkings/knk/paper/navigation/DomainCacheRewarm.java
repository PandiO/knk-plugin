package net.knightsandkings.knk.paper.navigation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
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
 * A failed warm-up still sends the second event. It does nothing while NPC navigation does not run
 * ({@code npc.navigation.enabled: false}): player routes are not changed by it.
 *
 * <p>PandiO/knk-workspace#724: the first event came with the map empty, so NPCs held at an edge were handed their way
 * and crossed before this re-warm was done. A clear of the map now keeps the last known answers for the NPC checks
 * ({@code RegionDomainResolver.getLastKnownDomain}); this re-warm takes the hold's generation when it starts and, once
 * its answers are in, on the main thread: lets the NPCs' trespass guards look under the answers still in force
 * ({@code beforeRelease}), ends the hold, then sends the event, so the NPCs switch to the new answers at that event.
 * When it warms nothing it ends the hold at once.
 */
public final class DomainCacheRewarm implements Runnable {

    private final BooleanSupplier active;
    private final Supplier<? extends Collection<String>> worlds;
    private final Function<String, Set<String>> regionsOf;
    private final BiFunction<String, Collection<String>, CompletableFuture<Void>> warm;
    private final Executor mainThread;
    private final Runnable changed;
    private final Logger logger;
    private final LongSupplier holdGeneration;
    private final Runnable beforeRelease;
    private final LongConsumer release;

    /**
     * @param active     whether NPC navigation runs now
     * @param worlds     the loaded worlds' names
     * @param regionsOf  the WorldGuard regions a world's road network passes (paper: the road snapshot's regions)
     * @param warm       asks the API about the regions the map does not know (paper: {@code warmCache})
     * @param mainThread runs {@code changed}
     * @param changed    road navigation's availability event
     */
    public DomainCacheRewarm(BooleanSupplier active, Supplier<? extends Collection<String>> worlds, Function<String, Set<String>> regionsOf,
                             BiFunction<String, Collection<String>, CompletableFuture<Void>> warm, Executor mainThread,
                             Runnable changed, Logger logger) {
        this(active, worlds, regionsOf, warm, mainThread, changed, logger, () -> 0L, () -> { }, generation -> { });
    }

    /**
     * PandiO/knk-workspace#724: with the map's hold of the last known answers.
     *
     * @param holdGeneration the hold's generation now (paper: {@code RegionDomainResolver.holdGeneration})
     * @param beforeRelease  the NPCs' trespass guards look under the answers still in force (main thread)
     * @param release        ends the hold taken at a generation (paper: {@code RegionDomainResolver.releaseLastKnown})
     */
    public DomainCacheRewarm(BooleanSupplier active, Supplier<? extends Collection<String>> worlds, Function<String, Set<String>> regionsOf,
                             BiFunction<String, Collection<String>, CompletableFuture<Void>> warm, Executor mainThread,
                             Runnable changed, Logger logger, LongSupplier holdGeneration, Runnable beforeRelease,
                             LongConsumer release) {
        this.holdGeneration = Objects.requireNonNull(holdGeneration, "holdGeneration");
        this.beforeRelease = Objects.requireNonNull(beforeRelease, "beforeRelease");
        this.release = Objects.requireNonNull(release, "release");
        this.active = Objects.requireNonNull(active, "active");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.regionsOf = Objects.requireNonNull(regionsOf, "regionsOf");
        this.warm = Objects.requireNonNull(warm, "warm");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.changed = Objects.requireNonNull(changed, "changed");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void run() {
        long generation = holdGeneration.getAsLong();
        if (!active.getAsBoolean()) {
            release.accept(generation); // no NPC navigation: nobody reads the held answers
            return;
        }
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
            release.accept(generation);
            return; // no road regions: the first event had nothing more to learn
        }
        CompletableFuture.allOf(warming.toArray(new CompletableFuture[0]))
            .whenComplete((v, ex) -> mainThread.execute(() -> switchRules(generation)));
    }

    /** The answers are in: the guards look under the held answers, the hold ends, then the event (main thread). */
    private void switchRules(long generation) {
        try {
            beforeRelease.run();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "[Navigation] The NPC trespass guards' look before the rule switch failed", e);
        } finally {
            release.accept(generation);
        }
        changed.run();
    }
}
