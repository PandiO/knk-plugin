package net.knightsandkings.knk.core.roads.route;

/**
 * Travel-time estimate (DESIGN §6.2 step 5, §6.4): {@code length / sprint-speed}, sprint speed
 * 5.6 blocks/s by default ("3 min = 1000 blocks"). The formatting helpers give the HUD's
 * "340 m · ~1 min" pieces; the paper side may format differently.
 */
public final class EtaEstimator {

    /** DESIGN §4 {@code sprint-speed}. */
    public static final double DEFAULT_SPRINT_SPEED = 5.6;

    private final double sprintSpeed;

    public EtaEstimator(double sprintSpeed) {
        if (!(sprintSpeed > 0)) {
            throw new IllegalArgumentException("sprintSpeed must be > 0");
        }
        this.sprintSpeed = sprintSpeed;
    }

    public static EtaEstimator defaults() {
        return new EtaEstimator(DEFAULT_SPRINT_SPEED);
    }

    public double sprintSpeed() {
        return sprintSpeed;
    }

    /** Seconds to walk {@code blocks} at sprint speed. */
    public double seconds(double blocks) {
        return Math.max(0, blocks) / sprintSpeed;
    }

    /** "~40 s" (rounded to 5 s) under a minute, else "~3 min" (rounded, never below 1). */
    public static String formatSeconds(double seconds) {
        if (seconds < 57.5) {
            long rounded = Math.max(5, Math.round(seconds / 5) * 5);
            return "~" + rounded + " s";
        }
        long minutes = Math.max(1, Math.round(seconds / 60));
        return "~" + minutes + " min";
    }

    /** "340 m" (blocks rounded to whole numbers; below 1 km) or "1.2 km". */
    public static String formatDistance(double blocks) {
        if (blocks < 1000) {
            return Math.round(blocks) + " m";
        }
        return String.format(java.util.Locale.ROOT, "%.1f km", blocks / 1000);
    }

    /** The HUD pieces for a remaining distance: "340 m · ~1 min". */
    public String describe(double blocks) {
        return formatDistance(blocks) + " · " + formatSeconds(seconds(blocks));
    }
}
