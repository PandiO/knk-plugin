package net.knightsandkings.knk.core.survival.economy;

import net.knightsandkings.knk.core.survival.wave.KillCause;

import java.util.Optional;
import java.util.UUID;

/**
 * An enemy death as the reward logic sees it (§8, §9): the killing blow decides, no assist share.
 *
 * @param enemyId       the planned enemy
 * @param cause         who or what killed it
 * @param killer        the player with the killing blow (only for {@link KillCause#PLAYER})
 * @param killerIsGhost whether that player is a ghost (ghosts earn nothing, S20)
 * @param headshot      a projectile kill that hit the head
 * @param critical      a killing blow Minecraft marks as critical
 */
public record EnemyKill(long enemyId, KillCause cause, Optional<UUID> killer, boolean killerIsGhost, boolean headshot, boolean critical) {

    public EnemyKill {
        if (cause == null) throw new IllegalArgumentException("cause is required");
        killer = killer == null ? Optional.empty() : killer;
        if (cause == KillCause.PLAYER && killer.isEmpty()) throw new IllegalArgumentException("a player kill needs a killer");
    }

    public static EnemyKill byPlayer(long enemyId, UUID killer, boolean headshot, boolean critical) {
        return new EnemyKill(enemyId, KillCause.PLAYER, Optional.of(killer), false, headshot, critical);
    }

    public static EnemyKill bySystem(long enemyId) {
        return new EnemyKill(enemyId, KillCause.SYSTEM, Optional.empty(), false, false, false);
    }

    public static EnemyKill byEnvironment(long enemyId) {
        return new EnemyKill(enemyId, KillCause.ENVIRONMENT, Optional.empty(), false, false, false);
    }

    public static EnemyKill byNpc(long enemyId) {
        return new EnemyKill(enemyId, KillCause.NPC, Optional.empty(), false, false, false);
    }

    /** Headshot or critical: the bonus multiplier applies once. */
    public boolean bonus() {
        return headshot || critical;
    }
}
