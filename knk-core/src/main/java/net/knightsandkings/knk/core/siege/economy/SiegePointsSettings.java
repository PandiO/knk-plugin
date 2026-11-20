package net.knightsandkings.knk.core.siege.economy;

/**
 * How PvP Siege pays siege points (siege shop DESIGN § 5.2, P13–P17, W34, W50, W60; API {@code SiegeConfiguration}
 * columns of PandiO/knk-workspace#866), frozen with the rest of the configuration per match. Negative values are read
 * as 0 (that source pays nothing); the streak cap is never below 1.
 *
 * @param pointsPerKill           a killing blow on an enemy member, before the streak multiplier
 * @param streakStep              the multiplier grows by this per kill of a streak after the first (P14)
 * @param streakCap               the highest multiplier
 * @param pointsPerAssist         an assist (P13)
 * @param assistMinDamage         damage (half hearts) an assister must have dealt to the victim within the window
 * @param assistWindowSeconds     how far back before the death that damage counts
 * @param pointsPerDeath          each own death by an enemy or the environment (catch-up)
 * @param gateHpPerPoint          gate HP removed per point; 0 = gate damage pays nothing
 * @param pointsPerCapturePercent per percent an objective moves towards the own side, to each own member in the radius (P16)
 * @param pointsPerCapture        to each member of the capturing side inside the radius at the capture
 * @param trickleAmount           every trickle interval, to each member active in it (P17)
 * @param trickleIntervalSeconds  the trickle interval (at least 1)
 * @param transfersEnabled        teammates may send each other points ({@code /siege pay}, P07); off by default in PvP
 */
public record SiegePointsSettings(
        int pointsPerKill,
        double streakStep,
        double streakCap,
        int pointsPerAssist,
        double assistMinDamage,
        int assistWindowSeconds,
        int pointsPerDeath,
        double gateHpPerPoint,
        int pointsPerCapturePercent,
        int pointsPerCapture,
        int trickleAmount,
        int trickleIntervalSeconds,
        boolean transfersEnabled
) {
    public static final int DEFAULT_POINTS_PER_KILL = 100;
    public static final double DEFAULT_STREAK_STEP = 0.1;
    public static final double DEFAULT_STREAK_CAP = 1.5;
    public static final int DEFAULT_POINTS_PER_ASSIST = 40;
    public static final double DEFAULT_ASSIST_MIN_DAMAGE = 4.0;
    public static final int DEFAULT_ASSIST_WINDOW_SECONDS = 10;
    public static final int DEFAULT_POINTS_PER_DEATH = 15;
    public static final double DEFAULT_GATE_HP_PER_POINT = 5.0;
    public static final int DEFAULT_POINTS_PER_CAPTURE_PERCENT = 3;
    public static final int DEFAULT_POINTS_PER_CAPTURE = 100;
    public static final int DEFAULT_TRICKLE_AMOUNT = 10;
    public static final int DEFAULT_TRICKLE_INTERVAL_SECONDS = 60;

    public SiegePointsSettings {
        pointsPerKill = Math.max(0, pointsPerKill);
        streakStep = finiteAtLeast(streakStep, 0);
        streakCap = Math.max(1.0, finiteAtLeast(streakCap, 1.0));
        pointsPerAssist = Math.max(0, pointsPerAssist);
        assistMinDamage = finiteAtLeast(assistMinDamage, 0);
        assistWindowSeconds = Math.max(0, assistWindowSeconds);
        pointsPerDeath = Math.max(0, pointsPerDeath);
        gateHpPerPoint = finiteAtLeast(gateHpPerPoint, 0);
        pointsPerCapturePercent = Math.max(0, pointsPerCapturePercent);
        pointsPerCapture = Math.max(0, pointsPerCapture);
        trickleAmount = Math.max(0, trickleAmount);
        trickleIntervalSeconds = Math.max(1, trickleIntervalSeconds);
    }

    /** The proposed defaults of DESIGN § 5.2 (developer answer § 12.1: ship them, tune in #651). */
    public static SiegePointsSettings defaults() {
        return new SiegePointsSettings(DEFAULT_POINTS_PER_KILL, DEFAULT_STREAK_STEP, DEFAULT_STREAK_CAP,
                DEFAULT_POINTS_PER_ASSIST, DEFAULT_ASSIST_MIN_DAMAGE, DEFAULT_ASSIST_WINDOW_SECONDS, DEFAULT_POINTS_PER_DEATH,
                DEFAULT_GATE_HP_PER_POINT, DEFAULT_POINTS_PER_CAPTURE_PERCENT, DEFAULT_POINTS_PER_CAPTURE,
                DEFAULT_TRICKLE_AMOUNT, DEFAULT_TRICKLE_INTERVAL_SECONDS, false);
    }

    /** These settings with the transfer switch set (tests, admin previews). */
    public SiegePointsSettings withTransfersEnabled(boolean enabled) {
        return new SiegePointsSettings(pointsPerKill, streakStep, streakCap, pointsPerAssist, assistMinDamage,
                assistWindowSeconds, pointsPerDeath, gateHpPerPoint, pointsPerCapturePercent, pointsPerCapture,
                trickleAmount, trickleIntervalSeconds, enabled);
    }

    private static double finiteAtLeast(double value, double min) {
        return Double.isFinite(value) ? Math.max(min, value) : min;
    }
}
