package net.knightsandkings.knk.core.roads.walk;

import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of a walk search (KNG-51 {@code LAST_MILE_PATHFINDING.md} §5, §7). There are no
 * partial paths (decision §11-5): either the whole way to an arrived cell, or no path and the
 * caller keeps today's straight line.
 *
 * <ul>
 *   <li>{@link Status#FOUND} — {@link #path()} is present.</li>
 *   <li>{@link Status#NO_PATH} — no walkable cell near the start or the target, or the reachable
 *       area was searched completely without arriving: the target is unreachable.</li>
 *   <li>{@link Status#FALLBACK} — a budget ran out (expansions or path length) before the search
 *       could tell; a path may exist.</li>
 * </ul>
 * Direct mode treats NO_PATH and FALLBACK alike (the leg's FALLBACK state, §7); the split is for
 * debugging and tuning the budget.
 */
public final class WalkResult {

    public enum Status { FOUND, NO_PATH, FALLBACK }

    private final Status status;
    private final WalkPath path;
    private final int expansions;
    private final String reason;

    private WalkResult(Status status, WalkPath path, int expansions, String reason) {
        this.status = Objects.requireNonNull(status, "status");
        this.path = path;
        this.expansions = expansions;
        this.reason = reason;
    }

    public static WalkResult found(WalkPath path, int expansions) {
        return new WalkResult(Status.FOUND, Objects.requireNonNull(path, "path"), expansions, "found");
    }

    public static WalkResult noPath(String reason, int expansions) {
        return new WalkResult(Status.NO_PATH, null, expansions, Objects.requireNonNull(reason, "reason"));
    }

    public static WalkResult fallback(String reason, int expansions) {
        return new WalkResult(Status.FALLBACK, null, expansions, Objects.requireNonNull(reason, "reason"));
    }

    public Status status() {
        return status;
    }

    public boolean isFound() {
        return status == Status.FOUND;
    }

    public Optional<WalkPath> path() {
        return Optional.ofNullable(path);
    }

    /** Cells the search expanded. */
    public int expansions() {
        return expansions;
    }

    /** A short debugging reason ("found", "no walkable cell near the start", "expansion budget" …). */
    public String reason() {
        return reason;
    }

    @Override
    public String toString() {
        return "WalkResult[" + status + ", " + reason + ", expansions=" + expansions
            + (path == null ? "" : ", " + path) + "]";
    }
}
