package net.knightsandkings.knk.paper.scenario.scenarios;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.knightsandkings.knk.core.lootbox.KnkLootboxArea;
import net.knightsandkings.knk.core.lootbox.KnkLootboxRuntimeConfig;
import net.knightsandkings.knk.core.ports.api.LootboxesQueryApi;
import net.knightsandkings.knk.core.regions.TempRegionCleanupPolicy.Usage;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.regions.RegionBookkeepingFlags;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import net.knightsandkings.knk.paper.tasks.TempRegionRetentionTask;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The temp-region cleanup keeps a temp region a lootbox spawn area still stands on (lootbox area region smoke test
 * § 4 step 2 and § 5, PandiO/knk-workspace#34): two {@code tempregion_worldtask_<n>} regions, both created 30 days
 * ago, no domain using either; a lootbox area (fake runtime config, as {@code GET api/LootboxSpawns/runtime-config}
 * sends it) uses the first. A report-only sweep with the 14-day retention (what {@code /knk regions temp} shows)
 * lists the first under "used by a domain, lootbox area, landmark or production area" and the second as deletable, and removes neither.
 * Report-only, because a deleting sweep would also remove other old temp regions of the server it runs on.
 */
public final class TempRegionSweepScenario implements Scenario<ScenarioScene> {

    @Override
    public String name() {
        return "temp-region-sweep-keeps-lootbox-area-region";
    }

    @Override
    public String description() {
        return "The temp-region cleanup keeps an old temp region a lootbox area uses, and offers an unused one";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        String used = "tempregion_worldtask_" + scene.fakeId(30);
        String unused = "tempregion_worldtask_" + scene.fakeId(31);
        String world = scene.world().getName();
        AtomicReference<TempRegionRetentionTask.Sweep> sweep = new AtomicReference<>();
        plan.act("draw two temp regions created 30 days ago", () -> {
                String created = Long.toString(System.currentTimeMillis() - Duration.ofDays(30).toMillis());
                ProtectedRegion first = ScenarioRegions.cuboid(scene, used, 2, 2, 2, 10, 6, 10);
                ProtectedRegion second = ScenarioRegions.cuboid(scene, unused, 14, 2, 14, 22, 6, 22);
                RegionBookkeepingFlags.set(first, RegionBookkeepingFlags.CREATION_TIMESTAMP, created);
                RegionBookkeepingFlags.set(second, RegionBookkeepingFlags.CREATION_TIMESTAMP, created);
            })
            .cleanup("remove the test regions", () -> ScenarioRegions.removeAll(scene, used, unused))
            .act("sweep with the 14-day retention, reporting only", () -> {
                LootboxesQueryApi lootboxes = ScenarioFakes.port(LootboxesQueryApi.class, Map.of(
                    "getRuntimeConfig", args -> CompletableFuture.completedFuture(configWithAreaOn(scene, used))));
                TempRegionRetentionTask task = new TempRegionRetentionTask(scene.services().plugin(), 14,
                    regionId -> false,
                    net.knightsandkings.knk.core.regions.TempRegionCleanupPolicy.inUseByAny(
                        regionId -> Usage.UNUSED, TempRegionRetentionTask.lootboxAreaUsageVia(lootboxes)));
                sweep.set(task.sweep(System.currentTimeMillis() - Duration.ofDays(14).toMillis(), false, false));
            })
            .check("the area's temp region is kept as in use", () -> sweep.get().inUse().contains(world + "/" + used) ? null
                : "in use: " + sweep.get().inUse() + "; deletable: " + sweep.get().deletable())
            .check("the unused temp region is offered for deletion", () ->
                sweep.get().deletable().contains(world + "/" + unused) ? null
                    : "deletable: " + sweep.get().deletable() + "; in use: " + sweep.get().inUse())
            .expect("both regions still exist after the report", () -> ScenarioRegions.manager(scene).hasRegion(used)
                && ScenarioRegions.manager(scene).hasRegion(unused));
    }

    private static KnkLootboxRuntimeConfig configWithAreaOn(ScenarioScene scene, String regionId) {
        KnkLootboxArea area = new KnkLootboxArea(scene.fakeId(32), scene.uniqueName("area"), scene.world().getName(),
            regionId, false, 1, 60, 0, 0, 0, 30, List.of(), List.of(), 0, null);
        return new KnkLootboxRuntimeConfig(true, 10, null, 5, 6, null, null, null, List.of(), List.of(), List.of(area));
    }
}
