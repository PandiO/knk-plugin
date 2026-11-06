package net.knightsandkings.knk.paper.siege.npc;

import java.util.Objects;
import java.util.Optional;

import org.bukkit.block.Block;
import org.bukkit.entity.Entity;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.paper.events.GateDoorDamageEvent;
import net.knightsandkings.knk.paper.gates.GateDoorHitService;

/**
 * A match NPC's melee hit on a gate door (Siege Survival link S3, PandiO/knk-workspace#41; DESIGN §4 "an intact closed
 * door MUST be damageable by enemies"). The Survival runtime's breach goal (link S5) calls {@link #hit} on a timer
 * while the mob stands at the door; the hit then takes the same path as a player's: {@link GateDoorHitService}
 * fires a {@link GateDoorDamageEvent} with cause {@link GateDoorDamageEvent.Cause#NPC_MELEE} (only for a CLOSED,
 * active, standing door), {@code SiegeGateListener} refuses it unless the actor's team is an enemy of the gate's
 * owner, and {@code GateDamageConsequenceListener} applies the damage per door through {@code HealthSystem}.
 * <p>
 * The damage is {@link #BASE_DAMAGE} times the caller's multiplier (the difficulty's gate damage factor, default 1).
 * Only registered match actors hit; nothing changes for player damage.
 */
public final class NpcGateDamage {

    /** One NPC melee hit at factor 1: the same as a player's left click. */
    public static final double BASE_DAMAGE = 10.0;

    public enum Outcome {
        /** The entity is not a registered match actor: nothing was fired. */
        NOT_AN_ACTOR,
        /** The block is not part of a gate door right now. */
        NOT_A_DOOR,
        /** The door doesn't take hits now (open, animating, inactive or destroyed) or the multiplier left no damage. */
        REFUSED,
        /** The siege rule cancelled it: the actor's team may not damage this gate (own alliance, other match). */
        CANCELLED,
        /** The hit went through to the health system. */
        HIT
    }

    private final GateDoorHitService hits;
    private final MatchActorRegistry actors;

    public NpcGateDamage(GateDoorHitService hits, MatchActorRegistry actors) {
        this.hits = Objects.requireNonNull(hits, "hits");
        this.actors = Objects.requireNonNull(actors, "actors");
    }

    /** {@link #hit(Entity, Block, double)} at factor 1. */
    public Outcome hit(Entity mob, Block block) {
        return hit(mob, block, 1.0);
    }

    /**
     * The mob hits the door block in front of it.
     *
     * @param multiplier the difficulty's gate damage factor (1 = a player's hit); 0 or less deals nothing
     */
    public Outcome hit(Entity mob, Block block, double multiplier) {
        if (block == null) return Outcome.NOT_A_DOOR;
        CachedGateDoor door = hits.resolveDoorGate(block.getWorld().getName(), block);
        if (door == null) return Outcome.NOT_A_DOOR;
        return hit(mob, door, block, multiplier);
    }

    /** As {@link #hit(Entity, Block, double)} when the caller already resolved the door. */
    public Outcome hit(Entity mob, CachedGateDoor door, Block block, double multiplier) {
        Optional<MatchActor> actor = actors.actorOf(mob);
        if (actor.isEmpty()) return Outcome.NOT_AN_ACTOR;
        if (door == null) return Outcome.NOT_A_DOOR;
        double damage = damageFor(multiplier);
        if (!(damage > 0)) return Outcome.REFUSED;
        GateDoorDamageEvent event = hits.handleDamage(door, mob, block, GateDoorDamageEvent.Cause.NPC_MELEE, damage);
        if (event == null) return Outcome.REFUSED;
        return event.isCancelled() ? Outcome.CANCELLED : Outcome.HIT;
    }

    /** {@link #BASE_DAMAGE} × the factor; 0 for a non-positive or non-finite factor. */
    public static double damageFor(double multiplier) {
        if (Double.isNaN(multiplier) || Double.isInfinite(multiplier) || multiplier <= 0) return 0;
        return BASE_DAMAGE * multiplier;
    }
}
