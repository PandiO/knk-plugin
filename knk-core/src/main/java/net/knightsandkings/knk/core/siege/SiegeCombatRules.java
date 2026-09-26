package net.knightsandkings.knk.core.siege;

/**
 * Player-vs-player rules while sieges run (DESIGN §6.7). The Paper listener resolves who is who
 * (projectile shooter, membership, safe zones) and asks here; it then cancels or explicitly
 * un-cancels the hit, so region PvP flags don't interfere - <b>no WorldGuard PVP flag is changed</b>
 * (v2 toggled the whole town region's flag, which leaked to non-players and stuck after a crash).
 */
public final class SiegeCombatRules {
    private SiegeCombatRules() {}

    /**
     * One side of a hit.
     *
     * @param lobbyId        the lobby whose match (HUB or IN_PROGRESS) the player is away in
     * @param inProgress     that match is IN_PROGRESS (combat is only ever allowed then)
     * @param teamId         the player's siege team, or -1 before the team split
     * @param inOwnSafeZone  inside a safe-zone radius of one of their own team's spawnpoints
     */
    public record Combatant(int lobbyId, boolean inProgress, int teamId, boolean inOwnSafeZone) {}

    public enum Outcome {
        /** Neither player is in a siege: not our business, leave the event alone. */
        NOT_SIEGE,
        /** Enemies in the same running match, outside safe zones: allowed (un-cancel). */
        ALLOW,
        /** A siege member and someone outside their match (DESIGN §6.7: always denied). */
        DENY_MEMBER_VS_NON_MEMBER,
        /** Same match, but not IN_PROGRESS (hub): no fighting before the start. */
        DENY_NOT_STARTED,
        /** Same alliance group. */
        DENY_ALLY,
        /** The victim is inside their own team's spawn safe zone. */
        DENY_VICTIM_SAFE,
        /** The attacker is inside their own team's spawn safe zone. */
        DENY_ATTACKER_SAFE;

        public boolean denied() {
            return this != NOT_SIEGE && this != ALLOW;
        }
    }

    /**
     * @param attacker  null when the attacking player isn't away in a siege match
     * @param victim    null when the victim isn't away in a siege match
     * @param alliances the alliances of the match both are in (only read when they share one)
     */
    public static Outcome decide(Combatant attacker, Combatant victim, AllianceResolver alliances) {
        if (attacker == null && victim == null) return Outcome.NOT_SIEGE;
        if (attacker == null || victim == null || attacker.lobbyId() != victim.lobbyId()) {
            return Outcome.DENY_MEMBER_VS_NON_MEMBER;
        }
        if (!attacker.inProgress() || !victim.inProgress()) return Outcome.DENY_NOT_STARTED;
        if (alliances == null || !alliances.areEnemies(attacker.teamId(), victim.teamId())) return Outcome.DENY_ALLY;
        if (victim.inOwnSafeZone()) return Outcome.DENY_VICTIM_SAFE;
        if (attacker.inOwnSafeZone()) return Outcome.DENY_ATTACKER_SAFE;
        return Outcome.ALLOW;
    }

    /** Height above the victim's feet a projectile must be at to count as a headshot (v2: 1.33). */
    public static final double HEADSHOT_HEIGHT = 1.33;

    /** v2 {@code EntityListener.isHeadshot}: the projectile is above the victim's body. */
    public static boolean isHeadshot(double projectileY, double victimFeetY) {
        return projectileY > victimFeetY + HEADSHOT_HEIGHT;
    }

    /** Damage after the headshot multiplier; a multiplier of 1.0 or less (or NaN) disables it. */
    public static double headshotDamage(double damage, double multiplier) {
        if (!(multiplier > 1.0)) return damage;
        return damage * multiplier;
    }

    /** Horizontal-and-vertical distance check for a spawn safe zone (DESIGN §3.5, §6.7). */
    public static boolean withinRadius(double dx, double dy, double dz, double radius) {
        return radius > 0 && dx * dx + dy * dy + dz * dz <= radius * radius;
    }
}
