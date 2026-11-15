package net.knightsandkings.knk.paper.scenario.scenarios;

import net.knightsandkings.knk.core.lootbox.KnkLootboxArea;
import net.knightsandkings.knk.core.lootbox.KnkLootboxRuntimeConfig;
import net.knightsandkings.knk.core.lootbox.LootboxRejectedException;
import net.knightsandkings.knk.core.lootbox.LootboxSpawnPlanner;
import net.knightsandkings.knk.core.ports.api.LootboxesCommandApi;
import net.knightsandkings.knk.core.ports.api.LootboxesQueryApi;
import net.knightsandkings.knk.paper.integration.WorldGuardIntegration;
import net.knightsandkings.knk.paper.lootbox.LootboxAnnouncer;
import net.knightsandkings.knk.paper.lootbox.LootboxRuntime;
import net.knightsandkings.knk.paper.lootbox.LootboxSpawnScheduler;
import net.knightsandkings.knk.paper.lootbox.WorldGuardLootboxRegions;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The plugin's own lootbox spawning (a {@link LootboxRuntime} and a {@link LootboxSpawnScheduler} with the real
 * WorldGuard lookups, the same classes the plugin wires in {@code initializeLootboxes}) built for one scenario and fed
 * fake API data: the runtime config the API would send, and a spawn endpoint that records where the scheduler asked
 * for a box and refuses it (as a full area would), so nothing is shown and nothing reaches a real API. Nothing in the
 * live plugin is swapped. The scheduler considers the area every second; {@link #stop} in a cleanup step.
 */
public final class ScenarioLootboxes {

    /** A spot the scheduler asked the API to put a box on (world coordinates). */
    public record SpawnRequest(int areaId, String world, int x, int y, int z) {
    }

    private final AtomicReference<KnkLootboxRuntimeConfig> config = new AtomicReference<>(KnkLootboxRuntimeConfig.empty());
    private final List<SpawnRequest> requests = new ArrayList<>();
    private final LootboxRuntime runtime;
    private final LootboxSpawnScheduler scheduler;

    public ScenarioLootboxes(ScenarioScene scene) {
        LootboxesQueryApi queryApi = ScenarioFakes.port(LootboxesQueryApi.class, Map.of(
            "getRuntimeConfig", args -> CompletableFuture.completedFuture(config.get()),
            "getActive", args -> CompletableFuture.completedFuture(List.of())));
        LootboxesCommandApi commandApi = ScenarioFakes.port(LootboxesCommandApi.class, Map.of(
            "spawn", args -> {
                synchronized (requests) {
                    requests.add(new SpawnRequest((Integer) args[0], (String) args[1], (Integer) args[2], (Integer) args[3],
                        (Integer) args[4]));
                }
                return CompletableFuture.failedFuture(new LootboxRejectedException(409, "AreaFull",
                    "scenario: spawn recorded, not created", null, null, null));
            }));
        MemoryConfiguration section = new MemoryConfiguration();
        section.set("enabled", true);
        section.set("server-id", "scenario");
        section.set("scheduler-tick-seconds", 1);
        section.set("surface.max-attempts-per-tick", 8);
        JavaPlugin plugin = (JavaPlugin) scene.services().plugin();
        runtime = new LootboxRuntime(plugin, queryApi, () -> section, Clock.systemUTC());
        Random random = new Random(511);
        scheduler = new LootboxSpawnScheduler(plugin, runtime, new WorldGuardLootboxRegions(new WorldGuardIntegration(plugin)),
            commandApi, new LootboxAnnouncer(message -> { }), new LootboxSpawnPlanner(random::nextDouble), java.util.Set::of);
    }

    /**
     * An enabled area on {@code regionId} in the scene world that is tried every second with a 100 % chance, needs no
     * player online and keeps no distance from players.
     */
    public static KnkLootboxArea area(ScenarioScene scene, int fakeIdSlot, String regionId) {
        return new KnkLootboxArea(scene.fakeId(fakeIdSlot), scene.uniqueName("area"), scene.world().getName(), regionId,
            true, 1000, 1, 100, 0, 0, 30, List.of(), List.of(), 0, null);
    }

    /** What the API's runtime config would say: lootboxes on or off (the web app's switch) and these areas. */
    public static KnkLootboxRuntimeConfig runtimeConfig(boolean enabled, KnkLootboxArea... areas) {
        return new KnkLootboxRuntimeConfig(enabled, 1000, null, 5, 6, null, null, Instant.now(), List.of(), List.of(),
            List.of(areas));
    }

    /** Sets what the fake API answers and re-reads it, as the runtime's periodic refresh does. */
    public CompletableFuture<Void> serve(KnkLootboxRuntimeConfig runtimeConfig) {
        config.set(runtimeConfig);
        return runtime.refresh();
    }

    public void start() {
        scheduler.start();
    }

    public void stop() {
        scheduler.stop();
        runtime.stop();
    }

    public List<SpawnRequest> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    public boolean configApplied(KnkLootboxRuntimeConfig expected) {
        return runtime.config() == expected;
    }
}
