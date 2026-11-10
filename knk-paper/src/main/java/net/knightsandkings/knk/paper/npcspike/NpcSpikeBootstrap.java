package net.knightsandkings.knk.paper.npcspike;

import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.paper.commands.KnkAdminCommand;
import net.knightsandkings.knk.paper.navigation.NavigationService;

/**
 * #243 spike (throwaway, never merged): the only spike class {@code KnKPlugin} names. Its signature uses no
 * Citizens type, so the plugin loads without Citizens; {@link #register} is only called when Citizens is enabled.
 * The commands are registered before navigation starts, so the spike is built on first use.
 */
public final class NpcSpikeBootstrap {

    private NpcSpikeBootstrap() {
    }

    /** Registers {@code /knk npcspike}; returns the shutdown hook for {@code onDisable}. */
    public static Runnable register(Plugin plugin, KnkAdminCommand knk, Supplier<NavigationService> navigation,
                                    IntSupplier captureMargin) {
        NpcSpikeCommand[] command = new NpcSpikeCommand[1];
        CitizensSpike[] spike = new CitizensSpike[1];
        knk.registerSubcommand(NpcSpikeCommand.metadata(), (sender, args) -> {
            if (command[0] == null) {
                NavigationService nav = navigation.get();
                if (nav == null) {
                    sender.sendMessage("Navigation is not running (navigation.enabled / road cache): no spike.");
                    return true;
                }
                spike[0] = new CitizensSpike(plugin, nav, captureMargin.getAsInt());
                command[0] = new NpcSpikeCommand(spike[0]);
            }
            return command[0].execute(sender, args);
        }, (sender, args) -> command[0] == null ? List.of() : command[0].complete(sender, args));
        plugin.getLogger().info("[NpcSpike] /knk npcspike registered (Citizens spike, #243)");
        return () -> {
            if (spike[0] != null) {
                spike[0].shutdown();
            }
        };
    }
}
