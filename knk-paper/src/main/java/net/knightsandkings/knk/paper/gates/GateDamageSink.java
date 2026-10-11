package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateFireAttribution;
import org.bukkit.entity.Entity;

/**
 * Where the gate mechanics report effective HP loss for player statistics (KNG-34, DESIGN.md §F.8).
 * The gate code only reads the loss it already computed and hands it over; nothing here can change a
 * gate's HP. The default ({@link #NONE}) does nothing, which is exactly today's behaviour.
 */
public interface GateDamageSink {

    GateDamageSink NONE = new GateDamageSink() {
    };

    /** A direct hit (click, projectile, explosion, block break) took {@code loss} HP from {@code gate}. */
    default void directDamage(CachedGateDoor gate, Entity causingEntity, double loss) {
    }

    /** Whether burning blocks should remember their igniter (the fire system skips the bookkeeping otherwise). */
    default boolean tracksFire() {
        return false;
    }

    /** The igniter a block set alight by {@code causingEntity} is attributed to, or null (unattributed). */
    default GateFireAttribution.Igniter igniterOf(Entity causingEntity) {
        return null;
    }

    /** {@code igniter}'s share of one fire tick's effective loss of {@code gate}. */
    default void fireDamage(CachedGateDoor gate, GateFireAttribution.Igniter igniter, double loss) {
    }
}
