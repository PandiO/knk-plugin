package net.knightsandkings.knk.paper.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.knightsandkings.knk.api.dto.LinkCodeResponseDto;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.ports.api.UserAccountApi;
import net.knightsandkings.knk.core.teleport.TeleportSettings;
import net.knightsandkings.knk.paper.KnKPlugin;
import net.knightsandkings.knk.paper.chat.ChatCaptureManager;
import net.knightsandkings.knk.paper.config.KnkConfig;
import net.knightsandkings.knk.paper.user.PlayerUserData;
import net.knightsandkings.knk.paper.user.UserManager;
import net.knightsandkings.knk.paper.utils.CommandCooldownManager;

/** WP10: /account link never logs a link code, and tells the player where to use it. */
class AccountLinkCommandTest {
    private static final String CODE = "QX7-K2P";

    private final UUID uuid = UUID.randomUUID();
    private final KnKPlugin plugin = mock(KnKPlugin.class);
    private final UserManager userManager = mock(UserManager.class);
    private final UserAccountApi api = mock(UserAccountApi.class);
    private final CommandCooldownManager cooldowns = mock(CommandCooldownManager.class);
    private final Player player = mock(Player.class);
    private final List<String> sent = new ArrayList<>();
    private final List<String> logged = new ArrayList<>();
    private final Logger logger = Logger.getLogger("AccountLinkCommandTest-" + UUID.randomUUID());
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() {
        bukkit = mockStatic(Bukkit.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return null;
        });

        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        when(plugin.getLogger()).thenReturn(logger);

        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("Steve");
        org.mockito.Mockito.doAnswer(inv -> sent.add(ChatColor.stripColor(inv.getArgument(0, String.class))))
            .when(player).sendMessage(anyString());
        when(userManager.getCachedUser(uuid)).thenReturn(new PlayerUserData(
            7, "Steve", uuid, null, 0, 0, 0, false, false, null, GatePassThroughMethod.DEFAULT));
        when(cooldowns.canExecute(any(), anyString(), anyInt())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    private AccountLinkCommand command(String publicUrl) {
        return new AccountLinkCommand(plugin, userManager, mock(ChatCaptureManager.class), api, config(publicUrl), cooldowns);
    }

    static KnkConfig config(String publicUrl) {
        KnkConfig.AuthConfig auth = new KnkConfig.AuthConfig("none", "", "", "X-API-Key");
        KnkConfig.ApiConfig api = new KnkConfig.ApiConfig("http://localhost", false, false, auth,
            new KnkConfig.TimeoutsConfig(1, 1, 1));
        KnkConfig.AccountConfig account = new KnkConfig.AccountConfig(20, 120,
            new KnkConfig.AccountConfig.CooldownsConfig(300, 60, 10, 5));
        KnkConfig.MessagesConfig messages = new KnkConfig.MessagesConfig(
            "[KnK] ", "created", "&aYour accounts have been linked!", LinkCodeMessage.DEFAULT_TEMPLATE,
            "&cThis code is invalid or has expired.", "duplicate", "merged");
        return new KnkConfig(api, KnkConfig.CacheConfig.defaultConfig(), account, messages,
            KnkConfig.PrivateMessagesConfig.defaults(), TeleportSettings.defaults(), KnkConfig.DiscoveryConfig.defaults(),
            KnkConfig.RegionHttpConfig.defaults(), new KnkConfig.WebConfig(publicUrl));
    }

    private void apiGenerates() {
        when(api.generateLinkCode(7)).thenReturn(CompletableFuture.completedFuture(
            new LinkCodeResponseDto("QX7K2P", "2026-10-08T12:00:00Z", CODE)));
    }

    @Test
    void theGeneratedCodeIsShownWithTheRegisterLinkButNeverLogged() {
        apiGenerates();

        command("https://app.knightsandkings.net").onCommand(player, null, "link", new String[0]);

        assertEquals(List.of(
            "[KnK] Your link code is " + CODE + ". It expires in 20 minutes.",
            "[KnK] New to the website? Register at https://app.knightsandkings.net/auth/register",
            "[KnK] Enter the code when you register on the website, or on your Account page if you already have a web login."
        ), sent);
        assertTrue(logged.stream().anyMatch(line -> line.contains("Link code generated for Steve")), logged.toString());
        logged.forEach(line -> assertFalse(line.contains(CODE) || line.contains("QX7K2P"), "logged the code: " + line));
    }

    @Test
    void withoutAPublicUrlTheLinkIsLeftOut() {
        apiGenerates();

        command("").onCommand(player, null, "link", new String[0]);

        assertEquals(2, sent.size(), sent.toString());
        sent.forEach(line -> assertFalse(line.contains("/auth/register"), line));
    }
}
