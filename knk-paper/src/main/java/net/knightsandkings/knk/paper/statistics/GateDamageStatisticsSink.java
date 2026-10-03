package net.knightsandkings.knk.paper.statistics;

import java.util.OptionalInt;
import java.util.function.IntPredicate;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateFireAttribution;
import net.knightsandkings.knk.core.statistics.StatisticsContext;
import net.knightsandkings.knk.core.statistics.StatisticsMetric;
import net.knightsandkings.knk.paper.gates.GateDamageSink;

/**
 * {@code gate_damage} statistics (KNG-34, DESIGN.md §F.8): the effective HP a gate lost, credited to
 * the player behind the hit (the attacker, a projectile's shooter or a primed TNT's source) and, for
 * fire, split per igniter. The context is the gate's: {@code siege} while a running match has it
 * locked down, else {@code open_world}. An igniter who logged off is credited with the user id stored
 * at ignition. Installed only with {@code statistics.gates.enabled}.
 */
public final class GateDamageStatisticsSink implements GateDamageSink {

    private final StatisticsService service;
    private final boolean fireAttribution;
    private volatile IntPredicate lockedByMatch = structureId -> false;

    public GateDamageStatisticsSink(StatisticsService service, boolean fireAttribution) {
        this.service = service;
        this.fireAttribution = fireAttribution;
    }

    /** Siege's gate lockdown ({@code SiegeGateController.isLocked(gateStructureId)}); set once Siege is up. */
    public void setLockedByMatch(IntPredicate lockedByMatch) {
        this.lockedByMatch = lockedByMatch == null ? structureId -> false : lockedByMatch;
    }

    StatisticsContext contextOf(CachedGateDoor gate) {
        return lockedByMatch.test(gate.getGateStructureId()) ? StatisticsContext.SIEGE : StatisticsContext.OPEN_WORLD;
    }

    @Override
    public void directDamage(CachedGateDoor gate, Entity causingEntity, double loss) {
        Player player = CombatStatisticsListener.playerBehind(causingEntity);
        if (player == null || !(loss > 0) || service.excluded(player)) {
            return;
        }
        service.addCounter(player.getUniqueId(), StatisticsMetric.GATE_DAMAGE, contextOf(gate), loss);
    }

    @Override
    public boolean tracksFire() {
        return fireAttribution;
    }

    @Override
    public GateFireAttribution.Igniter igniterOf(Entity causingEntity) {
        Player player = CombatStatisticsListener.playerBehind(causingEntity);
        if (player == null || service.excluded(player)) {
            return null;
        }
        OptionalInt userId = service.userIdOf(player.getUniqueId());
        return new GateFireAttribution.Igniter(player.getUniqueId(), userId.orElse(0));
    }

    @Override
    public void fireDamage(CachedGateDoor gate, GateFireAttribution.Igniter igniter, double loss) {
        if (igniter == null || !(loss > 0)) {
            return;
        }
        StatisticsContext context = contextOf(gate).forMetric(StatisticsMetric.GATE_DAMAGE);
        if (service.hasSession(igniter.playerId())) {
            service.addCounter(igniter.playerId(), StatisticsMetric.GATE_DAMAGE, context, loss);
        } else if (igniter.userId() > 0) {
            service.buffer().addCounter(igniter.userId(), StatisticsMetric.GATE_DAMAGE, context, loss, service.now());
        }
    }
}
