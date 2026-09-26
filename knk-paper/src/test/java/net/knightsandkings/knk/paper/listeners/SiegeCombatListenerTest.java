package net.knightsandkings.knk.paper.listeners;

import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.projectiles.BlockProjectileSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Siege Phase 5: the real attacker behind a hit, null-safe (v2 NPE'd on shooterless projectiles). */
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
}
