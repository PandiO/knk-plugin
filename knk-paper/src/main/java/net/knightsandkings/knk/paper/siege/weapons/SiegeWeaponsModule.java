package net.knightsandkings.knk.paper.siege.weapons;

import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import net.knightsandkings.knk.core.siege.weapons.SpotTaskOutput;
import net.knightsandkings.knk.paper.tasks.WorldTaskHandlerRegistry;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.Executor;

/**
 * Wires the siege weapons feature (PandiO/knk-workspace#354). Phase 2 (#783, #799): the {@code SiegeWeaponSpot} world
 * task handler, started from the weapon spot form in the web app. With {@code siege.weapons.enabled} off (the default)
 * nothing is registered.
 */
public final class SiegeWeaponsModule {

    private SiegeWeaponsModule() {
    }

    /** @return the spot task handler, or null when the feature is off */
    public static WeaponSpotTaskHandler register(JavaPlugin plugin, SiegeWeaponsSettings settings,
                                                 WorldTaskHandlerRegistry registry, WorldTasksApi worldTasksApi) {
        if (settings == null || !settings.enabled() || registry == null || worldTasksApi == null) {
            return null;
        }
        Executor mainThread = runnable -> plugin.getServer().getScheduler().runTask(plugin, runnable);
        WeaponSpotTaskHandler handler = new WeaponSpotTaskHandler(worldTasksApi, new WorldEditSpotSelections(),
                BukkitBlockProbe::new, mainThread, System::currentTimeMillis);
        // By task type only: the form puts the task on a bounds field, which has no handler of its own.
        registry.registerHandler(SpotTaskOutput.TASK_TYPE, handler);
        plugin.getLogger().info("Siege weapons are on (" + SiegeWeaponsSettings.ENABLED_PATH + "): the "
                + SpotTaskOutput.TASK_TYPE + " world task is registered.");
        return handler;
    }
}
