package net.knightsandkings.knk.paper.bootstrap;

import net.knightsandkings.knk.api.impl.enchantment.LocalEnchantmentRepositoryImpl;
import net.knightsandkings.knk.core.domain.item.GradeCatalog;
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
import net.knightsandkings.knk.paper.regions.CombatSafezoneCheck;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.ThreadLocalRandom;

public class EnchantmentBootstrap {
        private final Plugin plugin;
        private final CombatSafezoneCheck safezones;

        public EnchantmentBootstrap(Plugin plugin) {
        this(plugin, CombatSafezoneCheck.NONE);
    }

    /** @param safezones Town/District combat safezones for enchantment effects and abilities (KNG-11) */
    public EnchantmentBootstrap(Plugin plugin, CombatSafezoneCheck safezones) {
        this.plugin = plugin;
        this.safezones = safezones != null ? safezones : CombatSafezoneCheck.NONE;
    }

    public EnchantmentRuntime initialize() {
        EnchantmentConfigManager configManager = new EnchantmentConfigManager(plugin);
        EnchantmentRepository enchantmentRepository = new LocalEnchantmentRepositoryImpl();
        CooldownManager cooldownManager = new InMemoryCooldownManager();
        FrozenPlayerTracker frozenPlayerTracker = new FrozenPlayerTracker(plugin);
        EnchantmentExecutor enchantmentExecutor = new ExecutorImpl(plugin, cooldownManager, frozenPlayerTracker, safezones);
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
                        configManager.disableForCreative(),
                        safezones
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
        // Permanent enchantment books (KNG-5), capped by the target's grade (KNG-6).
        EnchantBooks enchantBooks = new EnchantBooks(
                enchantmentRepository,
                configManager.enchantBookCapSettings(),
                GradeCatalog.getInstance(),
                () -> ThreadLocalRandom.current().nextDouble()
        );
        pluginManager.registerEvents(new EnchantBookListener(enchantBooks, plugin), plugin);

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