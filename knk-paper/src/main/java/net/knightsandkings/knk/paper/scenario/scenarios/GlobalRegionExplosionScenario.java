package net.knightsandkings.knk.paper.scenario.scenarios;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioAssertionError;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.regions.GlobalRegionProtectionListener;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import org.bukkit.Material;
import org.bukkit.entity.TNTPrimed;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Explosions break nothing where only WorldGuard's global region applies, but do inside a region
 * (QOL hotfix 2026-10-25, {@code regions.global.protection}): primed TNT inside a hollow stone cube
 * outside every region leaves all 26 stone blocks; the same cube inside a temporary WorldGuard region
 * (TNT allowed) is blown apart. The second half is the control that proves the blast was real.
 */
public final class GlobalRegionExplosionScenario implements Scenario<ScenarioScene> {

    private static final int CUBE_Y = 2;
    private static final int OUTSIDE_X = 4;
    private static final int INSIDE_X = 22;
    private static final int CUBE_Z = 8;

    @Override
    public String name() {
        return "global-region-explosion";
    }

    @Override
    public String description() {
        return "TNT breaks no blocks outside every region, but does inside a WorldGuard region";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        String regionId = scene.uniqueName("inside");
        AtomicReference<TNTPrimed> outsideTnt = new AtomicReference<>();
        AtomicReference<TNTPrimed> insideTnt = new AtomicReference<>();
        plan.expect("regions.global.protection is on",
                () -> scene.services().config().getBoolean(GlobalRegionProtectionListener.CONFIG_PATH, true))
            .act("build two hollow stone cubes", () -> {
                cube(scene, OUTSIDE_X);
                cube(scene, INSIDE_X);
            })
            .act("add a WorldGuard region around the second cube", () -> regions(scene).addRegion(region(scene, regionId)))
            .cleanup("remove the WorldGuard region", () -> {
                RegionManager manager = regions(scene);
                if (manager.hasRegion(regionId)) manager.removeRegion(regionId);
            })
            .check("no region covers the first cube", () -> regionsAt(scene, OUTSIDE_X + 1).isEmpty() ? null
                : "regions at the first cube: " + regionsAt(scene, OUTSIDE_X + 1) + " (move testing.scenarios.origin)")
            .expectEquals("regions at the second cube", () -> regionsAt(scene, INSIDE_X + 1), Set.of(regionId))
            .act("prime TNT inside both cubes", () -> {
                outsideTnt.set(tnt(scene, OUTSIDE_X));
                insideTnt.set(tnt(scene, INSIDE_X));
            })
            .await("both TNT blocks exploded", 120, () -> !outsideTnt.get().isValid() && !insideTnt.get().isValid())
            .waitTicks(2)
            .expectEquals("stone blocks left of the first cube's 26", () -> stoneLeft(scene, OUTSIDE_X), 26)
            .check("the explosion broke the second cube (control)", () -> stoneLeft(scene, INSIDE_X) < 26 ? null
                : "TNT inside region " + regionId + " broke nothing; check WorldGuard's tnt settings");
    }

    /** A 3 × 3 × 3 stone cube with an empty centre, its lowest corner at (x, CUBE_Y, CUBE_Z). */
    private static void cube(ScenarioScene scene, int x) {
        for (int dx = 0; dx < 3; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                for (int dz = 0; dz < 3; dz++) {
                    boolean centre = dx == 1 && dy == 1 && dz == 1;
                    scene.block(x + dx, CUBE_Y + dy, CUBE_Z + dz).setType(centre ? Material.AIR : Material.STONE, false);
                }
            }
        }
    }

    private static int stoneLeft(ScenarioScene scene, int x) {
        int count = 0;
        for (int dx = 0; dx < 3; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                for (int dz = 0; dz < 3; dz++) {
                    if (scene.block(x + dx, CUBE_Y + dy, CUBE_Z + dz).getType() == Material.STONE) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static TNTPrimed tnt(ScenarioScene scene, int x) {
        TNTPrimed tnt = scene.spawn(x + 1.5, CUBE_Y + 1, CUBE_Z + 1.5, TNTPrimed.class);
        tnt.setFuseTicks(10);
        return tnt;
    }

    private static ProtectedRegion region(ScenarioScene scene, String id) {
        ProtectedCuboidRegion region = new ProtectedCuboidRegion(id,
            BlockVector3.at(scene.worldX(INSIDE_X - 3), scene.worldY(0), scene.worldZ(CUBE_Z - 3)),
            BlockVector3.at(scene.worldX(INSIDE_X + 5), scene.worldY(8), scene.worldZ(CUBE_Z + 5)));
        region.setFlag(Flags.TNT, StateFlag.State.ALLOW);
        region.setFlag(Flags.OTHER_EXPLOSION, StateFlag.State.ALLOW);
        return region;
    }

    private static RegionManager regions(ScenarioScene scene) {
        RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer()
            .get(BukkitAdapter.adapt(scene.world()));
        if (manager == null) {
            throw new ScenarioAssertionError("WorldGuard has no region manager for world " + scene.world().getName());
        }
        return manager;
    }

    /** Region ids at the centre of a cube, straight from the region manager (no query cache). */
    private static Set<String> regionsAt(ScenarioScene scene, int centreX) {
        BlockVector3 at = BlockVector3.at(scene.worldX(centreX), scene.worldY(CUBE_Y + 1), scene.worldZ(CUBE_Z + 1));
        return regions(scene).getApplicableRegions(at).getRegions().stream()
            .map(ProtectedRegion::getId)
            .filter(id -> !"__global__".equalsIgnoreCase(id))
            .collect(Collectors.toSet());
    }
}
