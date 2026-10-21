package net.knightsandkings.knk.core.duel;

import java.util.Objects;

/**
 * Player-vs-player rule for duels (DESIGN §8.1, D8). The Paper listener ({@code DuelCombatListener},
 * HIGHEST) resolves the real attacker (projectile shooter, tamed pet's owner, TNT and end-crystal
 * source) and each player's match, asks here, then un-cancels or cancels the hit. No WorldGuard flag
 * is changed.
 *
 * <table>
 *   <caption>DESIGN §8.1</caption>
 *   <tr><td>both in the same match, FIGHT, damage not locked</td><td>{@link Outcome#ALLOW}: {@code setCancelled(false)}</td></tr>
 *   <tr><td>same match, any other phase (or damage locked)</td><td>{@link Outcome#DENY_NOT_FIGHTING}</td></tr>
 *   <tr><td>one is a match participant (LOCKING..ENDING), the other isn't in that match</td><td>{@link Outcome#DENY_OUTSIDE_MATCH}, both ways</td></tr>
 *   <tr><td>neither is in a match</td><td>{@link Outcome#NOT_DUEL}: untouched</td></tr>
 * </table>
 */
public final class DuelCombatRules {
    private DuelCombatRules() {}

    /**
     * One side of a hit, or null when that player isn't a participant of any match.
     *
     * @param matchToken    the match's public token
     * @param phase         the match's phase
     * @param damageLocked  {@link DuelStateMachine#damageLocked()}
     */
    public record Combatant(String matchToken, DuelPhase phase, boolean damageLocked) {
        public Combatant {
            Objects.requireNonNull(matchToken, "matchToken");
            Objects.requireNonNull(phase, "phase");
        }

        public static Combatant of(String matchToken, DuelStateMachine machine) {
            return new Combatant(matchToken, machine.phase(), machine.damageLocked());
        }

        boolean participating() {
            return phase.isParticipating();
        }
    }

    public enum Outcome {
        /** Not a duel matter: leave the event alone. */
        NOT_DUEL,
        /** The fighting pair: un-cancel, overriding town, district and hub denies. */
        ALLOW,
        /** Same match, but not fighting (warmup, countdown, ending, or a result is in). */
        DENY_NOT_FIGHTING,
        /** A participant and someone outside their match (no third-party interference, V7). */
        DENY_OUTSIDE_MATCH;

        public boolean denied() {
            return this == DENY_NOT_FIGHTING || this == DENY_OUTSIDE_MATCH;
        }
    }

    /**
     * @param attacker null when the attacking player isn't in a match (or the damager is no player)
     * @param victim   null when the victim isn't in a match
     */
    public static Outcome decide(Combatant attacker, Combatant victim) {
        boolean a = attacker != null && attacker.participating();
        boolean v = victim != null && victim.participating();
        if (!a && !v) return Outcome.NOT_DUEL;
        if (!a || !v || !attacker.matchToken().equals(victim.matchToken())) return Outcome.DENY_OUTSIDE_MATCH;
        if (attacker.phase() != DuelPhase.FIGHT || attacker.damageLocked() || victim.damageLocked()) {
            return Outcome.DENY_NOT_FIGHTING;
        }
        return Outcome.ALLOW;
    }

    /**
     * The {@code DisallowedPVPEvent} handler (WorldGuard): cancel WG's block only for the fighting pair
     * (potions, fire and other paths WG blocks, DESIGN §8.1).
     */
    public static boolean overrideWorldGuardPvpBlock(Combatant attacker, Combatant victim) {
        return decide(attacker, victim) == Outcome.ALLOW;
    }
}
