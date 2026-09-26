package net.knightsandkings.knk.paper.bootstrap;

import net.knightsandkings.knk.api.impl.enchantment.LocalEnchantmentRepositoryImpl;
import net.knightsandkings.knk.core.ports.enchantment.CooldownManager;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentExecutor;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentRepository;
import net.knightsandkings.knk.paper.commands.enchantment.EnchantmentCommandHandler;
import net.knightsandkings.knk.paper.config.EnchantmentConfigManager;
import net.knightsandkings.knk.paper.enchantbook.EnchantBooks;
import net.knightsandkings.knk.paper.enchantment.ExecutorImpl;
import net.knightsandkings.knk.paper.enchantment.FrozenPlayerTracker;
import net.knightsandkings.knk.paper.enchantment.InMemoryCooldownManager;
import net.knightsandkings.knk.paper.listeners.EnchantBookListener;
import net.knightsandkings.knk.paper.listeners.EnchantmentCombatListener;
import net.knightsandkings.knk.paper.listeners.EnchantmentEnchantTableListener;
import net.knightsandkings.knk.paper.listeners.EnchantmentInteractListener;
import net.knightsandkings.knk.paper.listeners.FreezeMovementListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;

public class EnchantmentBootstrap {
        private final Plugin plugin;

        public EnchantmentBootstrap(Plugin plugin) {
        this.plugin = plugin;
    }

    public EnchantmentRuntime initialize() {
        EnchantmentConfigManager configManager = new EnchantmentConfigManager(plugin);
        EnchantmentRepository enchantmentRepository = new LocalEnchantmentRepositoryImpl();
        CooldownManager cooldownManager = new InMemoryCooldownManager();
        FrozenPlayerTracker frozenPlayerTracker = new FrozenPlayerTracker(plugin);
        EnchantmentExecutor enchantmentExecutor = new ExecutorImpl(plugin, cooldownManager, frozenPlayerTracker);
        EnchantmentCommandHandler commandHandler = new EnchantmentCommandHandler(
                plugin,
                configManager,
                enchantmentRepository,
                cooldownManager
        );

        var pluginManager = plugin.getServer().getPluginManager();
        pluginManager.registerEvents(
                new EnchantmentCombatListener(
                        enchantmentRepository,
                        enchantmentExecutor,
                        configManager.disableForCreative()
                ),
                plugin
        );
        pluginManager.registerEvents(
                new EnchantmentInteractListener(
                        enchantmentRepository,
                        enchantmentExecutor,
                        cooldownManager,
                        configManager.disableForCreative(),
                        configManager.cooldownMessageTemplate()
                ),
                plugin
        );
        pluginManager.registerEvents(new EnchantmentEnchantTableListener(enchantmentRepository), plugin);
        pluginManager.registerEvents(new FreezeMovementListener(frozenPlayerTracker), plugin);
        // Permanent enchantment books (KNG-5).
        pluginManager.registerEvents(new EnchantBookListener(new EnchantBooks(enchantmentRepository)), plugin);

                PluginCommand enchantmentCommand = plugin.getServer().getPluginCommand("ce");
        if (enchantmentCommand == null) {
            plugin.getLogger().warning("Failed to register /ce command - not defined in plugin.yml?");
        } else {
            enchantmentCommand.setExecutor(commandHandler);
            enchantmentCommand.setTabCompleter(commandHandler);
        }

        return new EnchantmentRuntime(
                configManager,
                enchantmentRepository,
                cooldownManager,
                enchantmentExecutor,
                frozenPlayerTracker,
                commandHandler
        );
    }

    public record EnchantmentRuntime(
            EnchantmentConfigManager configManager,
            EnchantmentRepository enchantmentRepository,
            CooldownManager cooldownManager,
            EnchantmentExecutor enchantmentExecutor,
            FrozenPlayerTracker frozenPlayerTracker,
            EnchantmentCommandHandler commandHandler
    ) {
    }
}