package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.api.GateDoorsApi;
import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateFrameCalculator;
import net.knightsandkings.knk.core.gates.GateManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.bukkit.Material;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages gate health, destruction, and respawn mechanics.
 * Handles API persistence for state changes and schedules respawn tasks.
 */
public class HealthSystem {
    private static final Logger LOGGER = Logger.getLogger(HealthSystem.class.getName());

    private final GateDoorsApi gateDoorsApi;
    private final Plugin plugin;
    private final GateDisplayManager displayManager;
    private final GateManager gateManager;

    /**
     * Create a new health system.
     *
     * @param gateDoorsApi The API client for persisting state
     * @param plugin The plugin instance for scheduler access
     * @param displayManager Manager used to refresh the gate's info display on health/state changes
     * @param gateManager Owner of the door-block spatial index, kept in sync on destroy/respawn
     */
    public HealthSystem(GateDoorsApi gateDoorsApi, Plugin plugin, GateDisplayManager displayManager, GateManager gateManager) {
        this.gateDoorsApi = gateDoorsApi;
        this.plugin = plugin;
        this.displayManager = displayManager;
        this.gateManager = gateManager;
    }

    /**
     * Apply damage to a gate from an explosion.
     * If gate health reaches 0, destroys the gate.
     * 
     * @param gate The gate to damage
     * @param damageAmount The amount of damage to apply
     */
    public void applyDamage(CachedGateDoor gate, double damageAmount) {
        if (gate == null || damageAmount <= 0) {
            return;
        }

        if (gate.isEffectivelyDestroyed()) {
            LOGGER.fine("Gate '" + gate.getName() + "' is already destroyed, ignoring damage.");
            return;
        }

        // Skip if invincible (decision 5.0-B: a structure-level override applies immediately)
        if (gate.isEffectivelyInvincible()) {
            LOGGER.info("Gate '" + gate.getName() + "' is invincible, ignoring damage");
            return;
        }

        // Apply damage
        double newHealth = Math.max(0, gate.getHealthCurrent() - damageAmount);
        gate.setHealthCurrent(newHealth);

        LOGGER.info("Gate '" + gate.getName() + "' took " + damageAmount + " damage. Health: " +
                   String.format("%.1f", newHealth) + "/" + gate.getHealthMax());

        // Check if gate is destroyed
        if (newHealth <= 0) {
            destroyGate(gate);
        } else {
            if (displayManager != null) {
                displayManager.syncDisplay(gate);
            }
            // Persist health change to API asynchronously
            persistHealthChange(gate);
        }
    }

    /**
     * Apply continuous (damage-over-time) damage to a gate, e.g. from a burning door block.
     * Unlike {@link #applyDamage}, deliberately does NOT persist the health change or refresh
     * the display on every call: continuous damage is applied on a tight tick interval (see
     * GateFireSystem/GateFireDamageTask) by potentially many burning gates during a siege, and
     * writing to the API that often would be wasteful. The in-memory value stays authoritative
     * for gameplay (destroy check, health display text) immediately; GateStateSyncTask's periodic
     * sweep - or this call destroying the gate - is what eventually flushes it to the DB. This is
     * the balance point between data retention and server performance for this damage source.
     *
     * @param gate The gate to damage
     * @param damageAmount The amount of damage to apply
     */
    public void applyContinuousDamage(CachedGateDoor gate, double damageAmount) {
        if (gate == null || damageAmount <= 0) {
            return;
        }

        if (gate.isEffectivelyDestroyed() || gate.isEffectivelyInvincible()) {
            return;
        }

        double newHealth = Math.max(0, gate.getHealthCurrent() - damageAmount);
        gate.setHealthCurrent(newHealth);

        if (newHealth <= 0) {
            destroyGate(gate);
        }
    }

    /**
     * Destroy a gate, disabling it and optionally scheduling respawn.
     * 
     * @param gate The gate to destroy
     */
    public void destroyGate(CachedGateDoor gate) {
        if (gate == null || gate.isDestroyed()) {
            return;
        }

        LOGGER.info("Destroying gate: '" + gate.getName() + "'");

        // Capture the frame the door blocks actually occupy right now, before resetting state
        // below - a gate destroyed while OPEN or mid-animation must have ITS blocks removed,
        // not whatever sits at frame 0.
        int frameAtDeath = gate.getCurrentFrame();

        // Update state
        gate.setIsDestroyed(true);
        gate.setIsActive(false);
        gate.setIsJammed(false);
        gate.setCurrentState(AnimationState.CLOSED);
        gate.setCurrentFrame(0);
        gate.setAnimationStartTime(0);
        gate.setHealthCurrent(0);

        // Cosmetic destruction effect (particle + sound only - no real explosion, so it can't
        // damage terrain or knock back nearby players).
        playDestructionEffect(gate);

        // Remove all gate blocks from the world
        removeGateBlocks(gate, frameAtDeath);

        // Ensure a destroyed gate does not remain in an animating or open state.
        gate.setCurrentState(AnimationState.CLOSED);
        gate.setCurrentFrame(0);

        if (displayManager != null) {
            displayManager.syncDisplay(gate);
        }

        // Persist destruction to API
        persistGateState(gate);
        persistHealthChange(gate);

        // Schedule respawn if enabled (decision 5.0-B: a structure-level CanRespawnOverride can
        // suppress auto-respawn for every door at once, e.g. while a Siege is in progress)
        if (gate.isEffectivelyCanRespawn()) {
            scheduleRespawn(gate);
        }
    }

    /**
     * Play a cosmetic-only explosion effect (particle + sound) at the gate's anchor point.
     * Deliberately does not call World.createExplosion - no terrain damage or entity knockback.
     */
    private void playDestructionEffect(CachedGateDoor gate) {
        Vector anchor = gate.getAnchorPoint();
        if (anchor == null) {
            return;
        }

        World world = Bukkit.getWorld(gate.getWorldName());
        if (world == null) {
            return;
        }

        Location location = new Location(world, anchor.getX(), anchor.getY(), anchor.getZ());
        world.spawnParticle(Particle.EXPLOSION, location, 1);
        world.playSound(location, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 1.0f, 1.0f);
    }

    /**
     * Remove all block data associated with a gate from the world, at the given animation
     * frame (the frame the door blocks actually occupied at the moment of removal - callers
     * must capture this before resetting the gate's currentFrame).
     *
     * @param gate The gate whose blocks to remove
     * @param frame The animation frame the blocks currently occupy
     */
    private void removeGateBlocks(CachedGateDoor gate, int frame) {
        try {
            World world = Bukkit.getWorld(gate.getWorldName());
            if (world == null) {
                LOGGER.warning("Could not find world '" + gate.getWorldName() + "' to remove gate blocks for '" + gate.getName() + "'");
                return;
            }

            List<Vector> positions = new ArrayList<>();
            int blocksRemoved = 0;
            for (BlockSnapshot blockSnapshot : gate.getBlocks()) {
                Vector worldPos = GateFrameCalculator.calculateBlockPosition(
                    gate,
                    blockSnapshot,
                    frame
                );

                if (worldPos == null) {
                    continue;
                }

                positions.add(worldPos);

                Block block = world.getBlockAt(
                    worldPos.getBlockX(),
                    worldPos.getBlockY(),
                    worldPos.getBlockZ()
                );

                // These are specifically the gate's own door blocks - remove them
                // unconditionally rather than only when occluding, so gates built from
                // glass, iron bars, slabs, etc. are actually cleared on destruction.
                if (block.getType() != Material.AIR) {
                    // Bukkit's BlockBreakEvent is only a notification the server fires as part of
                    // a real player's dig sequence - calling it manually here wouldn't itself
                    // remove the block or show anything, since there's no player initiating a
                    // break. Particle.BLOCK is the actual mechanism for the vanilla block-break
                    // particle burst; capture the texture before clearing the block to air.
                    world.spawnParticle(
                        Particle.BLOCK,
                        worldPos.getBlockX() + 0.5, worldPos.getBlockY() + 0.5, worldPos.getBlockZ() + 0.5,
                        20, 0.3, 0.3, 0.3, 0,
                        block.getBlockData()
                    );
                    block.setType(Material.AIR, false);
                    blocksRemoved++;
                }
            }

            if (gateManager != null) {
                gateManager.getSpatialIndex().removeAll(gate.getWorldName(), positions);
            }

            LOGGER.info("Removed " + blocksRemoved + " blocks from destroyed gate: '" + gate.getName() + "'");
        } catch (Exception e) {
            LOGGER.warning("Error removing gate blocks: " + e.getMessage());
        }
    }

    /**
     * Schedule a respawn task for a destroyed gate.
     * 
     * @param gate The gate to respawn
     */
    private void scheduleRespawn(CachedGateDoor gate) {
        if (gate == null) {
            return;
        }

        int respawnSeconds = Math.max(0, gate.getRespawnRateSeconds());
        if (respawnSeconds <= 0) {
            LOGGER.warning("Respawn rate is 0 for gate '" + gate.getName() + "', skipping respawn scheduling");
            return;
        }

        long delayTicks = (long) respawnSeconds * 20L; // Convert seconds to ticks

        LOGGER.info("Scheduling respawn for gate '" + gate.getName() + "' in " +
                   respawnSeconds + " seconds");

        gate.setRespawnScheduledTime(System.currentTimeMillis() + (respawnSeconds * 1000L));

        // Schedule the respawn task
        new BukkitRunnable() {
            @Override
            public void run() {
                respawnGate(gate);
            }
        }.runTaskLater(plugin, delayTicks);
    }

    /**
     * Respawn a destroyed gate, restoring it to full health and active state.
     * 
     * @param gate The gate to respawn
     */
    public void respawnGate(CachedGateDoor gate) {
        if (gate == null || !gate.isDestroyed()) {
            return;
        }

        LOGGER.info("Respawning gate: '" + gate.getName() + "'");

        // Update state
        gate.setIsDestroyed(false);
        gate.setIsActive(true);
        gate.setIsJammed(false);
        gate.setCurrentState(AnimationState.CLOSED);
        gate.setCurrentFrame(0);
        gate.setAnimationStartTime(0);
        gate.setHealthCurrent(gate.getHealthMax());
        gate.setRespawnScheduledTime(0);

        restoreGateBlocks(gate);

        if (displayManager != null) {
            displayManager.syncDisplay(gate);
        }

        // Persist respawn to API
        persistGateState(gate);
        persistHealthChange(gate);

        // Notify players
        Bukkit.getServer().broadcast(
            Component.text("[KnK] Gate '" + gate.getName() + "' has been restored!")
                .color(NamedTextColor.GREEN)
        );
    }

    /**
     * Restore gate blocks to the closed (frame 0) position.
     */
    private void restoreGateBlocks(CachedGateDoor gate) {
        try {
            World world = Bukkit.getWorld(gate.getWorldName());
            if (world == null) {
                LOGGER.warning("Could not find world '" + gate.getWorldName() + "' to restore gate blocks for '" + gate.getName() + "'");
                return;
            }

            List<Vector> positions = new ArrayList<>();
            for (BlockSnapshot blockSnapshot : gate.getBlocks()) {
                Vector worldPos = GateFrameCalculator.calculateBlockPosition(gate, blockSnapshot, 0);
                if (worldPos == null) {
                    continue;
                }

                positions.add(worldPos);
                GateBlockPlacer.placeBlock(world, worldPos, blockSnapshot.getBlockData(), Material.STONE);
            }

            if (gateManager != null) {
                gateManager.getSpatialIndex().putAll(gate.getWorldName(), positions, gate.getId());
            }
        } catch (Exception e) {
            LOGGER.warning("Error restoring gate blocks: " + e.getMessage());
        }
    }

    /**
     * Persist gate health change to the API asynchronously.
     * 
     * @param gate The gate to persist
     */
    private void persistHealthChange(CachedGateDoor gate) {
        if (gateDoorsApi == null) {
            LOGGER.warning("Gate API not available, cannot persist health change");
            return;
        }

        // Run API call asynchronously to avoid blocking the server thread
        double healthCurrent = gate.getHealthCurrent();
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    gateDoorsApi.updateHealth(gate.getId(), healthCurrent).join();
                    LOGGER.fine("Health change persisted to API for gate: " + gate.getName() + " (health=" + healthCurrent + ")");
                } catch (Exception e) {
                    LOGGER.warning("Failed to persist health change: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Persist full gate state to the API asynchronously.
     * 
     * @param gate The gate to persist
     */
    private void persistGateState(CachedGateDoor gate) {
        if (gateDoorsApi == null) {
            LOGGER.warning("Gate API not available, cannot persist gate state");
            return;
        }

        // Run API call asynchronously to avoid blocking the server thread
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    AnimationState persistedState = gate.getCurrentState() == AnimationState.OPEN && !gate.isDestroyed()
                        ? AnimationState.OPEN : AnimationState.CLOSED;
                    String openedState = GateDoorOpenStateMapper.toWireValue(persistedState, gate.isJammed());
                    gateDoorsApi.updateState(gate.getId(), openedState, gate.isDestroyed()).join();
                    LOGGER.fine("Gate state persisted to API: " + gate.getName() +
                               " (destroyed=" + gate.isDestroyed() + ", openedState=" + openedState + ")");
                } catch (Exception e) {
                    LOGGER.warning("Failed to persist gate state: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(plugin);
    }
}
