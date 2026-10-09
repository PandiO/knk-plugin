package net.knightsandkings.knk.core.settings;

import java.util.List;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.settings.KnkRespawnPolicy;

/**
 * Where a player respawns after dying (docs/specs/game-settings/DESIGN.md §3.3), Bukkit-free. Runs
 * inside {@code PlayerRespawnEvent}, so it only reads what was resolved beforehand: the policy's
 * reference location and the towns with their spawn points.
 */
public final class RespawnPlanner {

    private static final double MAX_COORDINATE = 30_000_000;

    /** A town's spawn point, for {@link KnkRespawnPolicy.Mode#NEAREST_TOWN}. */
    public record TownSpot(int id, String name, String wgRegionId, KnkLocation location) {
    }

    /** Whether a point lies inside a town's own region. */
    @FunctionalInterface
    public interface RegionCheck {
        boolean contains(TownSpot town, KnkLocation point);
    }

    public enum Kind {
        /** Don't touch the respawn: bed or respawn anchor, else the world's spawn. */
        SERVER_DEFAULT,
        /** Respawn at {@link Plan#location()}. */
        LOCATION,
        /** Respawn at the spawn point of the world the player died in (the main world's for a nether/End death). */
        WORLD_SPAWN,
        /** Respawn where the player would join ({@link KnkRespawnPolicy.Mode#JOIN_SPAWN}); the caller resolves it. */
        JOIN_SPAWN
    }

    /**
     * @param location only for {@link Kind#LOCATION}
     * @param reason   for log lines
     */
    public record Plan(Kind kind, KnkLocation location, String reason) {
        static Plan serverDefault(String reason) {
            return new Plan(Kind.SERVER_DEFAULT, null, reason);
        }
    }

    private RespawnPlanner() {
    }

    /**
     * @param policy     the death world's policy
     * @param death      where the player died (world + coordinates)
     * @param configured the policy's reference, resolved; null when unknown or unset
     * @param towns      every town with a spawn point, any world
     * @param inside     region containment; may be null (no "died inside a town" preference)
     */
    public static Plan plan(KnkRespawnPolicy policy, KnkLocation death, KnkLocation configured, List<TownSpot> towns,
                            RegionCheck inside) {
        if (policy == null) {
            return Plan.serverDefault("no policy");
        }
        if (policy.mode() == KnkRespawnPolicy.Mode.SERVER_DEFAULT) {
            return Plan.serverDefault("server-default policy");
        }
        if (policy.mode() == KnkRespawnPolicy.Mode.WORLD_SPAWN) {
            // Forced: beds and respawn anchors are ignored (developer decision D1, 2026-10-09; a
            // player's own house/room spawn replaces the bed later).
            return new Plan(Kind.WORLD_SPAWN, null, "world-spawn policy");
        }
        if (policy.mode() == KnkRespawnPolicy.Mode.JOIN_SPAWN) {
            return new Plan(Kind.JOIN_SPAWN, null, "same as the join spawn");
        }
        if (policy.mode() == KnkRespawnPolicy.Mode.CONFIGURED_REFERENCE) {
            if (isUsable(configured)) {
                String label = policy.reference() != null ? policy.reference().label() : "configured spot";
                return new Plan(Kind.LOCATION, configured, label);
            }
            return fallback(policy, "the configured respawn spot has no usable location");
        }
        TownSpot town = nearestTown(policy, death, towns, inside);
        if (town != null) {
            return new Plan(Kind.LOCATION, town.location(), "nearest town " + town.name());
        }
        return fallback(policy, "no town with a spawn point in range");
    }

    private static Plan fallback(KnkRespawnPolicy policy, String why) {
        return policy.useWorldSpawnFallback()
            ? new Plan(Kind.WORLD_SPAWN, null, why + "; world spawn")
            : Plan.serverDefault(why + "; server default");
    }

    /**
     * A town in the death world: one whose region the player died in (nearest of those), else the
     * nearest within {@code maxNearestTownDistance}. Distance is horizontal - town spawns sit at
     * very different heights.
     */
    static TownSpot nearestTown(KnkRespawnPolicy policy, KnkLocation death, List<TownSpot> towns, RegionCheck inside) {
        if (!isUsable(death) || towns == null) {
            return null;
        }
        Double max = policy.maxNearestTownDistance();
        boolean limited = max != null && max > 0;
        TownSpot bestInside = null;
        double bestInsideDistance = Double.MAX_VALUE;
        TownSpot best = null;
        double bestDistance = Double.MAX_VALUE;
        for (TownSpot town : towns) {
            if (town == null || !isUsable(town.location()) || !town.location().world().equalsIgnoreCase(death.world())) {
                continue;
            }
            double distance = Math.hypot(town.location().x() - death.x(), town.location().z() - death.z());
            if (inside != null && inside.contains(town, death)) {
                if (distance < bestInsideDistance) {
                    bestInside = town;
                    bestInsideDistance = distance;
                }
                continue;
            }
            if (limited && distance > max) {
                continue;
            }
            if (distance < bestDistance) {
                best = town;
                bestDistance = distance;
            }
        }
        return bestInside != null ? bestInside : best;
    }

    /** A world name and finite coordinates within the world border limit. */
    public static boolean isUsable(KnkLocation location) {
        return location != null
            && location.world() != null && !location.world().isBlank()
            && inRange(location.x()) && inRange(location.y()) && inRange(location.z());
    }

    private static boolean inRange(Double value) {
        return value != null && Double.isFinite(value) && Math.abs(value) <= MAX_COORDINATE;
    }
}
