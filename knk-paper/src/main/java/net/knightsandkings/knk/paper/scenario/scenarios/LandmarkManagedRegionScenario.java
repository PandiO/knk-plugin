package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import net.knightsandkings.knk.core.domain.landmarks.KnkLandmark;
import net.knightsandkings.knk.core.domain.landmarks.LandmarkAreaKind;
import net.knightsandkings.knk.core.domain.towns.TownSummary;
import net.knightsandkings.knk.core.regions.managed.DomainRegionSpecs;
import net.knightsandkings.knk.core.regions.managed.ManagedRegionPolicy;
import net.knightsandkings.knk.core.regions.managed.ManagedRegionReconciler;
import net.knightsandkings.knk.core.regions.managed.ManagedRegionSpec;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.regions.managed.WorldGuardManagedRegionStore;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * A13 (landmark-domain DESIGN LD13, PandiO/knk-workspace#985, smoke § 3 step 4): real WorldGuard regions for a town, a
 * landmark inside it (the town as parent) and a landmark in the wilderness. The managed-region repair makes the town's
 * landmark a LANDMARK region under the town's main region (priority the town's + 10, at least 30) with no category flags
 * of its own, so the town's {@code pvp deny} reaches it; the wilderness landmark is top level at 30 and the town's flags
 * don't reach it. After its parent and priority are broken by hand, the repair restores them.
 */
public final class LandmarkManagedRegionScenario implements Scenario<ScenarioScene> {

    @Override
    public String name() {
        return "landmark-region-is-managed-under-its-parent";
    }

    @Override
    public String description() {
        return "A landmark's own region is a LANDMARK region under its parent's main region; the repair restores it";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        String town = scene.uniqueName("town");
        String well = "domain_" + scene.fakeId(51);
        String tower = "domain_" + scene.fakeId(52);
        int townId = 451;
        List<ManagedRegionSpec> specs = new ArrayList<>();

        plan.act("draw the town (x 0-9), a landmark in it (x 3-4) and one in the wilderness (x 14-17)", () -> {
                ScenarioRegions.cuboid(scene, town, 0, 0, 0, 9, 10, 9);
                ScenarioRegions.cuboid(scene, well, 3, 0, 3, 4, 10, 4);
                ScenarioRegions.cuboid(scene, tower, 14, 0, 2, 17, 10, 5);
            })
            .cleanup("remove the regions", () -> ScenarioRegions.removeAll(scene, tower, well, town))
            .act("run the managed-region repair with the town and both landmarks", () -> {
                String world = scene.world().getName();
                specs.addAll(DomainRegionSpecs.build(List.of(new TownSummary(townId, "Scenario town", "", town, world)),
                        List.of(), List.of(), Map.of()).specs());
                specs.addAll(DomainRegionSpecs.landmarks(List.of(
                        landmark(scene.fakeId(51), "Well", world, well, town),
                        landmark(scene.fakeId(52), "Tower", world, tower, null))).specs());
                repair(scene, specs);
            })
            .check("the town's landmark sits under the town at 30, without flags of its own", () -> problem(scene, well, town, 30))
            .check("the wilderness landmark is top level at 30", () -> problem(scene, tower, null, 30))
            .check("PvP: denied in the town's landmark (the town's flag), not in the wilderness landmark", () -> {
                StateFlag.State inTown = pvpAt(scene, 3.5, 3.5);
                StateFlag.State outside = pvpAt(scene, 15.5, 3.5);
                if (inTown != StateFlag.State.DENY) {
                    return "town landmark pvp " + inTown;
                }
                return outside != StateFlag.State.DENY ? null : "wilderness landmark pvp " + outside;
            })
            .act("break the town landmark's parent and priority by hand, then repair again", () -> {
                ProtectedRegion region = ScenarioRegions.manager(scene).getRegion(well);
                try {
                    region.setParent(null);
                } catch (ProtectedRegion.CircularInheritanceException e) {
                    throw new IllegalStateException(e);
                }
                region.setPriority(0);
                repair(scene, specs);
            })
            .check("the repair restored parent and priority", () -> problem(scene, well, town, 30));
    }

    private static KnkLandmark landmark(int id, String name, String world, String region, String parentRegion) {
        return new KnkLandmark(id, name, null, world, 0, 64, 0, 0f, 0f, parentRegion == null ? null : 451,
                parentRegion == null ? null : "Scenario town", parentRegion == null ? null : "Town", parentRegion,
                LandmarkAreaKind.OWN_REGION, region, List.of(), false, 1.0, true);
    }

    private static void repair(ScenarioScene scene, List<ManagedRegionSpec> specs) {
        new ManagedRegionReconciler(new ManagedRegionPolicy(), Map.of()).reconcile(specs,
                new WorldGuardManagedRegionStore(() -> List.of(ScenarioRegions.manager(scene))));
    }

    private static String problem(ScenarioScene scene, String regionId, String parent, int priority) {
        ProtectedRegion region = ScenarioRegions.manager(scene).getRegion(regionId);
        if (region == null) {
            return "no region " + regionId;
        }
        String actualParent = region.getParent() == null ? null : region.getParent().getId();
        if (parent == null ? actualParent != null : !parent.equalsIgnoreCase(actualParent)) {
            return "parent " + actualParent;
        }
        if (region.getPriority() != priority) {
            return "priority " + region.getPriority();
        }
        return region.getFlag(Flags.PVP) == null && region.getFlag(Flags.ENTRY) == null ? null
                : "category flags set: pvp " + region.getFlag(Flags.PVP) + ", entry " + region.getFlag(Flags.ENTRY);
    }

    private static StateFlag.State pvpAt(ScenarioScene scene, double x, double z) {
        return WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                .queryState(BukkitAdapter.adapt(scene.location(x, 2, z)),
                        (com.sk89q.worldguard.protection.association.RegionAssociable) null, Flags.PVP);
    }
}
