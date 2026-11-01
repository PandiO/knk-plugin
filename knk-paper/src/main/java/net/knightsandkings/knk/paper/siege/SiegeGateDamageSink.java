package net.knightsandkings.knk.paper.siege;

import java.util.UUID;
import java.util.function.Supplier;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateFireAttribution;
import net.knightsandkings.knk.paper.gates.GateDamageSink;

/**
 * Siege gate damage reward (closed-alpha test run 1, PandiO/knk-workspace#165): the effective HP a gate lost is
 * credited to the match member behind it - the attacker, a projectile's shooter or a primed TNT's source - and,
 * for fire, to each igniter's share (the gate fire system splits a tick's loss per burning block). The credit goes
 * to the member's running match only when its lockdown holds the gate ({@link SiegeService#recordGateDamage}).
 * Always tracks fire, independent of {@code statistics.gates.fire-attribution}. Reads the loss only.
 */
public final class SiegeGateDamageSink implements GateDamageSink {

    private final Supplier<SiegeService> siege;

    /** @param siege the siege service; created after the gates, so read per hit (null: nothing is credited) */
    public SiegeGateDamageSink(Supplier<SiegeService> siege) {
        this.siege = siege;
    }

    @Override
    public void directDamage(CachedGateDoor gate, Entity causingEntity, double loss) {
        Player player = playerBehind(causingEntity);
        if (player != null) credit(player.getUniqueId(), gate, loss);
    }

    @Override
    public boolean tracksFire() {
        return true;
    }

    @Override
    public GateFireAttribution.Igniter igniterOf(Entity causingEntity) {
        Player player = playerBehind(causingEntity);
        return player == null ? null : new GateFireAttribution.Igniter(player.getUniqueId(), 0);
    }

    @Override
    public void fireDamage(CachedGateDoor gate, GateFireAttribution.Igniter igniter, double loss) {
        if (igniter != null) credit(igniter.playerId(), gate, loss);
    }

    private void credit(UUID playerId, CachedGateDoor gate, double loss) {
        SiegeService service = siege.get();
        if (service == null || gate == null || !(loss > 0)) return;
        service.recordGateDamage(playerId, gate.getGateStructureId(), loss);
    }

    /** The player behind a damager: itself, a projectile's shooter or a primed TNT's source; null otherwise. */
    static Player playerBehind(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) return shooter;
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player source) return source;
        return null;
    }
}
