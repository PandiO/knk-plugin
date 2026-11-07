package net.knightsandkings.knk.core.survival.economy;

import java.util.Objects;
import java.util.UUID;

/**
 * The last player hit on an enemy as the reward logic needs it (DESIGN §8: the killing blow decides; ×1.1 for a headshot
 * or a critical hit, S33). Recorded by the damage listener, consumed by the death handler.
 *
 * @param attacker the player who dealt the hit
 * @param critical the hit was a Minecraft critical hit (Paper's {@code EntityDamageByEntityEvent#isCritical})
 * @param headshot a projectile hit in the head band ({@link #isHeadshot})
 */
public record KillBlow(UUID attacker, boolean critical, boolean headshot) {

    /**
     * The head band of a target: a projectile whose position is within this many blocks below the eye height (or above
     * it) hit the head. A zombie's eyes are at 1.74 of its 1.95 blocks, so the band covers the whole head.
     */
    public static final double HEAD_BAND_BELOW_EYES = 0.3;

    public KillBlow {
        Objects.requireNonNull(attacker, "attacker");
    }

    /**
     * Whether a projectile at {@code projectileY} hit the head of a target standing with its feet at {@code feetY} and
     * its eyes {@code eyeHeight} above them: the hit is at or above {@code feetY + eyeHeight − HEAD_BAND_BELOW_EYES}.
     */
    public static boolean isHeadshot(double projectileY, double feetY, double eyeHeight) {
        if (!(eyeHeight > 0) || Double.isNaN(projectileY) || Double.isNaN(feetY)) return false;
        return projectileY >= feetY + eyeHeight - HEAD_BAND_BELOW_EYES;
    }
}
