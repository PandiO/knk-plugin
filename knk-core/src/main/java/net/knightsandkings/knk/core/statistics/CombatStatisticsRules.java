package net.knightsandkings.knk.core.statistics;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure combat-statistics rules (DESIGN.md §F.7, L1-9, L1-10): the damage cap, which damage causes
 * count, the PvE spawn-reason filter and which projectiles are arrows. Bukkit enums are passed by
 * name so the rules stay testable without a server.
 */
public final class CombatStatisticsRules {

    /** {@code statistics.combat.pve-excluded-spawn-reasons} default: farmed creatures (L1-10). */
    public static final List<String> DEFAULT_PVE_EXCLUDED_SPAWN_REASONS =
            List.of("SPAWNER", "SPAWNER_EGG", "BREEDING", "EGG", "DISPENSE_EGG");

    /** The synthetic damage cause of plugin-dealt damage (Chaos enchant procs, L1-9). */
    public static final String CUSTOM_CAUSE = "CUSTOM";

    private CombatStatisticsRules() {
    }

    /**
     * The damage a hit counts for: its final damage capped at what the victim had left
     * (health + absorption before the hit), never negative. NaN or infinite input counts 0.
     */
    public static double cappedDamage(double finalDamage, double healthBefore, double absorptionBefore) {
        if (!(finalDamage > 0) || Double.isInfinite(finalDamage)) {
            return 0;
        }
        double remaining = Math.max(0, finite(healthBefore)) + Math.max(0, finite(absorptionBefore));
        return Math.min(finalDamage, remaining);
    }

    /** Whether a damage event of {@code causeName} counts: everything but {@code CUSTOM}, unless configured. */
    public static boolean countsDamageCause(String causeName, boolean countCustomDamage) {
        return countCustomDamage || causeName == null || !CUSTOM_CAUSE.equalsIgnoreCase(causeName);
    }

    /** Whether a creature with this spawn reason counts as a PvE kill (null reason counts). */
    public static boolean countsPveKill(String spawnReason, Set<String> excludedSpawnReasons) {
        if (spawnReason == null || excludedSpawnReasons == null || excludedSpawnReasons.isEmpty()) {
            return true;
        }
        return !excludedSpawnReasons.contains(spawnReason.toUpperCase(Locale.ROOT));
    }

    /** Arrows fired: arrow projectiles (incl. spectral and tipped), never tridents or fireworks. */
    public static boolean isArrow(boolean isArrowProjectile, boolean isTrident) {
        return isArrowProjectile && !isTrident;
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0;
    }
}
