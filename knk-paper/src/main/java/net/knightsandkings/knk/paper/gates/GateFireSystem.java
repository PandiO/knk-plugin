package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateFireAttribution;
import net.knightsandkings.knk.core.gates.GateManager;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Owns the continuous-damage "fire" mechanic for gate door blocks: igniting a block (see
 * {@link #igniteBlock}) starts a burn that deals damage-over-time to the owning gate's
 * HealthCurrent for as long as the block stays on fire (see {@link #tick}). Deliberately models
 * fire per-block rather than per-gate, so a gate with several burning blocks takes proportionally
 * more damage, matching how a real siege (several flaming arrows) would escalate pressure on
 * a gate faster than a single hit.
 *
 * Detection lives in GateDoorHitService (GateDoorIgniteEvent qualification); this class is the
 * consequence, invoked by GateDamageConsequenceListener on ignite and by GateFireDamageTask on
 * every fire-tick interval. See HealthSystem.applyContinuousDamage for why this deliberately
 * avoids persisting to the API on every tick.
 */
public class GateFireSystem {
    private static final Logger LOGGER = Logger.getLogger(GateFireSystem.class.getName());

    private final HealthSystem healthSystem;
    private final GateManager gateManager;
    private final long fireDurationMillis;
    private final double damagePerBlockPerTick;
    // KNG-34 (DESIGN.md §F.8): who set each burning block alight, for gate-damage statistics only.
    private final GateFireAttribution attribution = new GateFireAttribution();
    private GateDamageSink damageSink = GateDamageSink.NONE;
    // The burn as the players see it: a regular fire block on the attacker's side of each burning door block.
    private GateFireVisuals visuals = new GateFireVisuals();

    /**
     * @param healthSystem Applies the accumulated damage each tick
     * @param gateManager Source of the currently-cached gates to sweep each tick
     * @param fireDurationMillis How long a single ignition keeps a block burning
     * @param damagePerBlockPerTick Health damage dealt per still-burning block, per tick
     */
    public GateFireSystem(HealthSystem healthSystem, GateManager gateManager, long fireDurationMillis, double damagePerBlockPerTick) {
        this.healthSystem = healthSystem;
        this.gateManager = gateManager;
        this.fireDurationMillis = Math.max(0L, fireDurationMillis);
        this.damagePerBlockPerTick = Math.max(0.0, damagePerBlockPerTick);
    }

    /** KNG-34: where fire-tick losses are credited; {@link GateDamageSink#NONE} (the default) does nothing. */
    public void setDamageSink(GateDamageSink damageSink) {
        this.damageSink = damageSink == null ? GateDamageSink.NONE : damageSink;
    }

    /** Replaces the fire visuals (tests). */
    void setVisuals(GateFireVisuals visuals) {
        this.visuals = visuals;
    }

    GateFireAttribution attribution() {
        return attribution;
    }

    /**
     * Set a gate's door block on fire (or refresh its burn if already alight), and play an
     * immediate ignite effect at the block. No-ops for a null gate/block.
     */
    public void igniteBlock(CachedGateDoor gate, Block block) {
        igniteBlock(gate, block, null);
    }

    /**
     * As {@link #igniteBlock(CachedGateDoor, Block)}; {@code causingEntity} (the event's) becomes the
     * block's igniter for gate-damage statistics when the damage sink tracks fire.
     */
    public void igniteBlock(CachedGateDoor gate, Block block, Entity causingEntity) {
        igniteBlock(gate, block, causingEntity, null);
    }

    /**
     * As {@link #igniteBlock(CachedGateDoor, Block, Entity)}; the fire is drawn on {@code hitFace} of the
     * block when that side is free (the side the ignition struck), otherwise on the free side that faces
     * the causing entity.
     */
    public void igniteBlock(CachedGateDoor gate, Block block, Entity causingEntity, BlockFace hitFace) {
        if (gate == null || block == null) {
            return;
        }

        Vector position = new Vector(block.getX(), block.getY(), block.getZ());
        gate.getBurningBlocks().put(position, System.currentTimeMillis() + fireDurationMillis);
        if (damageSink.tracksFire()) {
            try {
                attribution.ignite(gate.getId(), blockKey(position), damageSink.igniterOf(causingEntity));
            } catch (RuntimeException e) {
                LOGGER.warning("Gate fire statistics failed for gate '" + gate.getName() + "': " + e);
            }
        }

        visuals.show(gate, block, causingEntity, hitFace);
        World world = block.getWorld();
        if (world != null) {
            world.playSound(block.getLocation(), Sound.ITEM_FIRECHARGE_USE, SoundCategory.BLOCKS, 1.0f, 1.0f);
        }
    }

    /**
     * Put out the fire on one door block (a defender's freeze enchantment, see GateFreezeExtinguisher):
     * it stops burning, so it stops adding to the gate's continuous damage, and its fire disappears.
     *
     * @return true when the block was burning
     */
    public boolean extinguishBlock(CachedGateDoor gate, Block block) {
        if (gate == null || block == null) {
            return false;
        }
        Vector position = new Vector(block.getX(), block.getY(), block.getZ());
        if (gate.getBurningBlocks().remove(position) == null) {
            return false;
        }
        attribution.ignite(gate.getId(), blockKey(position), null);
        visuals.extinguish(gate, position);
        World world = block.getWorld();
        if (world != null) {
            world.playSound(block.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 1.0f, 1.0f);
        }
        return true;
    }

    /**
     * Sweep every cached gate: prune expired burns, apply this tick's damage for whatever is
     * still burning, and extinguish any gate that stopped qualifying (destroyed, inactive, or no
     * longer CLOSED - an animating/open gate's door blocks aren't at a stable position anymore).
     * Intended to be called once per fire-tick interval by GateFireDamageTask.
     */
    public void tick() {
        long now = System.currentTimeMillis();
        for (CachedGateDoor gate : gateManager.getAllGates().values()) {
            if (gate.getBurningBlocks().isEmpty()) {
                continue;
            }
            processGateFire(gate, now);
        }
    }

    private void processGateFire(CachedGateDoor gate, long now) {
        var burning = gate.getBurningBlocks();

        if (gate.isEffectivelyDestroyed() || !gate.isEffectivelyActive() || gate.getCurrentState() != AnimationState.CLOSED) {
            burning.clear();
            attribution.clear(gate.getId());
            visuals.clear(gate);
            return;
        }

        burning.values().removeIf(expiresAt -> expiresAt <= now);
        visuals.sync(gate);
        if (burning.isEmpty()) {
            attribution.clear(gate.getId());
            return;
        }

        // Read before the damage: destroying the gate clears its burning blocks.
        List<Long> burned = damageSink.tracksFire() ? blockKeys(burning) : null;
        double totalDamage = damagePerBlockPerTick * burning.size();
        double loss = healthSystem.applyContinuousDamage(gate, totalDamage);
        if (burned != null) {
            creditFire(gate, burned, loss);
        }

        if (gate.isDestroyed()) {
            LOGGER.fine("Gate '" + gate.getName() + "' destroyed by fire damage, extinguishing.");
            burning.clear();
            attribution.clear(gate.getId());
            visuals.clear(gate);
        }
    }

    private void creditFire(CachedGateDoor gate, List<Long> burned, double loss) {
        for (Map.Entry<GateFireAttribution.Igniter, Double> share : attribution.split(gate.getId(), burned, loss).entrySet()) {
            try {
                damageSink.fireDamage(gate, share.getKey(), share.getValue());
            } catch (RuntimeException e) {
                LOGGER.warning("Gate fire statistics failed for gate '" + gate.getName() + "': " + e);
            }
        }
    }

    private static List<Long> blockKeys(Map<Vector, Long> burning) {
        List<Long> keys = new ArrayList<>(burning.size());
        for (Vector position : burning.keySet()) {
            keys.add(blockKey(position));
        }
        return keys;
    }

    private static long blockKey(Vector position) {
        return GateFireAttribution.blockKey(position.getBlockX(), position.getBlockY(), position.getBlockZ());
    }
}
