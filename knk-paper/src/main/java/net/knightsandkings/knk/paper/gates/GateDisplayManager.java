package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Renders each gate's configurable name/health/status info as a floating {@link TextDisplay}
 * above the gate. A TextDisplay (not a LivingEntity, unlike an ArmorStand) has no AI/physics
 * baggage, has no hitbox so it can't be pushed or punched, and natively supports multi-line
 * text - which is needed here since each of the three info topics can be toggled independently.
 */
public class GateDisplayManager {
    private static final Logger LOGGER = Logger.getLogger(GateDisplayManager.class.getName());

    private final NamespacedKey gateIdKey;
    private final Map<Integer, TextDisplay> displays = new HashMap<>();

    public GateDisplayManager(Plugin plugin) {
        this.gateIdKey = new NamespacedKey(plugin, "gate_display_id");
    }

    /**
     * Recompute and (re)spawn the display for every cached gate, in whichever of its world(s)
     * is currently loaded.
     */
    public void syncAll(GateManager gateManager) {
        for (CachedGateDoor gate : gateManager.getAllGates().values()) {
            syncDisplay(gate);
        }
    }

    /**
     * Recompute the display for a single gate: spawns/moves/updates its TextDisplay, or removes
     * it if nothing is currently visible.
     */
    public void syncDisplay(CachedGateDoor gate) {
        if (gate == null) {
            return;
        }

        World world = resolveWorld(gate);
        // destroyGate() sets isActive=false as part of disabling animation/interaction, but a
        // destroyed gate should still show its info hover (DESTROYED status, buildStatusLine
        // below already handles this) rather than vanish - only a merely-deactivated,
        // not-destroyed gate hides its display entirely.
        if (world == null || (!gate.isEffectivelyActive() && !gate.isEffectivelyDestroyed())) {
            removeDisplay(gate.getId());
            return;
        }

        Component text = buildDisplayText(gate);
        if (text == null) {
            removeDisplay(gate.getId());
            return;
        }

        Location location = calculateDisplayLocation(gate, world);
        if (location == null) {
            removeDisplay(gate.getId());
            return;
        }

        TextDisplay display = displays.get(gate.getId());
        if (display != null && display.isValid() && world.equals(display.getWorld())) {
            removeDuplicateEntities(world, gate.getId(), display);
        }

        // isValid() (not isDead()) is the reliable check here: a display whose chunk unloaded and
        // later reloaded keeps a Java reference that is never "dead" but no longer represents the
        // entity actually being rendered, so re-teleporting it silently does nothing and leaves the
        // real, visible entity frozen with stale text - the duplicate/stuck displays from this bug.
        if (display == null || !display.isValid() || !world.equals(display.getWorld())) {
            if (display != null) {
                display.remove();
            }
            final int gateId = gate.getId();
            display = adoptExistingDisplay(world, gateId);
            if (display == null) {
                display = world.spawn(location, TextDisplay.class, entity -> configureDisplay(entity, gateId));
            }
            displays.put(gate.getId(), display);
        } else {
            display.teleport(location);
        }

        display.text(text);
    }

    /**
     * Remove any leftover TextDisplay entities tagged for this gate that our tracking map doesn't
     * know about (e.g. a stale entity orphaned by a chunk unload/reload cycle - see {@link #syncDisplay}).
     * Called right before spawning a replacement so a respawn never leaves an old copy behind.
     */
    private TextDisplay adoptExistingDisplay(World world, int gateId) {
        TextDisplay adopted = null;
        for (TextDisplay entity : world.getEntitiesByClass(TextDisplay.class)) {
            Integer taggedId = entity.getPersistentDataContainer().get(gateIdKey, PersistentDataType.INTEGER);
            if (taggedId == null || taggedId != gateId) {
                continue;
            }

            if (adopted == null && entity.isValid()) {
                adopted = entity;
            } else {
                entity.remove();
            }
        }
        return adopted;
    }

    private void removeDuplicateEntities(World world, int gateId, TextDisplay keep) {
        for (TextDisplay entity : world.getEntitiesByClass(TextDisplay.class)) {
            Integer taggedId = entity.getPersistentDataContainer().get(gateIdKey, PersistentDataType.INTEGER);
            if (taggedId != null && taggedId == gateId && !entity.equals(keep)) {
                entity.remove();
            }
        }
    }

    private void configureDisplay(TextDisplay entity, int gateId) {
        entity.setBillboard(Display.Billboard.CENTER);
        entity.setGravity(false);
        entity.setPersistent(true);
        entity.setInvulnerable(true);
        entity.getPersistentDataContainer().set(gateIdKey, PersistentDataType.INTEGER, gateId);
    }

    /**
     * Remove the display entity for a single gate, if one exists.
     */
    public void removeDisplay(int gateId) {
        TextDisplay display = displays.remove(gateId);
        if (display != null && !display.isDead()) {
            display.remove();
        }
    }

    /**
     * Runtime repair pass: re-run {@link #cleanupOrphans} against every currently loaded world and
     * refresh the survivors. Unlike the startup-only cleanup, this is safe to call repeatedly while
     * the server is running, so a duplicate/orphaned display created after startup (e.g. by a chunk
     * unload/reload) gets removed without needing a restart. Must run on the main thread.
     */
    public void repairOrphans(GateManager gateManager) {
        for (World world : Bukkit.getServer().getWorlds()) {
            cleanupOrphans(world, gateManager);
        }
        syncAll(gateManager);
    }

    /**
     * Remove every display this manager currently tracks. Used on plugin shutdown.
     */
    public void removeAll() {
        for (TextDisplay display : displays.values()) {
            if (display != null && !display.isDead()) {
                display.remove();
            }
        }
        displays.clear();
    }

    /**
     * Scan a world for gate-display TextDisplays left over from a previous run (crash, or a gate
     * that has since been deleted) and remove the ones that no longer match a cached gate.
     * Entities that still match a live gate are adopted into the tracking map so startup doesn't
     * spawn duplicates. Must run before the first {@link #syncAll(GateManager)} at startup.
     */
    public void cleanupOrphans(World world, GateManager gateManager) {
        if (world == null) {
            return;
        }

        for (TextDisplay entity : world.getEntitiesByClass(TextDisplay.class)) {
            Integer gateId = entity.getPersistentDataContainer().get(gateIdKey, PersistentDataType.INTEGER);
            if (gateId == null) {
                continue; // Not a gate display entity.
            }

            if (gateManager.getGate(gateId) == null) {
                entity.remove();
                LOGGER.info("Removed orphaned gate display entity for gate ID " + gateId);
                continue;
            }

            TextDisplay alreadyAdopted = displays.get(gateId);
            if (alreadyAdopted != null && alreadyAdopted.isValid() && !alreadyAdopted.equals(entity)) {
                // A display for this gate was already adopted (this world or an earlier one in the
                // scan) - remove the extra instead of silently overwriting the map entry and leaking
                // it as an untracked, never-updated duplicate.
                entity.remove();
                LOGGER.info("Removed duplicate gate display entity for gate ID " + gateId);
                continue;
            }

            displays.put(gateId, entity);
        }
    }

    private World resolveWorld(CachedGateDoor gate) {
        String worldName = gate.getWorldName();
        if (worldName == null || worldName.isBlank()) {
            return null;
        }
        return Bukkit.getWorld(worldName);
    }

    /** How far above the door's geometric center the display floats. */
    private static final double ABOVE_CENTER_OFFSET = 1.0;
    /** How far out along FaceDirection the display is pushed, so it clears the door blocks instead
     *  of rendering inside them (see docs/features/gate-structure-animation/REQUIREMENTS.md, "FaceDirection Values"). */
    private static final double FACE_DIRECTION_OFFSET = 0.7;

    /**
     * If the gate has an admin-configured InfoDisplayLocation, that position is used as-is and none
     * of the computation below runs. Otherwise the position is derived from the gate's geometry and
     * FaceDirection:
     *
     * Width/Height are measured along the gate's local u/v basis (see
     * {@code GateFrameCalculator.isWithinGeometryBounds}, which projects world positions onto
     * uAxis/vAxis/nAxis and compares against Width/Height/Depth) - that basis is derived from
     * AnchorPoint/ReferencePoint1/ReferencePoint2 by {@code GateLoaderAdapter.precomputeBasisVectors}
     * and accounts for diagonal FaceDirections. Offsetting along world X/Z instead only happens to
     * be correct for axis-aligned (north/south/east/west) gates and places the display off to the
     * side for a diagonally-facing (e.g. north-east) gate.
     *
     * Depth deliberately uses FaceDirection for both the half-depth centering and the clearance
     * push below, rather than the gate's auto-derived nAxis ({@code cross(uAxis, vAxis)}, whose
     * sign depends on how ReferencePoint1/2 happen to be ordered and isn't guaranteed to agree with
     * FaceDirection). Mixing the two risked the two offsets partially cancelling - or for a thin
     * (1-2 block deep) gate, cancelling past the anchor entirely - landing the display back inside
     * the door/wall blocks and making it fully invisible, exactly what this method used to do.
     */
    private Location calculateDisplayLocation(CachedGateDoor gate, World world) {
        Vector manualOverride = gate.getInfoDisplayLocation();
        if (manualOverride != null) {
            return new Location(world, manualOverride.getX(), manualOverride.getY(), manualOverride.getZ());
        }

        Vector anchor = gate.getAnchorPoint();
        if (anchor == null) {
            return null;
        }

        Vector uAxis = gate.getUAxis();
        Vector vAxis = gate.getVAxis();
        Vector faceDirection = resolveFaceDirectionVector(gate);

        Vector widthOffset = uAxis != null && uAxis.lengthSquared() > 0
            ? uAxis.clone().multiply(gate.getGeometryWidth() / 2.0)
            : new Vector(gate.getGeometryWidth() / 2.0, 0, 0);
        Vector heightOffset = vAxis != null && vAxis.lengthSquared() > 0
            ? vAxis.clone().multiply(gate.getGeometryHeight() / 2.0)
            : new Vector(0, gate.getGeometryHeight() / 2.0, 0);

        // Center of the door (anchor + half width/height + half depth along FaceDirection), then 1
        // block up and a further 0.7 blocks out along that same FaceDirection so the text clears
        // the door blocks instead of rendering inside them.
        double depthPush = gate.getGeometryDepth() / 2.0 + FACE_DIRECTION_OFFSET;
        Vector position = anchor.clone()
            .add(widthOffset)
            .add(heightOffset)
            .add(new Vector(0, ABOVE_CENTER_OFFSET, 0))
            .add(faceDirection.multiply(depthPush));

        return new Location(world, position.getX(), position.getY(), position.getZ());
    }

    /** FaceDirection as a world-space unit vector, falling back to the gate's precomputed normal axis.
     *  Package-private (not private) and static so it's directly unit-testable without a live
     *  Bukkit World, matching GatePassThroughService's pure-geometry-helper convention. */
    static Vector resolveFaceDirectionVector(CachedGateDoor gate) {
        Vector faceDirection = EntityPusher.vectorFromFaceDirection(gate.getFaceDirection());
        if (faceDirection != null && faceDirection.lengthSquared() > 0) {
            return faceDirection;
        }

        Vector nAxis = gate.getNAxis();
        if (nAxis != null && nAxis.lengthSquared() > 0) {
            return nAxis.clone();
        }

        return new Vector(0, 0, 0);
    }

    /**
     * Combines structure + door info (decision 5.0-D): the structure's own name (gated by
     * GateNameDisplayMode, unchanged from before item 5 - it always meant "the top-level gate's
     * name"), this door's own name (gated by the new DoorNameDisplayMode), then health and
     * status - each line independently gated by its own (possibly structure-overridden) mode.
     */
    private Component buildDisplayText(CachedGateDoor gate) {
        Component text = null;

        if (isTopicVisible(gate.getEffectiveGateNameDisplayMode(), gate)) {
            text = appendLine(text, Component.text(gate.getStructureName(), NamedTextColor.WHITE));
        }

        if (isTopicVisible(gate.getDoorNameDisplayMode(), gate)) {
            text = appendLine(text, Component.text(gate.getName(), NamedTextColor.GRAY));
        }

        if (isHealthVisible(gate)) {
            text = appendLine(text, buildHealthLine(gate));
        }

        if (isTopicVisible(gate.getEffectiveStatusDisplayMode(), gate)) {
            text = appendLine(text, buildStatusLine(gate));
        }

        return text;
    }

    private Component appendLine(Component existing, Component line) {
        return existing == null ? line : existing.append(Component.newline()).append(line);
    }

    /** Shared visibility rule for the Gate Name / Door Name / Status topics (ALWAYS / NEVER / SIEGE_ONLY). */
    private boolean isTopicVisible(String mode, CachedGateDoor gate) {
        String resolved = mode != null ? mode : "ALWAYS";
        return switch (resolved) {
            case "NEVER" -> false;
            case "SIEGE_ONLY" -> gate.getCurrentSiegeId() != null;
            default -> true;
        };
    }

    /** Health has its own rule since it has the extra DAMAGED_ONLY mode and a master toggle. */
    private boolean isHealthVisible(CachedGateDoor gate) {
        if (!gate.isEffectivelyShowHealthDisplay()) {
            return false;
        }

        String mode = gate.getEffectiveHealthDisplayMode() != null ? gate.getEffectiveHealthDisplayMode() : "ALWAYS";
        return switch (mode) {
            case "NEVER" -> false;
            case "DAMAGED_ONLY" -> gate.getHealthCurrent() < gate.getHealthMax();
            case "SIEGE_ONLY" -> gate.getCurrentSiegeId() != null;
            default -> true;
        };
    }

    private Component buildHealthLine(CachedGateDoor gate) {
        double max = Math.max(1.0, gate.getHealthMax());
        double ratio = gate.getHealthCurrent() / max;

        NamedTextColor color;
        if (gate.isEffectivelyDestroyed()) {
            color = NamedTextColor.DARK_RED;
        } else if (ratio >= 0.75) {
            color = NamedTextColor.GREEN;
        } else if (ratio >= 0.5) {
            color = NamedTextColor.YELLOW;
        } else if (ratio >= 0.25) {
            color = NamedTextColor.GOLD;
        } else {
            color = NamedTextColor.RED;
        }

        String healthText = String.format("%.0f/%.0f hp", gate.getHealthCurrent(), gate.getHealthMax());
        return Component.text(healthText, color);
    }

    private Component buildStatusLine(CachedGateDoor gate) {
        if (gate.isEffectivelyDestroyed()) {
            return Component.text("DESTROYED", NamedTextColor.DARK_RED);
        }
        if (gate.isEffectivelyInvincible()) {
            return Component.text("INVINCIBLE", NamedTextColor.AQUA);
        }

        AnimationState state = gate.getCurrentState();
        if (state == null) {
            return Component.text("UNKNOWN", NamedTextColor.GRAY);
        }

        return switch (state) {
            case OPEN -> Component.text("OPEN", NamedTextColor.GREEN);
            case OPENING -> Component.text("OPENING", NamedTextColor.GOLD);
            case CLOSING -> Component.text("CLOSING", NamedTextColor.GOLD);
            case CLOSED -> Component.text("CLOSED", NamedTextColor.RED);
        };
    }
}
