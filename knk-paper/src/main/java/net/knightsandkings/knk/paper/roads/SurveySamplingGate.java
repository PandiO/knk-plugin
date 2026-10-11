package net.knightsandkings.knk.paper.roads;

/**
 * When a survey walk takes a sample (DESIGN §5.3): "only while the admin is on the ground and walking -
 * not flying, riding, swimming or standing still". Pure, so the gate is unit-tested without a Player.
 */
public final class SurveySamplingGate {
    /** Slower than this (blocks per tick, horizontal) counts as standing still. */
    public static final double MIN_SPEED_PER_TICK = 0.1;

    /** The admin's state this tick, read from the Player by the session. */
    public record PlayerState(boolean onGround, boolean flying, boolean gliding, boolean insideVehicle,
                              boolean swimming, boolean inWater, double dx, double dz) {
        /** Horizontal speed in blocks per tick. */
        public double speed() {
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    public enum Verdict {
        SAMPLE, NOT_ON_GROUND, FLYING, RIDING, SWIMMING, STANDING_STILL
    }

    private SurveySamplingGate() {
    }

    public static Verdict verdict(PlayerState state) {
        if (state.flying() || state.gliding()) {
            return Verdict.FLYING;
        }
        if (state.insideVehicle()) {
            return Verdict.RIDING;
        }
        if (state.swimming() || state.inWater()) {
            return Verdict.SWIMMING;
        }
        if (!state.onGround()) {
            return Verdict.NOT_ON_GROUND;
        }
        if (state.speed() < MIN_SPEED_PER_TICK) {
            return Verdict.STANDING_STILL;
        }
        return Verdict.SAMPLE;
    }

    public static boolean shouldSample(PlayerState state) {
        return verdict(state) == Verdict.SAMPLE;
    }

    /**
     * The walking direction as a unit vector {@code {dx, dz}} from the movement since the last sample, or
     * from the yaw (degrees, Bukkit convention: 0 = +z, 90 = −x) when the admin hasn't moved enough.
     */
    public static double[] direction(double dx, double dz, float yaw) {
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len >= 0.05) {
            return new double[] {dx / len, dz / len};
        }
        double rad = Math.toRadians(yaw);
        return new double[] {-Math.sin(rad), Math.cos(rad)};
    }

    /** The lateral unit vector (to the right of the walking direction). */
    public static double[] lateral(double[] direction) {
        return new double[] {-direction[1], direction[0]};
    }
}
