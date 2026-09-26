package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.ports.api.UserAccountApi;
import net.knightsandkings.knk.paper.KnKPlugin;
import net.knightsandkings.knk.paper.chat.ChatCaptureManager;
import net.knightsandkings.knk.paper.config.KnkConfig;
import net.knightsandkings.knk.paper.config.KnkConfig.EntityCacheSettings;
import net.knightsandkings.knk.paper.user.PlayerUserData;
import net.knightsandkings.knk.paper.user.UserManager;
import net.knightsandkings.knk.paper.utils.CommandCooldownManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

@Tag("requires-bukkit")
class AccountCommandRegistryTest {
    private KnkConfig config;
    private KnKPlugin plugin;
    private UserManager userManager;
    private ChatCaptureManager chatCaptureManager;
    private UserAccountApi userAccountApi;
    private CommandCooldownManager cooldownManager;

    @BeforeEach
    void setUp() {
        config = buildConfig();
        plugin = mock(KnKPlugin.class);
        userManager = mock(UserManager.class);
        chatCaptureManager = mock(ChatCaptureManager.class);
        userAccountApi = mock(UserAccountApi.class);
        cooldownManager = mock(CommandCooldownManager.class);
        
        // Mock cooldown manager to always allow execution
        when(cooldownManager.canExecute(any(), anyString(), anyInt())).thenReturn(true);
    }

    @Test
    void defaultsToStatusWhenNoArgs() {
        AccountCommandRegistry registry = new AccountCommandRegistry(
            plugin,
            userManager,
            chatCaptureManager,
            userAccountApi,
            config,
            cooldownManager
        );

        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("TestPlayer");
        when(player.hasPermission("knk.account.use")).thenReturn(true);

        PlayerUserData userData = new PlayerUserData(
            1,
            "TestPlayer",
            uuid,
            "test@example.com",
            10,
            5,
            100,
            true,
            false,
            null,
            GatePassThroughMethod.DEFAULT
        );
        when(userManager.getCachedUser(uuid)).thenReturn(userData);

        registry.onCommand(player, null, "account", new String[0]);

        verify(userManager).getCachedUser(uuid);
        verify(player, atLeastOnce()).sendMessage(anyString());
    }

    @Test
    void deniesCreateWhenPermissionMissing() {
        AccountCommandRegistry registry = new AccountCommandRegistry(
            plugin,
            userManager,
            chatCaptureManager,
            userAccountApi,
            config,
            cooldownManager
        );

        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("knk.account.create")).thenReturn(false);

        registry.onCommand(sender, null, "account", new String[] { "create" });

        verify(sender).sendMessage(contains("permission"));
    }

    @Test
    void showsUsageForUnknownSubcommand() {
        AccountCommandRegistry registry = new AccountCommandRegistry(
            plugin,
            userManager,
            chatCaptureManager,
            userAccountApi,
            config,
            cooldownManager
        );

        CommandSender sender = mock(CommandSender.class);

        registry.onCommand(sender, null, "account", new String[] { "unknown" });

        verify(sender, atLeastOnce()).sendMessage(contains("Usage"));
    }

    private KnkConfig buildConfig() {
        KnkConfig.AuthConfig auth = new KnkConfig.AuthConfig("none", "", "", "X-API-Key");
        KnkConfig.TimeoutsConfig timeouts = new KnkConfig.TimeoutsConfig(1, 1, 1);
        KnkConfig.ApiConfig api = new KnkConfig.ApiConfig("http://localhost", false, false, auth, timeouts);
        KnkConfig.CacheConfig cache = new KnkConfig.CacheConfig(60, EntityCacheSettings.defaults());
        KnkConfig.AccountConfig.CooldownsConfig cooldowns = new KnkConfig.AccountConfig.CooldownsConfig(300, 60, 10, 5);
        KnkConfig.AccountConfig account = new KnkConfig.AccountConfig(20, 120, cooldowns);
        KnkConfig.MessagesConfig messages = new KnkConfig.MessagesConfig(
            "&8[&6KnK&8] &r",
            "&aAccount created successfully!",
            "&aYour accounts have been linked!",
            "&aYour link code is: &6{code}&a.",
            "&cThis code is invalid or has expired.",
            "&cYou have two accounts. Please choose which one to keep.",
            "&aAccount merge complete. Your account now has {coins} coins, {gems} gems, and {exp} XP."
        );

        return new KnkConfig(api, cache, account, messages, KnkConfig.PrivateMessagesConfig.defaults());
    }
}