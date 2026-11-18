package net.knightsandkings.knk.paper.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.knightsandkings.knk.api.dto.LinkCodeResponseDto;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.ports.api.UserAccountApi;
import net.knightsandkings.knk.core.teleport.TeleportSettings;
import net.knightsandkings.knk.paper.chat.ChatCaptureManager;
import net.knightsandkings.knk.paper.config.KnkConfig;
import net.knightsandkings.knk.paper.user.PlayerUserData;
import net.knightsandkings.knk.paper.user.UserManager;
import net.knightsandkings.knk.paper.utils.CommandCooldownManager;

/** WP10: /account link never logs a link code, and tells the player where to use it. */
class AccountLinkCommandTest {
    private static final String CODE = "QX7-K2P";

    private final UUID uuid = UUID.randomUUID();
    // Bukkit's Plugin interface: Mockito's inline mock maker can't redefine the KnKPlugin class.
    private final Plugin plugin = mock(Plugin.class);
    private final UserManager userManager = mock(UserManager.class);
    private final UserAccountApi api = mock(UserAccountApi.class);
    private final CommandCooldownManager cooldowns = mock(CommandCooldownManager.class);
    private final Player player = mock(Player.class);
    private final List<String> sent = new ArrayList<>();
    private final List<Component> components = new ArrayList<>();
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
        org.mockito.Mockito.doAnswer(inv -> {
            Component message = inv.getArgument(0, Component.class);
            components.add(message);
            return sent.add(PlainTextComponentSerializer.plainText().serialize(message));
        }).when(player).sendMessage(any(Component.class));
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
        return config(publicUrl, LinkCodeMessage.DEFAULT_TEMPLATE);
    }

    static KnkConfig config(String publicUrl, String linkCodeTemplate) {
        KnkConfig.AuthConfig auth = new KnkConfig.AuthConfig("none", "", "", "X-API-Key");
        KnkConfig.ApiConfig api = new KnkConfig.ApiConfig("http://localhost", false, false, auth,
            new KnkConfig.TimeoutsConfig(1, 1, 1));
        KnkConfig.AccountConfig account = new KnkConfig.AccountConfig(20, 120,
            new KnkConfig.AccountConfig.CooldownsConfig(300, 60, 10, 5));
        KnkConfig.MessagesConfig messages = new KnkConfig.MessagesConfig(
            "[KnK] ", "created", "&aYour accounts have been linked!", linkCodeTemplate,
            "&cThis code is invalid or has expired.", "duplicate", "merged");
        return new KnkConfig(api, KnkConfig.CacheConfig.defaultConfig(), account, messages,
            KnkConfig.PrivateMessagesConfig.defaults(), TeleportSettings.defaults(), KnkConfig.DiscoveryConfig.defaults(),
            net.knightsandkings.knk.paper.config.NavigationConfig.defaults(), KnkConfig.StatisticsConfig.defaults(), KnkConfig.TelemetryConfig.defaults(),
            KnkConfig.WorldAnalyticsConfig.defaults(), KnkConfig.RegionHttpConfig.defaults(), new KnkConfig.WebConfig(publicUrl));
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
            "[KnK] Enter the code when you register on the website, or on your Account page if you already have a web login.",
            "[KnK] Never give this code to anyone; staff never ask for it."
        ), sent);
        assertTrue(logged.stream().anyMatch(line -> line.contains("Link code generated for Steve")), logged.toString());
        logged.forEach(line -> assertFalse(line.contains(CODE) || line.contains("QX7K2P"), "logged the code: " + line));
    }

    @Test
    void theCodeLineCopiesTheCodeAndTheRegisterLineOpensTheWebsite() {
        apiGenerates();

        command("https://app.knightsandkings.net").onCommand(player, null, "link", new String[0]);

        assertEquals(ClickEvent.copyToClipboard(CODE), components.get(0).clickEvent());
        assertEquals(ClickEvent.openUrl("https://app.knightsandkings.net/auth/register"), components.get(1).clickEvent());
        assertNull(components.get(2).clickEvent());
        assertNull(components.get(3).clickEvent());
    }

    @Test
    void anOlderTemplateWithoutTheUrlStillGetsTheRegisterLine() {
        apiGenerates();

        new AccountLinkCommand(plugin, userManager, mock(ChatCaptureManager.class), api,
            config("https://app.knightsandkings.net", "&aYour link code is: &6{code}&a. It expires in {minutes} minutes."),
            cooldowns).onCommand(player, null, "link", new String[0]);

        assertEquals(List.of(
            "[KnK] Your link code is: " + CODE + ". It expires in 20 minutes.",
            "[KnK] New to the website? Register at https://app.knightsandkings.net/auth/register"
        ), sent);
    }

    @Test
    void withoutAPublicUrlTheLinkIsLeftOut() {
        apiGenerates();

        command("").onCommand(player, null, "link", new String[0]);

        assertEquals(3, sent.size(), sent.toString());
        sent.forEach(line -> assertFalse(line.contains("/auth/register"), line));
        assertTrue(sent.get(2).contains("Never give this code to anyone"), sent.toString());
    }

    @Test
    void aCodeTypedInGameIsExplainedNotValidated() {
        command("https://app.knightsandkings.net").onCommand(player, null, "link", new String[] {CODE});

        assertEquals(List.of(
            "[KnK] Link codes are entered on the website, not in game.",
            "[KnK] Type /account link (without a code) to get yours.",
            "[KnK] Then enter it at https://app.knightsandkings.net/auth/register, or on your Account page if you"
                + " already have a web login."
        ), sent);
        assertNothingDoneWithTheCode();
    }

    @Test
    void aCodeTypedInGameWithoutAPublicUrlLeavesTheLinkOut() {
        command("").onCommand(player, null, "link", new String[] {CODE});

        assertEquals(List.of(
            "[KnK] Link codes are entered on the website, not in game.",
            "[KnK] Type /account link (without a code) to get yours."
        ), sent);
        assertNothingDoneWithTheCode();
    }

    private void assertNothingDoneWithTheCode() {
        verify(api, never()).validateLinkCode(anyString());
        verifyNoInteractions(cooldowns);
        verify(userManager, never()).updateCachedUser(any(), any());
        assertTrue(logged.stream().anyMatch(line -> line.contains("Steve tried to enter a link code in game")),
            logged.toString());
        logged.forEach(line -> assertFalse(line.contains(CODE), "logged the code: " + line));
    }
}
