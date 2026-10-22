package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.paper.enchantment.GateAreaDamage;
import net.knightsandkings.knk.paper.events.GateDoorDamageEvent;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lets area enchantments (chaos, flash_chaos) damage gate doors in range: finds the door blocks within the
 * radius through GateManager's spatial index and hands each gate to GateDoorHitService once, as a
 * {@link GateDoorDamageEvent.Cause#ENCHANTMENT} carrying the enchantment's own damage. Going through the
 * hit service keeps every gate rule in one place: only a closed, active, standing gate is hurt, the siege
 * listener can veto it (a defender doesn't hurt their own gate), and the statistics see the loss.
 */
public class GateAreaDamageService implements GateAreaDamage {
    private final GateManager gateManager;
    private final GateDoorHitService hitService;
    private final double radius;

    /** @param radius how many blocks from the caster a door block still counts as in range */
    public GateAreaDamageService(GateManager gateManager, GateDoorHitService hitService, double radius) {
        this.gateManager = gateManager;
        this.hitService = hitService;
        this.radius = radius;
    }

    @Override
    public void damageGatesAround(Player source, Location center, double damage) {
        World world = center == null ? null : center.getWorld();
        if (world == null || !(damage > 0) || !(radius > 0)) {
            return;
        }
        int reach = (int) Math.ceil(radius);
        int baseX = center.getBlockX();
        int baseY = center.getBlockY();
        int baseZ = center.getBlockZ();

        // Per gate the door block nearest to the centre (it's the block the hit is reported on).
        Map<Integer, int[]> nearest = new LinkedHashMap<>();
        Map<Integer, Double> nearestDistance = new LinkedHashMap<>();
        for (int x = baseX - reach; x <= baseX + reach; x++) {
            for (int y = baseY - reach; y <= baseY + reach; y++) {
                for (int z = baseZ - reach; z <= baseZ + reach; z++) {
                    double distance = distanceToBlock(center, x, y, z);
                    if (distance > radius) {
                        continue;
                    }
                    Integer gateId = gateManager.getSpatialIndex().lookup(world.getName(), x, y, z);
                    if (gateId == null) {
                        continue;
                    }
                    Double best = nearestDistance.get(gateId);
                    if (best == null || distance < best) {
                        nearestDistance.put(gateId, distance);
                        nearest.put(gateId, new int[] {x, y, z});
                    }
                }
            }
        }

        for (Map.Entry<Integer, int[]> entry : nearest.entrySet()) {
            CachedGateDoor gate = gateManager.getGate(entry.getKey());
            int[] at = entry.getValue();
            Block block = world.getBlockAt(at[0], at[1], at[2]);
            hitService.handleDamage(gate, source, block, GateDoorDamageEvent.Cause.ENCHANTMENT, damage);
        }
    }

    /** Distance from a point to the nearest point of a block's cube (0 inside it). */
    static double distanceToBlock(Location point, int blockX, int blockY, int blockZ) {
        double dx = Math.max(Math.max(blockX - point.getX(), 0.0), point.getX() - (blockX + 1));
        double dy = Math.max(Math.max(blockY - point.getY(), 0.0), point.getY() - (blockY + 1));
        double dz = Math.max(Math.max(blockZ - point.getZ(), 0.0), point.getZ() - (blockZ + 1));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
