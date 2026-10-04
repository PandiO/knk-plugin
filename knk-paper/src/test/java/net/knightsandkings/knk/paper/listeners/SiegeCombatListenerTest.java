package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import net.knightsandkings.knk.core.siege.AllianceResolver;
import net.knightsandkings.knk.core.siege.SiegeCombatRules;
import net.knightsandkings.knk.core.siege.SiegeCombatRules.Combatant;
import net.knightsandkings.knk.core.siege.SiegeCombatRules.Outcome;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.Trident;
import org.bukkit.entity.Zombie;
import org.bukkit.projectiles.BlockProjectileSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Siege Phase 5: the real attacker behind a hit, null-safe (v2 NPE'd on shooterless projectiles).
 * KNG-28: every denied hit involving a spawn safe zone tells the damager why, whichever role hits.
 */
class SiegeCombatListenerTest {

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Object shooter) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, (p, method, args) -> {
            if (method.getName().equals("getShooter")) return shooter;
            if (method.getName().equals("equals")) return p == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(p);
            return method.getReturnType() == boolean.class ? false : null;
        });
    }

    @Test
    void aPlayerIsTheirOwnAttacker() {
        Player player = proxy(Player.class, null);
        assertSame(player, SiegeCombatListener.resolveAttacker(player));
    }

    @Test
    void aProjectileResolvesToItsShootingPlayer() {
        Player shooter = proxy(Player.class, null);
        assertSame(shooter, SiegeCombatListener.resolveAttacker(proxy(Arrow.class, shooter)));
    }

    @Test
    void shooterlessMobAndDispenserProjectilesHaveNoPlayerAttacker() {
        assertNull(SiegeCombatListener.resolveAttacker(proxy(Arrow.class, null)));
        assertNull(SiegeCombatListener.resolveAttacker(proxy(Arrow.class, proxy(Zombie.class, null))));
        assertNull(SiegeCombatListener.resolveAttacker(proxy(Arrow.class, proxy(BlockProjectileSource.class, null))));
        assertNull(SiegeCombatListener.resolveAttacker(proxy(Zombie.class, null)));
        assertNull(SiegeCombatListener.resolveAttacker(null));
    }

    @Test
    void thrownTridentsAndPotionsResolveToTheirThrower() {
        Player thrower = proxy(Player.class, null);
        assertSame(thrower, SiegeCombatListener.resolveAttacker(proxy(Trident.class, thrower)));
        assertSame(thrower, SiegeCombatListener.resolveAttacker(proxy(ThrownPotion.class, thrower)));
    }

    private static final String VICTIM_SAFE = "You can't hurt players inside their spawn area!";
    private static final String DAMAGER_SAFE = "You can't hurt players while you are inside your spawn area!";

    @Test
    void safeZoneDenialsAlwaysTellTheDamagerWhicheverRoleHits() {
        int defenders = 1;
        int attackers = 2;
        AllianceResolver alliances = new AllianceResolver(List.of(
                new KnkSiegeTeam(defenders, 0, SiegeTeamRole.DEFENDER, 1, null, "D", "WHITE", null, null, List.of()),
                new KnkSiegeTeam(attackers, 1, SiegeTeamRole.ATTACKER, 2, null, "A", "WHITE", null, null, List.of())));
        for (int damagerTeam : new int[]{defenders, attackers}) {
            int victimTeam = damagerTeam == defenders ? attackers : defenders;
            for (boolean damagerSafe : new boolean[]{false, true}) {
                for (boolean victimSafe : new boolean[]{false, true}) {
                    Outcome outcome = SiegeCombatRules.decide(new Combatant(1, true, damagerTeam, damagerSafe),
                            new Combatant(1, true, victimTeam, victimSafe), alliances);
                    String expected = victimSafe ? VICTIM_SAFE : damagerSafe ? DAMAGER_SAFE : null;
                    assertEquals(expected, SiegeCombatListener.denyMessage(outcome, true),
                            "team " + damagerTeam + " (safe=" + damagerSafe + ") hits team " + victimTeam
                                    + " (safe=" + victimSafe + ")");
                }
            }
        }
    }

    @Test
    void everyDenialButAlliesHasAMessage() {
        for (Outcome outcome : Outcome.values()) {
            if (!outcome.denied() || outcome == Outcome.DENY_ALLY) {
                assertNull(SiegeCombatListener.denyMessage(outcome, true), outcome.name());
            } else {
                assertNotNull(SiegeCombatListener.denyMessage(outcome, true), outcome.name());
                assertNotNull(SiegeCombatListener.denyMessage(outcome, false), outcome.name());
            }
        }
    }
}
