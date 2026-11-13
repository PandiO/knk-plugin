package net.knightsandkings.knk.paper.npc.nav;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.World;

import net.knightsandkings.knk.core.npc.nav.AccessSubject;
import net.knightsandkings.knk.core.npc.nav.LocalLeg;
import net.knightsandkings.knk.core.npc.nav.LocalLegPreparer;
import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NpcAccessSubject;
import net.knightsandkings.knk.paper.navigation.walk.WalkBox;
import net.knightsandkings.knk.paper.navigation.walk.WalkLegPreparer;

/**
 * {@link LocalLegPreparer} on player navigation's walk capture (PandiO/knk-workspace#360): the box around the leg is
 * captured from loaded chunks on the main thread, the subject's gate, door and region access is decided in it
 * ({@link WalkLegPreparer#capture}), the domain part is resolved on the walk executor (lookups may block), and the
 * leg is delivered on the main thread.
 */
public final class NpcLegPreparer implements LocalLegPreparer {

    private final WalkLegPreparer walk;
    private final Executor searches;
    private final Executor mainThread;
    private final Function<String, World> worlds;
    private final Logger logger;

    /**
     * @param searches   the walk executor ({@code NavigationService.Walk#searches})
     * @param mainThread runs a task on the server thread
     * @param worlds     a loaded world by name, or null
     */
    public NpcLegPreparer(WalkLegPreparer walk, Executor searches, Executor mainThread,
                          Function<String, World> worlds, Logger logger) {
        this.walk = Objects.requireNonNull(walk, "walk");
        this.searches = Objects.requireNonNull(searches, "searches");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public CompletableFuture<Prepared> prepare(AccessSubject subject, NavPoint feet, NavPoint targetFloor) {
        World world = worlds.apply(feet.world());
        if (world == null) {
            return CompletableFuture.completedFuture(Prepared.of(Status.FAILED, "no world " + feet.world()));
        }
        // doors: the ruleset's switch for the engine's door handling; single doors are still vetoed by the access
        boolean allowDoors = !(subject instanceof NpcAccessSubject npc) || npc.profile().openDoors();
        CompletableFuture<Prepared> out = new CompletableFuture<>();
        walk.capture(subject, world, new double[] {feet.x(), feet.y(), feet.z()},
            new double[] {targetFloor.x(), targetFloor.y(), targetFloor.z()}).whenComplete((capture, error) -> {
                if (error != null || capture == null) {
                    logger.log(Level.WARNING, "[NPC] local leg capture failed for " + subject.name(), error);
                    out.complete(Prepared.of(Status.FAILED, "capture error"));
                    return;
                }
                switch (capture.status()) {
                    case UNLOADED -> out.complete(Prepared.of(Status.UNLOADED, "unloaded chunk in the leg's box"));
                    case TOO_LARGE -> out.complete(Prepared.of(Status.TOO_LARGE,
                        "box of " + capture.box().chunkCount() + " chunks"));
                    default -> CompletableFuture.supplyAsync(capture.access(), searches)
                        .whenComplete((access, buildError) -> mainThread.execute(() -> {
                            if (buildError != null || access == null) {
                                logger.log(Level.WARNING, "[NPC] local leg access failed for " + subject.name(), buildError);
                                out.complete(Prepared.of(Status.FAILED, "access error"));
                                return;
                            }
                            WalkBox box = capture.box();
                            out.complete(Prepared.ready(new LocalLeg(access, box.minX(), box.minY(), box.minZ(),
                                box.maxX(), box.maxY(), box.maxZ(), allowDoors)));
                        }));
                }
            });
        return out;
    }
}
