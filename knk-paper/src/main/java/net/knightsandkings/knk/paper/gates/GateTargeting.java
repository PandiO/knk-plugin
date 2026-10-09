package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.gates.target.GateBox;
import net.knightsandkings.knk.core.gates.target.GateTargetMath;
import net.knightsandkings.knk.core.gates.target.GateTargetMath.DoorCandidate;
import net.knightsandkings.knk.core.gates.target.GateTargetMath.DoorRegion;
import net.knightsandkings.knk.core.gates.target.GateTargetMath.StructureCandidate;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Finds the gate doors around a player for the gate commands' implicit targets: {@code here}
 * (KNG-78, doors whose region is within {@link Settings#hereRadius()} blocks) and the door the
 * player is looking at (KNG-79). The geometry is {@link GateTargetMath}; a door's region is
 * {@link GateDoorBounds}. Runs only when a command runs - never per tick, never on tab completion.
 */
public class GateTargeting {

    /**
     * {@code gates.here.*} and {@code gates.lookat.*} in config.yml.
     *
     * @param hereRadius        how far (blocks, to the region's closest point) {@code here} looks
     * @param hereNearest       true = pick the nearest of several candidates instead of asking
     *                          ({@code gates.here.ambiguity: nearest}; default {@code prompt})
     * @param lookAtEnabled     whether an omitted target is inferred from the player's view
     * @param lookAtMaxDistance how far the player can point at a door
     */
    public record Settings(double hereRadius, boolean hereNearest, boolean lookAtEnabled, double lookAtMaxDistance) {
        public static final double DEFAULT_HERE_RADIUS = 15;
        public static final double DEFAULT_LOOK_AT_MAX_DISTANCE = 12;

        public static Settings defaults() {
            return new Settings(DEFAULT_HERE_RADIUS, false, true, DEFAULT_LOOK_AT_MAX_DISTANCE);
        }

        /** Reads {@code gates.here.ambiguity}: {@code nearest} picks the nearest, anything else asks. */
        public static boolean parseNearest(String ambiguity) {
            return ambiguity != null && "nearest".equals(ambiguity.trim().toLowerCase(Locale.ROOT));
        }
    }

    private final GateManager gateManager;
    private Settings settings = Settings.defaults();

    public GateTargeting(GateManager gateManager) {
        this.gateManager = Objects.requireNonNull(gateManager, "gateManager");
    }

    public Settings settings() {
        return settings;
    }

    public void setSettings(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Doors within {@code gates.here.radius} of the player, nearest first. */
    public List<DoorCandidate> doorsNear(Player player) {
        Location at = player.getLocation();
        return GateTargetMath.doorsWithin(regionsIn(player.getWorld(), settings.hereRadius() + 1),
            worldName(player.getWorld()), at.getX(), at.getY(), at.getZ(), settings.hereRadius());
    }

    /** Gate structures with a door within {@code gates.here.radius} of the player, nearest first. */
    public List<StructureCandidate> structuresNear(Player player) {
        Location at = player.getLocation();
        return GateTargetMath.structuresWithin(regionsIn(player.getWorld(), settings.hereRadius() + 1),
            worldName(player.getWorld()), at.getX(), at.getY(), at.getZ(), settings.hereRadius());
    }

    /**
     * The door the player is looking at, if look-at is enabled: one block ray trace (ignoring
     * fluids and passable blocks) to find what hides the view, then the first door region the
     * line of sight enters - which finds open gates too.
     */
    public Optional<DoorCandidate> lookedAt(Player player) {
        if (!settings.lookAtEnabled()) {
            return Optional.empty();
        }
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        double maxDistance = settings.lookAtMaxDistance();

        Double blockHit = null;
        if (world != null) {
            RayTraceResult hit = world.rayTraceBlocks(eye, direction, maxDistance, FluidCollisionMode.NEVER, true);
            if (hit != null && hit.getHitPosition() != null) {
                blockHit = hit.getHitPosition().distance(eye.toVector());
            }
        }
        return GateTargetMath.lookedAt(regionsIn(world, maxDistance + 1), worldName(world),
            new double[]{eye.getX(), eye.getY(), eye.getZ()},
            new double[]{direction.getX(), direction.getY(), direction.getZ()},
            maxDistance, blockHit);
    }

    /**
     * Regions of the loaded doors in {@code world}. A door with a blank world (an anchor saved
     * without one) counts as being in the server's primary world only - the one the animation
     * falls back to - so it can't be targeted from the same coordinates in the nether.
     * {@code reach} is unused for now - the door count per world is small enough to box every
     * door - but marks where an index would go.
     */
    List<DoorRegion> regionsIn(World world, double reach) {
        String worldName = worldName(world);
        boolean primaryWorld = isPrimaryWorld(world);
        List<DoorRegion> regions = new ArrayList<>();
        for (CachedGateDoor door : gateManager.getAllGates().values()) {
            String doorWorld = door.getWorldName();
            boolean blankWorld = doorWorld == null || doorWorld.isBlank();
            if (blankWorld ? !primaryWorld : !doorWorld.equals(worldName)) {
                continue;
            }
            GateBox box = GateDoorBounds.of(door, gateManager);
            if (box != null) {
                regions.add(new DoorRegion(door.getId(), door.getGateStructureId(), worldName, box));
            }
        }
        return regions;
    }

    /** True for the server's first (default) world; true as well when that can't be told (no server, tests). */
    private static boolean isPrimaryWorld(World world) {
        if (world == null) {
            return false;
        }
        try {
            org.bukkit.Server server = org.bukkit.Bukkit.getServer();
            if (server == null || server.getWorlds().isEmpty()) {
                return true;
            }
            return server.getWorlds().get(0).getName().equals(world.getName());
        } catch (RuntimeException ex) {
            return true;
        }
    }

    private static String worldName(World world) {
        return world == null ? "" : world.getName();
    }
}
