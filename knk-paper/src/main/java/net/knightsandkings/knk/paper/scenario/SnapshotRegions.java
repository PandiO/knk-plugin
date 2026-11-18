package net.knightsandkings.knk.paper.scenario;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import net.knightsandkings.knk.core.npc.nav.snapshot.NavSnapshot;
import net.knightsandkings.knk.core.scenario.ScenarioAssertionError;
import net.knightsandkings.knk.paper.regions.access.DomainAccessFlags;

/** The WorldGuard side of {@link SnapshotReplay}: a snapshot's domains as regions in the scene's world. */
final class SnapshotRegions {

    private SnapshotRegions() {
    }

    static List<String> build(SnapshotReplay replay, ScenarioScene scene, List<String> built) {
        RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer()
            .get(BukkitAdapter.adapt(scene.world()));
        if (manager == null) {
            throw new ScenarioAssertionError("WorldGuard has no region manager for world " + scene.world().getName());
        }
        int[] frame = replay.frame();
        int dx = scene.worldX(0) - frame[0];
        int dy = scene.worldY(0) - frame[1];
        int dz = scene.worldZ(0) - frame[2];
        Map<String, ProtectedRegion> made = new HashMap<>();
        List<String> ids = new ArrayList<>();
        for (NavSnapshot.Domain d : replay.snapshot().domains()) {
            String id = replay.regionId(scene, d.regionId());
            ProtectedRegion region;
            if ("polygon".equals(d.shape())) {
                List<BlockVector2> points = new ArrayList<>();
                for (int[] p : d.points()) {
                    points.add(BlockVector2.at(p[0] + dx, p[1] + dz));
                }
                region = new ProtectedPolygonalRegion(id, points, d.minY() + dy, d.maxY() + dy);
            } else if (d.points().size() == 2) {
                int[] a = d.points().get(0);
                int[] b = d.points().get(1);
                region = new ProtectedCuboidRegion(id, BlockVector3.at(a[0] + dx, a[1] + dy, a[2] + dz),
                    BlockVector3.at(b[0] + dx, b[1] + dy, b[2] + dz));
            } else {
                continue; // a shape the snapshot could not describe
            }
            region.setPriority(d.priority());
            if (DomainAccessFlags.registered()) {
                setFlag(region, DomainAccessFlags.entry(), d.entryFlag());
                setFlag(region, DomainAccessFlags.exit(), d.exitFlag());
            }
            manager.addRegion(region);
            made.put(d.regionId(), region);
            built.add(id);
            ids.add(id);
        }
        for (NavSnapshot.Domain d : replay.snapshot().domains()) {
            ProtectedRegion child = made.get(d.regionId());
            ProtectedRegion parent = d.parent() == null ? null : made.get(d.parent());
            if (child != null && parent != null) {
                try {
                    child.setParent(parent);
                } catch (ProtectedRegion.CircularInheritanceException e) {
                    throw new ScenarioAssertionError("snapshot regions inherit in a circle at " + d.regionId());
                }
            }
        }
        return ids;
    }

    private static void setFlag(ProtectedRegion region, StateFlag flag, String value) {
        if (flag == null || value == null) {
            return;
        }
        region.setFlag(flag, "allow".equalsIgnoreCase(value) ? StateFlag.State.ALLOW : StateFlag.State.DENY);
    }

    static void remove(ScenarioScene scene, List<String> ids) {
        RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer()
            .get(BukkitAdapter.adapt(scene.world()));
        if (manager == null) {
            return;
        }
        for (String id : ids) {
            if (manager.hasRegion(id)) {
                manager.removeRegion(id);
            }
        }
    }
}
