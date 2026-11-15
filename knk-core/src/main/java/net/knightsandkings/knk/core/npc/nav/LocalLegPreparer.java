package net.knightsandkings.knk.core.npc.nav;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Prepares one local leg for the engine's path search: captures the terrain box around it and decides the subject's
 * access inside it (paper: the walk capture and {@code WalkAccessFactory}). Called on the main thread; the future
 * completes on the main thread. Never loads chunks.
 */
public interface LocalLegPreparer {

    enum Status {
        READY,
        /** The box reaches a chunk that is not loaded: wait at the edge (NAV-12 until Phase 7b). */
        UNLOADED,
        /** The box is larger than a capture may be. */
        TOO_LARGE,
        /** The capture or the access could not be built. */
        FAILED
    }

    /** @param leg the prepared leg for {@link Status#READY}, else null */
    record Prepared(Status status, LocalLeg leg, String detail) {
        public Prepared {
            Objects.requireNonNull(status, "status");
            if (status == Status.READY && leg == null) {
                throw new IllegalArgumentException("READY needs the leg");
            }
        }

        public static Prepared ready(LocalLeg leg) {
            return new Prepared(Status.READY, leg, null);
        }

        public static Prepared of(Status status, String detail) {
            return new Prepared(status, null, detail);
        }
    }

    /**
     * @param feet        where the NPC stands (feet)
     * @param targetFloor the leg's target as a floor point (the block stood on)
     */
    CompletableFuture<Prepared> prepare(AccessSubject subject, NavPoint feet, NavPoint targetFloor);
}
