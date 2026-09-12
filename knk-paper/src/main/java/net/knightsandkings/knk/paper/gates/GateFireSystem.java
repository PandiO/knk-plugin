package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

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

    /**
     * Set a gate's door block on fire (or refresh its burn if already alight), and play an
     * immediate ignite effect at the block. No-ops for a null gate/block.
     */
    public void igniteBlock(CachedGateDoor gate, Block block) {
        if (gate == null || block == null) {
            return;
        }

        Vector position = new Vector(block.getX(), block.getY(), block.getZ());
        gate.getBurningBlocks().put(position, System.currentTimeMillis() + fireDurationMillis);

        World world = block.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.FLAME, block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5,
                8, 0.3, 0.3, 0.3, 0.01);
            world.playSound(block.getLocation(), Sound.ITEM_FIRECHARGE_USE, SoundCategory.BLOCKS, 1.0f, 1.0f);
        }
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
            return;
        }

        burning.values().removeIf(expiresAt -> expiresAt <= now);
        if (burning.isEmpty()) {
            return;
        }

        spawnBurnParticles(gate, burning.keySet());

        double totalDamage = damagePerBlockPerTick * burning.size();
        healthSystem.applyContinuousDamage(gate, totalDamage);

        if (gate.isDestroyed()) {
            LOGGER.fine("Gate '" + gate.getName() + "' destroyed by fire damage, extinguishing.");
            burning.clear();
        }
    }

    private void spawnBurnParticles(CachedGateDoor gate, Iterable<Vector> positions) {
        World world = Bukkit.getWorld(gate.getWorldName());
        if (world == null) {
            return;
        }
        for (Vector position : positions) {
            world.spawnParticle(Particle.FLAME, position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5,
                3, 0.2, 0.2, 0.2, 0.0);
        }
    }
}
