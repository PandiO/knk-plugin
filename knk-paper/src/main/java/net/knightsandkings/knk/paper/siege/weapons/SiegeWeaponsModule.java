package net.knightsandkings.knk.paper.siege.weapons;

import net.knightsandkings.knk.core.ports.api.SiegeWeaponSpotsApi;
import net.knightsandkings.knk.paper.commands.KnkAdminCommand;
import net.knightsandkings.knk.paper.listeners.WorldTaskChatListener;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * Wires the siege weapons feature (PandiO/knk-workspace#354). Phase 2 (#783): the weapon spot task and
 * {@code /knk weaponspot}. With {@code siege.weapons.enabled} off (the default) nothing is registered.
 */
public final class SiegeWeaponsModule {

    private SiegeWeaponsModule() {
    }

    /**
     * @param busy why a player can't start a spot task now (an open world task or gate capture), or empty
     * @return the spot task, or null when the feature is off
     */
    public static WeaponSpotTask register(JavaPlugin plugin, SiegeWeaponsSettings settings, KnkAdminCommand knkAdminCommand,
                                          SiegeWeaponSpotsApi api, Function<Player, Optional<String>> busy) {
        if (settings == null || !settings.enabled() || knkAdminCommand == null || api == null) {
            return null;
        }
        Executor mainThread = runnable -> plugin.getServer().getScheduler().runTask(plugin, runnable);
        WeaponSpotTask task = new WeaponSpotTask(api, new WorldEditSpotSelections(), BukkitBlockProbe::new, mainThread);
        WeaponSpotCommand command = new WeaponSpotCommand(api, task, busy, mainThread);
        knkAdminCommand.registerSubcommand(WeaponSpotCommand.metadata(), command::execute, command::complete);
        plugin.getServer().getPluginManager().registerEvents(
                new WeaponSpotChatListener(task, WorldTaskChatListener.MainThread.bukkit(plugin)), plugin);
        plugin.getLogger().info("Siege weapons are on (" + SiegeWeaponsSettings.ENABLED_PATH + "): /knk weaponspot is registered.");
        return task;
    }
}
