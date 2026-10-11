package net.knightsandkings.knk.core.roads.walk;

import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of a walk search (KNG-51 {@code LAST_MILE_PATHFINDING.md} §5, §7): the whole way to an
 * arrived cell, or no path.
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
 *
 * <p><b>Partial path</b> (decision §11-5, revised 2026-10-07): a NO_PATH or FALLBACK result may carry
 * {@link #partialPath()} — the walkable way to the searched cell closest to the target, when that cell
 * is clearly closer than the start. The caller may follow it and show the rest as a straight line.
 * {@link #path()} stays empty: the status says the target was not reached.
 */
public final class WalkResult {

    public enum Status { FOUND, NO_PATH, FALLBACK }

    private final Status status;
    private final WalkPath path;
    private final int expansions;
    private final String reason;
    private final WalkPath partial;

    private WalkResult(Status status, WalkPath path, int expansions, String reason, WalkPath partial) {
        this.status = Objects.requireNonNull(status, "status");
        this.path = path;
        this.expansions = expansions;
        this.reason = reason;
        this.partial = partial;
    }

    public static WalkResult found(WalkPath path, int expansions) {
        return new WalkResult(Status.FOUND, Objects.requireNonNull(path, "path"), expansions, "found", null);
    }

    public static WalkResult noPath(String reason, int expansions) {
        return noPath(reason, expansions, null);
    }

    /** NO_PATH with the way to the reachable cell closest to the target (null for none). */
    public static WalkResult noPath(String reason, int expansions, WalkPath partial) {
        return new WalkResult(Status.NO_PATH, null, expansions, Objects.requireNonNull(reason, "reason"), partial);
    }

    public static WalkResult fallback(String reason, int expansions) {
        return fallback(reason, expansions, null);
    }

    /** FALLBACK with the way to the searched cell closest to the target (null for none). */
    public static WalkResult fallback(String reason, int expansions, WalkPath partial) {
        return new WalkResult(Status.FALLBACK, null, expansions, Objects.requireNonNull(reason, "reason"), partial);
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

    /** NO_PATH / FALLBACK only: the way to the searched cell closest to the target, if it gets clearly closer. */
    public Optional<WalkPath> partialPath() {
        return Optional.ofNullable(partial);
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
            + (path == null ? "" : ", " + path) + (partial == null ? "" : ", partial " + partial) + "]";
    }
}
