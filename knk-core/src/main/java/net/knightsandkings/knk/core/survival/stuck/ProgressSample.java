package net.knightsandkings.knk.core.survival.stuck;

import net.knightsandkings.knk.core.survival.SurvivalPoint;

import java.util.Optional;

/**
 * What the adapter reports about an enemy each check (DESIGN §3c rule 1). Progress means: damage dealt or taken
 * since the previous sample, a meaningful reduction of the distance to the current target, or navigation not
 * blocked; without any of these for N seconds the enemy is stuck.
 *
 * @param atMillis          sample time
 * @param position          the enemy's position (kept as "last position" for telemetry)
 * @param target            a description of the current target (player name, gate id…), empty when it has none
 * @param distanceToTarget  distance to the current target, empty when it has none
 * @param dealtOrTookDamage damage dealt or taken since the previous sample
 * @param navigationBlocked navigation reports it cannot reach the target
 */
public record ProgressSample(long atMillis, SurvivalPoint position, Optional<String> target,
                             Optional<Double> distanceToTarget, boolean dealtOrTookDamage, boolean navigationBlocked) {

    public ProgressSample {
        if (position == null) throw new IllegalArgumentException("position is required");
        target = target == null ? Optional.empty() : target;
        distanceToTarget = distanceToTarget == null ? Optional.empty() : distanceToTarget;
    }

    public static ProgressSample of(long atMillis, SurvivalPoint position, String target, double distanceToTarget,
                                    boolean dealtOrTookDamage, boolean navigationBlocked) {
        return new ProgressSample(atMillis, position, Optional.ofNullable(target), Optional.of(distanceToTarget),
            dealtOrTookDamage, navigationBlocked);
    }
}
