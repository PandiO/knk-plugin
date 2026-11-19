package net.knightsandkings.knk.paper.scenario.scenarios;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import net.knightsandkings.knk.core.domain.districts.DistrictSummary;
import net.knightsandkings.knk.core.domain.structures.StructureSummary;
import net.knightsandkings.knk.core.domain.towns.TownSummary;
import net.knightsandkings.knk.core.ports.api.TerritoryApi;
import net.knightsandkings.knk.core.regions.managed.DomainRegionSpecs;
import net.knightsandkings.knk.core.regions.managed.ManagedRegionPolicy;
import net.knightsandkings.knk.core.regions.managed.ManagedRegionReconciler;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.core.territory.TerritoryHolder;
import net.knightsandkings.knk.core.territory.TerritoryInputs;
import net.knightsandkings.knk.core.territory.TerritoryLookup;
import net.knightsandkings.knk.core.territory.TerritoryReport;
import net.knightsandkings.knk.core.territory.TerritoryService;
import net.knightsandkings.knk.core.territory.TerritorySettings;
import net.knightsandkings.knk.paper.http.TerritoryLookupHandler;
import net.knightsandkings.knk.paper.map.RegionOutlineAdapter;
import net.knightsandkings.knk.paper.regions.RegionUnionOutlines;
import net.knightsandkings.knk.paper.regions.managed.WorldGuardManagedRegionStore;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * P2c (PandiO/knk-workspace#677, smoke § 3.3 steps 1-3): real WorldGuard regions for a town, its district, a warehouse in
 * the district and a rural production structure outside the town. The managed-region repair (with production on) makes
 * the rural region top level at the {@code RESOURCE_PRODUCTION} floor (30, no parent), so the town's {@code pvp deny}
 * does not reach it while it does reach the warehouse. The territory lookup the API's {@code InsideTerritory} rule calls
 * answers {@code inside: true} for the rural region in the town's territory and {@code false} for one beyond its range.
 */
public final class RuralStructureRegionScenario implements Scenario<ScenarioScene> {

    @Override
    public String name() {
        return "rural-structure-region";
    }

    @Override
    public String description() {
        return "A rural production structure's region is top level (PvP allowed) and the territory lookup finds it inside its town's territory";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        String town = scene.uniqueName("town");
        String district = scene.uniqueName("district");
        String warehouse = scene.uniqueName("warehouse");
        String quarry = scene.uniqueName("quarry");
        String outpost = scene.uniqueName("outpost");
        int townId = 422;
        AtomicReference<TerritoryService> service = new AtomicReference<>();

        plan.act("draw the town (x 0-9), district (x 2-7), warehouse (x 3-4), rural quarry (x 14-17), outpost (x 30-33)", () -> {
                ScenarioRegions.cuboid(scene, town, 0, 0, 0, 9, 10, 9);
                ScenarioRegions.cuboid(scene, district, 2, 0, 2, 7, 10, 7);
                ScenarioRegions.cuboid(scene, warehouse, 3, 0, 3, 4, 10, 4);
                ScenarioRegions.cuboid(scene, quarry, 14, 0, 2, 17, 10, 5);
                ScenarioRegions.cuboid(scene, outpost, 30, 0, 2, 33, 10, 5);
            })
            .cleanup("remove the regions", () -> ScenarioRegions.removeAll(scene, outpost, quarry, warehouse, district, town))
            .act("run the managed-region repair with production on", () -> {
                String world = scene.world().getName();
                DomainRegionSpecs.Result specs = DomainRegionSpecs.build(
                        List.of(new TownSummary(townId, "Scenario town", "", town, world)),
                        List.of(new DistrictSummary(scene.fakeId(1), "Scenario district", "", district, townId,
                                "Scenario town", world)),
                        List.of(new StructureSummary(scene.fakeId(2), "Warehouse", "", warehouse, 1, null, null,
                                        scene.fakeId(1), "Scenario district", world, townId),
                                new StructureSummary(scene.fakeId(3), "Quarry", "", quarry, 0, null, null, null, null,
                                        world, townId)),
                        Map.of(scene.fakeId(2), "Warehouse", scene.fakeId(3), "ProductionStructure"), true);
                new ManagedRegionReconciler(new ManagedRegionPolicy(), Map.of()).reconcile(specs.specs(),
                        new WorldGuardManagedRegionStore(() -> List.of(ScenarioRegions.manager(scene))));
            })
            .check("the quarry is top level at the RESOURCE_PRODUCTION floor with entry allow", () -> {
                ProtectedRegion region = ScenarioRegions.manager(scene).getRegion(quarry);
                if (region.getParent() != null) {
                    return "parent " + region.getParent().getId();
                }
                if (region.getPriority() != 30) {
                    return "priority " + region.getPriority();
                }
                return region.getFlag(Flags.ENTRY) == StateFlag.State.ALLOW ? null : "entry " + region.getFlag(Flags.ENTRY);
            })
            .check("the warehouse sits under the district", () -> {
                ProtectedRegion region = ScenarioRegions.manager(scene).getRegion(warehouse);
                return region.getParent() != null && region.getParent().getId().equalsIgnoreCase(district)
                        && region.getPriority() == 30 ? null : "parent " + region.getParent() + ", priority " + region.getPriority();
            })
            .check("PvP: denied at the warehouse (the town's flag), not denied at the rural quarry", () -> {
                StateFlag.State atWarehouse = pvpAt(scene, 3.5, 3.5);
                StateFlag.State atQuarry = pvpAt(scene, 15.5, 3.5);
                if (atWarehouse != StateFlag.State.DENY) {
                    return "warehouse pvp " + atWarehouse;
                }
                return atQuarry != StateFlag.State.DENY ? null : "quarry pvp " + atQuarry;
            })
            .act("build the territory cache: the district reaches 12 blocks from its edge (x 19)", () -> {
                TerritoryInputs inputs = new TerritoryInputs(false, "scenario", Map.of(townId, "Scenario town"), Map.of(),
                        List.of(new TerritoryInputs.Source(TerritoryInputs.SourceType.DISTRICT, scene.fakeId(1),
                                "Scenario district", scene.world().getName(), district, List.of(),
                                TerritoryHolder.town(townId), false, 12)), List.of());
                TerritoryApi api = new TerritoryApi() {
                    @Override
                    public CompletableFuture<TerritoryInputs> fetchInputs() {
                        return CompletableFuture.completedFuture(inputs);
                    }

                    @Override
                    public CompletableFuture<TerritoryReport.Result> report(TerritoryReport report) {
                        return CompletableFuture.failedFuture(new IllegalStateException("no report in a scenario"));
                    }
                };
                service.set(new TerritoryService(new TerritorySettings(true, 0, false, false, false, 0, Map.of()), api,
                        new RegionOutlineAdapter(() -> List.of(ScenarioRegions.manager(scene))), null, null,
                        Runnable::run, Runnable::run, Logger.getLogger("KnK scenario")));
                service.get().refresh("scenario", true).join();
            })
            .check("the lookup: the quarry is inside the town's territory, the outpost is not", () -> {
                TerritoryLookupHandler handler = new TerritoryLookupHandler(service::get,
                        new RegionUnionOutlines(() -> List.of(ScenarioRegions.manager(scene))),
                        () -> scene.world().getName(), Runnable::run);
                TerritoryLookup.Answer inside = handler.answer("GET", URI.create("/api/territory/contains-region/" + quarry
                        + "?holderKind=Town&holderId=" + townId + "&world=" + scene.world().getName()));
                TerritoryLookup.Answer outside = handler.answer("GET", URI.create("/api/territory/contains-region/" + outpost
                        + "?holderKind=Town&holderId=" + townId));
                if (inside.status() != 200 || !Boolean.TRUE.equals(inside.inside())) {
                    return "quarry: " + inside.json();
                }
                return outside.status() == 200 && Boolean.FALSE.equals(outside.inside()) ? null : "outpost: " + outside.json();
            });
    }

    private static StateFlag.State pvpAt(ScenarioScene scene, double x, double z) {
        return WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                .queryState(BukkitAdapter.adapt(scene.location(x, 2, z)),
                        (com.sk89q.worldguard.protection.association.RegionAssociable) null, Flags.PVP);
    }
}
