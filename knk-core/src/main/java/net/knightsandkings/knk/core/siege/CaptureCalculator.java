package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The capture formula (DESIGN §7.2, §7.4) with the constants from {@code SiegeConfiguration}.
 * <p>
 * Per second, with {@code a} living attackers and {@code d} living defenders inside the radius:
 * {@code attack = a ≥ 1 ? A1 + (a−1)·A2 : 0}, {@code defend = d ≥ 1 ? D1 + (d−1)·D2 : 0},
 * {@code delta = attack − defend}, {@code points = clamp(points − delta, 0, capturePoints)}.
 * A2/D2 have separate instant-victory values. This is the v1 {@code Objective.calculateCapturePoints}
 * / v2 {@code SiegeObjective.calculateCapturePoints} loop in closed form (legacy: 5/2(5), 6/3(6)).
 * <p>
 * Since Siege Survival link S3 the counts may be weight sums ({@link #delta(double, double, boolean)}): an NPC actor
 * weighs its difficulty's capture contribution, a player always 1, so PvP matches are unchanged.
 */
public final class CaptureCalculator {

    private final int attackBase;
    private final int attackPerExtra;
    private final int attackPerExtraInstantVictory;
    private final int defendBase;
    private final int defendPerExtra;
    private final int defendPerExtraInstantVictory;
    private final BigDecimal sideCaptureReduction;

    public CaptureCalculator(KnkSiegeConfiguration config) {
        this.attackBase = config.captureAttackBase();
        this.attackPerExtra = config.captureAttackPerExtra();
        this.attackPerExtraInstantVictory = config.captureAttackPerExtraInstantVictory();
        this.defendBase = config.captureDefendBase();
        this.defendPerExtra = config.captureDefendPerExtra();
        this.defendPerExtraInstantVictory = config.captureDefendPerExtraInstantVictory();
        // BigDecimal.valueOf keeps 0.4 exact, so floor(500 × 0.4 / n) never lands one below.
        this.sideCaptureReduction = BigDecimal.valueOf(config.sideCaptureReduction());
    }

    /** Points removed per second by {@code attackers} living attackers in the radius. */
    public int attackPressure(int attackers, boolean instantVictory) {
        if (attackers <= 0) return 0;
        int perExtra = instantVictory ? attackPerExtraInstantVictory : attackPerExtra;
        return attackBase + (attackers - 1) * perExtra;
    }

    /** Points restored per second by {@code defenders} living defenders in the radius. */
    public int defensePressure(int defenders, boolean instantVictory) {
        if (defenders <= 0) return 0;
        int perExtra = instantVictory ? defendPerExtraInstantVictory : defendPerExtra;
        return defendBase + (defenders - 1) * perExtra;
    }

    /** {@code attack − defend}: positive moves the objective towards capture. */
    public int delta(int attackers, int defenders, boolean instantVictory) {
        return attackPressure(attackers, instantVictory) - defensePressure(defenders, instantVictory);
    }

    // ==================== Weighted (actor-aware) presence, Siege Survival link S3 ====================

    /**
     * Points removed per second by attackers whose weights sum to {@code attackerWeight}: the same formula with the
     * weight sum in place of the head count, {@code A1 + (w − 1)·A2} for {@code w > 0}, never below 0 (a lone actor
     * lighter than one player presses less than one player). A whole-number weight gives exactly
     * {@link #attackPressure(int, boolean)}.
     */
    public double attackPressure(double attackerWeight, boolean instantVictory) {
        if (!(attackerWeight > 0)) return 0;
        int perExtra = instantVictory ? attackPerExtraInstantVictory : attackPerExtra;
        return Math.max(0, attackBase + (attackerWeight - 1) * perExtra);
    }

    /** Points restored per second by defenders whose weights sum to {@code defenderWeight}; see {@link #attackPressure(double, boolean)}. */
    public double defensePressure(double defenderWeight, boolean instantVictory) {
        if (!(defenderWeight > 0)) return 0;
        int perExtra = instantVictory ? defendPerExtraInstantVictory : defendPerExtra;
        return Math.max(0, defendBase + (defenderWeight - 1) * perExtra);
    }

    /**
     * Weighted {@code attack − defend}, truncated towards zero to the whole points a step moves (a fraction of a
     * point is dropped, never carried). With whole-number weights this is {@link #delta(int, int, boolean)} exactly.
     */
    public int delta(double attackWeight, double defendWeight, boolean instantVictory) {
        double delta = attackPressure(attackWeight, instantVictory) - defensePressure(defendWeight, instantVictory);
        return (int) delta;
    }

    /** One capture step: {@code clamp(points − delta, 0, capturePoints)}. */
    public int applyStep(int points, int capturePoints, int delta) {
        return clamp(points - delta, 0, capturePoints);
    }

    /**
     * Side-capture pressure (DESIGN §7.4): the points a non-instant-victory capture removes from
     * each uncaptured instant-victory objective, {@code floor(ivCapturePoints × reduction / nonIvCount)}.
     * Legacy computed {@code capturePointsOriginal/5*2/nonIvCount} in integer steps; for capture
     * points divisible by 5 (the 500 default) the two agree exactly.
     */
    public int sideCaptureReduction(int instantVictoryCapturePoints, int nonInstantVictoryCount) {
        if (nonInstantVictoryCount <= 0 || instantVictoryCapturePoints <= 0) return 0;
        return BigDecimal.valueOf(instantVictoryCapturePoints)
                .multiply(sideCaptureReduction)
                .divide(BigDecimal.valueOf(nonInstantVictoryCount), 0, RoundingMode.FLOOR)
                .max(BigDecimal.ZERO)
                .intValueExact();
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
