package net.knightsandkings.knk.core.npc.nav;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;

/**
 * The off-road part of an NPC's route planned with the walk navigator's search (PandiO/knk-workspace#700, link C1):
 * the start to the road, the road's end to the goal, and a trip with no road. Paper: the walk capture of loaded chunks
 * around the leg, the subject's gate, door and region access in it, and today's walk search with the walk settings'
 * budget ({@code navigation.walk.*}: {@code max-length}, {@code detour-allowance}, {@code climb-allowance},
 * {@code max-drop}, {@code max-expansions}). The NPC service walks a found path as part of its route line (stretches,
 * 3D handover, the join from the feet, surface heights); without one it falls back to today's hops.
 * <p>
 * Called on the main thread; the future completes on the main thread.
 */
public interface OffRoadWalks {

    enum Status {
        /** {@link Walk#path} leads to {@link Walk#reached}. */
        FOUND,
        /** The search ran and reached no target (or ran out of its budget before it could tell). */
        NO_PATH,
        /** A chunk of the leg's box is not loaded: chunks are never loaded for a capture. */
        UNLOADED,
        /** The leg's box holds more chunks than one capture may take. */
        TOO_LARGE,
        /** Anything else (no world, an error). */
        FAILED
    }

    /**
     * @param status  the outcome
     * @param path    for {@link Status#FOUND}: the walked cells as floor points (block centres, the floor block's y),
     *                the start's cell first; else empty
     * @param reached for {@link Status#FOUND}: the index in the targets of the one the path arrived at; else -1
     * @param detail  why there is no path, the search's numbers, for the log
     */
    record Walk(Status status, List<NavPoint> path, int reached, String detail) {
        public Walk {
            Objects.requireNonNull(status, "status");
            path = path == null ? List.of() : List.copyOf(path);
            if (status == Status.FOUND && (path.isEmpty() || reached < 0)) {
                throw new IllegalArgumentException("a found walk needs a path and the target it reached");
            }
        }

        public static Walk found(List<NavPoint> path, int reached, String detail) {
            return new Walk(Status.FOUND, path, reached, detail);
        }

        public static Walk none(Status status, String detail) {
            return new Walk(status, List.of(), -1, detail);
        }

        public boolean isFound() {
            return status == Status.FOUND;
        }
    }

    /**
     * One walk search from {@code feet} that arrives at any of {@code targets} (floor points), within {@code arrive}
     * blocks of one (3D, floor positions). The first target is the one the search aims at: its heuristic and its length
     * cap are measured to it. The others are the road points the walk may also join at (the road join by
     * reachability).
     */
    CompletableFuture<Walk> plan(AccessSubject subject, NavPoint feet, List<NavPoint> targets, double arrive);
}
