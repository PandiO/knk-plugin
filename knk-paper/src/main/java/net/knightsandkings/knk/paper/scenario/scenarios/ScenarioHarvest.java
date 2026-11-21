package net.knightsandkings.knk.paper.scenario.scenarios;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

import net.knightsandkings.knk.core.ports.api.HarvestApi;
import net.knightsandkings.knk.core.production.harvest.HarvestArea;
import net.knightsandkings.knk.core.production.harvest.HarvestCatalog;
import net.knightsandkings.knk.core.production.harvest.HarvestRule;
import net.knightsandkings.knk.core.production.harvest.HarvestSettings;
import net.knightsandkings.knk.core.production.survey.AreaSpec;
import net.knightsandkings.knk.paper.production.harvest.HarvestModule;
import net.knightsandkings.knk.paper.production.terminal.BlueprintItems;
import net.knightsandkings.knk.paper.scenario.ScenarioPlayer;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * The player harvesting scenarios' stand-ins (PandiO/knk-workspace#134): a recording {@link HarvestApi} that books every
 * entry ({@code workerUnits} = 2 per break, the block back after {@link #REGEN_SECONDS}), cobblestone for every blueprint,
 * a catalogue with one area over the whole scene, and a module started on them with its files in a temporary folder.
 */
final class ScenarioHarvest {

    static final int REGEN_SECONDS = 2;
    static final String NODE = "knk.harvest.scenario";

    final List<HarvestApi.ReportEntry> sent = new ArrayList<>();
    final Set<UUID> qualified = ConcurrentHashMap.newKeySet();
    final Path dir;
    HarvestModule module;

    final HarvestApi api = new HarvestApi() {
        public CompletableFuture<HarvestCatalog> catalog() {
            return new CompletableFuture<>(); // the scenario applies its catalogue itself
        }

        public CompletableFuture<ReportResult> report(List<ReportEntry> entries) {
            sent.addAll(entries);
            return CompletableFuture.completedFuture(new ReportResult(true, entries.stream()
                    .map(e -> new EntryResult(e.idempotencyKey(), "Applied", null, 41, 2L * e.breaks(), REGEN_SECONDS, 90, 100))
                    .toList()));
        }
    };

    ScenarioHarvest() {
        try {
            dir = Files.createTempDirectory("knk-harvest-scenario");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static BlueprintItems cobble() {
        return new BlueprintItems(null, null) {
            @Override
            public Template cached(int blueprintId) {
                return new Template(new ItemStack(Material.COBBLESTONE), 64, true);
            }

            @Override
            public CompletableFuture<Template> template(int blueprintId, String fallbackName) {
                return CompletableFuture.completedFuture(cached(blueprintId));
            }
        };
    }

    /** Stone (bedrock placeholder, pickaxe, the node) and oak logs (whole tree, a sapling) in one area over the scene. */
    static HarvestCatalog catalog(ScenarioScene scene) {
        HarvestRule stone = new HarvestRule(1, "Scenario stone", 3, HarvestRule.TargetKind.BLOCK, List.of("STONE"), 0, NODE,
                null, HarvestRule.ToolClass.PICKAXE, HarvestRule.ToolTier.ANY, 0, 41, HarvestRule.Placeholder.DEPLETED_BLOCK,
                "BEDROCK", 60);
        HarvestRule logs = new HarvestRule(2, "Scenario logs", 3, HarvestRule.TargetKind.BLOCK, List.of("*_LOG"), 0, NODE,
                null, HarvestRule.ToolClass.ANY, HarvestRule.ToolTier.ANY, 0, 41, HarvestRule.Placeholder.SAPLING, null, 60);
        AreaSpec area = new AreaSpec(scene.fakeId(1), scene.fakeId(2), "Scenario quarry", "Scene", "Cuboid", true, true,
                scene.world().getName(), null, null, null, null, null, null, scene.worldX(0), scene.worldY(0),
                scene.worldZ(0), scene.worldX(31), scene.worldY(23), scene.worldZ(31));
        return new HarvestCatalog(true, Instant.now(), List.of(stone, logs), List.of(new HarvestArea(area, 3, true, 1)));
    }

    /** Starts a module on the stand-ins (again after a stop: the same folder, so the saved jobs come back). */
    void start(ScenarioScene scene, int userId) {
        module = new HarvestModule(scene.services().plugin(), HarvestSettings.defaults(true), api, cobble(),
                uuid -> userId, (uuid, r) -> qualified.contains(uuid) ? null : "you need the permission " + r.permission(),
                () -> { }, AreaSpec.RegionShapes.NONE, player -> false, null, "scenario", dir, Clock.systemUTC());
        module.start();
        module.apply(catalog(scene));
    }

    void stop() {
        if (module != null) {
            module.stop();
            HandlerList.unregisterAll(module);
            module = null;
        }
    }

    /** Stop and delete the files. */
    void close() {
        stop();
        try (var files = Files.list(dir)) {
            for (Path p : files.toList()) {
                Files.deleteIfExists(p);
            }
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // a temporary folder
        }
    }

    static int cobbleIn(ScenarioPlayer player) {
        int n = 0;
        for (ItemStack stack : player.inventory().getStorageContents()) {
            if (stack != null && stack.getType() == Material.COBBLESTONE) {
                n += stack.getAmount();
            }
        }
        return n;
    }

    static long dropsNear(ScenarioScene scene, int x, int y, int z) {
        return scene.world().getNearbyEntities(scene.location(x + 0.5, y + 0.5, z + 0.5), 4, 4, 4).stream()
                .filter(e -> e instanceof Item).count();
    }
}
