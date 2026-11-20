package net.knightsandkings.knk.paper.npc.citizens;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import net.knightsandkings.knk.core.npc.nav.NpcClimbables;
import net.knightsandkings.knk.paper.npc.nav.BukkitNavWorld;

/**
 * Roof hatches NPCs open on their way (link H1, PandiO/knk-workspace#779), as they open doors: Citizens' own door
 * opener skips trapdoors. A closed trapdoor over a ladder ({@link BukkitNavWorld#closedHatch}) is opened when an NPC
 * that may open doors is about to climb through it ({@link #ahead}); it is closed again once nobody has been in its
 * shaft for {@link #CLOSE_AFTER_TICKS} (checked every {@link #CHECK_TICKS}), unless someone else closed or opened it
 * in between. Main thread; one per Citizens runtime.
 */
final class NpcHatches {

    /** A hatch is opened when its centre is at most this far from the body's centre. */
    static final double REACH = 2.5;
    /** Ticks a hatch stays open after the last body left its shaft. */
    static final int CLOSE_AFTER_TICKS = 30;
    static final int CHECK_TICKS = 10;

    private final Plugin plugin;
    /** Hatches this runtime opened, by block, with the tick their shaft was last seen empty-to-busy. */
    private final Map<Block, long[]> opened = new HashMap<>();
    private BukkitTask closer;
    private long ticks;

    NpcHatches(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Opens the closed hatch the body is about to climb through, if any: in the column of {@code destination} (the
     * engine's current waypoint) from a block below it to a block above it, or right above the body's head, within
     * {@link #REACH}. Returns whether one was opened.
     */
    boolean ahead(LivingEntity body, Location destination) {
        if (body == null || !body.isValid()) {
            return false;
        }
        World world = body.getWorld();
        Location at = body.getLocation();
        Block hatch = null;
        if (destination != null && destination.getWorld() != null && destination.getWorld().equals(world)) {
            hatch = find(world, destination.getBlockX(), destination.getBlockY() - 1, destination.getBlockY() + 1,
                destination.getBlockZ(), at, body.getHeight());
        }
        if (hatch == null) {
            int head = (int) Math.floor(at.getY() + body.getHeight());
            hatch = find(world, at.getBlockX(), head, head + 1, at.getBlockZ(), at, body.getHeight());
        }
        if (hatch == null) {
            return false;
        }
        open(hatch);
        return true;
    }

    private static Block find(World world, int x, int y0, int y1, int z, Location at, double height) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }
        for (int y = y0; y <= y1; y++) {
            if (y <= world.getMinHeight() || y >= world.getMaxHeight()) {
                continue;
            }
            Block block = world.getBlockAt(x, y, z);
            if (BukkitNavWorld.closedHatch(block, NpcClimbables.DEFAULT)
                && near(at, height, x + 0.5, y + 0.5, z + 0.5)) {
                return block;
            }
        }
        return null;
    }

    /** Whether a block centre is within {@link #REACH} of the body's centre. */
    static boolean near(Location at, double height, double x, double y, double z) {
        double dx = x - at.getX();
        double dy = y - (at.getY() + height / 2);
        double dz = z - at.getZ();
        return dx * dx + dy * dy + dz * dz <= REACH * REACH;
    }

    private void open(Block hatch) {
        if (!(hatch.getBlockData() instanceof TrapDoor trapdoor) || trapdoor.isOpen()) {
            return;
        }
        trapdoor.setOpen(true);
        hatch.setBlockData(trapdoor);
        hatch.getWorld().playSound(hatch.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_WOODEN_TRAPDOOR_OPEN, 1f, 1f);
        opened.put(hatch, new long[] {ticks});
        if (closer == null) {
            closer = Bukkit.getScheduler().runTaskTimer(plugin, this::check, CHECK_TICKS, CHECK_TICKS);
        }
    }

    /** Closes the hatches whose shaft has been empty long enough; stops when none is left open. */
    void check() {
        ticks += CHECK_TICKS;
        Iterator<Map.Entry<Block, long[]>> it = opened.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Block, long[]> e = it.next();
            Block hatch = e.getKey();
            if (!hatch.getWorld().isChunkLoaded(hatch.getX() >> 4, hatch.getZ() >> 4)
                || !(hatch.getBlockData() instanceof TrapDoor trapdoor) || !trapdoor.isOpen()) {
                it.remove(); // gone, unloaded or closed by someone else: leave it as it is
                continue;
            }
            if (busy(hatch)) {
                e.getValue()[0] = ticks;
            } else if (ticks - e.getValue()[0] >= CLOSE_AFTER_TICKS) {
                trapdoor.setOpen(false);
                hatch.setBlockData(trapdoor);
                hatch.getWorld().playSound(hatch.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE,
                    1f, 1f);
                it.remove();
            }
        }
        if (opened.isEmpty() && closer != null) {
            closer.cancel();
            closer = null;
        }
    }

    /** Whether a living body is in the hatch's shaft: the hatch block, two below it or one above it. */
    private static boolean busy(Block hatch) {
        BoundingBox shaft = new BoundingBox(hatch.getX(), hatch.getY() - 2, hatch.getZ(), hatch.getX() + 1,
            hatch.getY() + 2, hatch.getZ() + 1);
        return !hatch.getWorld().getNearbyEntities(shaft, entity -> entity instanceof LivingEntity).isEmpty();
    }

    /** The hatches opened and not closed yet (tests). */
    Set<Block> open() {
        return Set.copyOf(opened.keySet());
    }

    /** Stops the closing check (the runtime shuts down); open hatches stay open. */
    void stop() {
        if (closer != null) {
            closer.cancel();
            closer = null;
        }
        opened.clear();
    }
}
