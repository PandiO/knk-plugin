package net.knightsandkings.knk.core.targeting;

import java.util.Objects;

/**
 * A possible target as the consumer sees it this tick. Hard eligibility (§5a rule 1) is decided by the consumer and
 * passed in: an ineligible candidate never enters scoring, whatever its score would be, and an actor committed to
 * it is released at once.
 *
 * @param id       the consumer's id ("player:<uuid>", "gate:<structure id>", "objective:<id>")
 * @param kind     the kind (drives preference and the action)
 * @param x        the position the actor heads for (a player's feet, a gate's stand point, an objective's capture point)
 * @param eligible false = fails a hard rule (ghost, denied, captured, destroyed, out of the match)
 * @param reason   why it is ineligible (telemetry), empty when eligible
 */
public record TargetCandidate(String id, TargetKind kind, double x, double y, double z, boolean eligible, String reason) {

    public TargetCandidate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        reason = reason == null ? "" : reason;
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("candidate position must be finite: " + id);
        }
    }

    public static TargetCandidate eligible(String id, TargetKind kind, double x, double y, double z) {
        return new TargetCandidate(id, kind, x, y, z, true, "");
    }

    public static TargetCandidate denied(String id, TargetKind kind, double x, double y, double z, String reason) {
        return new TargetCandidate(id, kind, x, y, z, false, reason);
    }

    public double distanceTo(double px, double py, double pz) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Horizontal distance (the castle is flat enough; vertical offsets between a stand point and a mob don't matter). */
    public double horizontalDistanceTo(double px, double pz) {
        return Math.hypot(x - px, z - pz);
    }
}
