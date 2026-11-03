package net.knightsandkings.knk.core.survival.scaling;

import net.knightsandkings.knk.core.survival.ReadinessIssue;

import java.util.Optional;

/**
 * Minecraft's practical movement-speed limits for the readiness stat-cap check (implementation checks report §10):
 * keep the effective {@code MOVEMENT_SPEED} at or below 0.4 (≈ 5 blocks/s), treat 0.5 as the hard cap. Vanilla
 * bases: zombie 0.23, skeleton/creeper 0.25, spider 0.3. The NPC platform clamps at runtime; this only warns the
 * admin ahead of time.
 */
public final class WalkingSpeedLimit {
    public static final double HARD_CAP_EFFECTIVE_SPEED = 0.5;
    public static final double RECOMMENDED_EFFECTIVE_SPEED = 0.4;

    private WalkingSpeedLimit() { }

    /** The walking-speed multiplier at which a mob with {@code baseSpeed} hits the hard cap. */
    public static double multiplierAtHardCap(double baseSpeed) {
        if (!(baseSpeed > 0)) throw new IllegalArgumentException("baseSpeed must be > 0");
        return HARD_CAP_EFFECTIVE_SPEED / baseSpeed;
    }

    /**
     * Checks the walking-speed multiplier a profile can reach (its stat cap, or the preview of its last previewed
     * round) against the limits for the fastest selected mob.
     *
     * @param baseSpeed  the fastest selected mob type's vanilla speed
     * @param multiplier the walking-speed multiplier to check
     * @return an error above the hard cap, a warning above the recommended speed, else empty
     */
    public static Optional<ReadinessIssue> check(double baseSpeed, double multiplier) {
        double effective = baseSpeed * multiplier;
        if (effective > HARD_CAP_EFFECTIVE_SPEED) {
            return Optional.of(ReadinessIssue.error("scaling.walking-speed-hard-cap",
                String.format("Walking speed %.3f × %.2f = %.3f exceeds the hard cap %.2f (pathfinding breaks)",
                    baseSpeed, multiplier, effective, HARD_CAP_EFFECTIVE_SPEED)));
        }
        if (effective > RECOMMENDED_EFFECTIVE_SPEED) {
            return Optional.of(ReadinessIssue.warning("scaling.walking-speed-fast",
                String.format("Walking speed %.3f × %.2f = %.3f is above the recommended %.2f",
                    baseSpeed, multiplier, effective, RECOMMENDED_EFFECTIVE_SPEED)));
        }
        return Optional.empty();
    }
}
