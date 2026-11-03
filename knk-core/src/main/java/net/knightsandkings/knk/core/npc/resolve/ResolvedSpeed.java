package net.knightsandkings.knk.core.npc.resolve;

import net.knightsandkings.knk.core.npc.ClampReport;
import net.knightsandkings.knk.core.npc.ResolvedStat;

import java.util.List;
import java.util.Objects;

/**
 * One actor's movement speed, resolved and clamped to its profile's hard cap. {@link #aboveComfort()}
 * is the advisory the adapter may log: legal, but above the limit measured safe for pathfinding.
 *
 * @param profileId     the profile the value came from
 * @param factorProduct the product of the terrain / movement-profile factors applied to the base
 * @param speed         the resolved {@code MOVEMENT_SPEED} attribute value
 */
public record ResolvedSpeed(String profileId, double factorProduct, ResolvedStat speed) {

    public ResolvedSpeed {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(speed, "speed");
    }

    public double value() {
        return speed.value();
    }

    public boolean aboveComfort() {
        return speed.hasAdvisory();
    }

    public boolean wasClamped() {
        return speed.wasClamped();
    }

    public List<ClampReport> reports() {
        return speed.reports();
    }
}
