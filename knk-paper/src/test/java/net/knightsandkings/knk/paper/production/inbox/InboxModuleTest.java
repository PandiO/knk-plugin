package net.knightsandkings.knk.paper.production.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.knightsandkings.knk.core.domain.users.PlayerNotification;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.ItemDeliveryApi;
import net.knightsandkings.knk.core.production.inbox.ClaimResult;
import net.knightsandkings.knk.core.production.inbox.DeliveryRequest;
import net.knightsandkings.knk.core.production.inbox.DeliveryResult;
import net.knightsandkings.knk.core.production.inbox.InboxLayout;
import net.knightsandkings.knk.core.production.inbox.InboxNotice;
import net.knightsandkings.knk.core.production.inbox.InboxSettings;
import net.knightsandkings.knk.core.production.inbox.InboxText;
import net.knightsandkings.knk.core.production.inbox.InboxView;
import net.knightsandkings.knk.core.production.terminal.TerminalLayout;
import net.knightsandkings.knk.paper.commands.InboxCommand;
import net.knightsandkings.knk.paper.production.terminal.BlueprintItems;

/**
 * PandiO/knk-workspace#684 (A15): {@code /inbox} shows the inbox; a claim asks for what fits (partial space) with one key
 * per click and places exactly what the API accepted; a full inventory, a unique item and a siege are refused without a
 * call; the notices show the loss, the warning and reminders; the join summary; the command while the flag is off.
 */
class InboxModuleTest {

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    private ServerMock server;
    private PlayerMock player;
    private Plugin plugin;
    private InboxModule module;
    private RewardInbox rewards;
    private final List<String> calls = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();
    private final Set<UUID> inSiege = new HashSet<>();
    private long accepts = Long.MAX_VALUE;
    private CompletableFuture<ClaimResult> nextClaim;

    private InboxView view = new InboxView(3, 40, 101, 2304, 4, null, null, false, List.of(
            new InboxView.Entry(11, "Bread", null, null, 100, NOW.plus(Duration.ofDays(3)).plusSeconds(5), 40),
            new InboxView.Entry(12, "Sword", 900L, "Excalibur", 1, NOW.plus(Duration.ofDays(20)), 1)));

    private final ItemDeliveryApi api = new ItemDeliveryApi() {
        public CompletableFuture<DeliveryResult> deliver(DeliveryRequest request) {
            throw new UnsupportedOperationException();
        }

        public CompletableFuture<InboxView> inbox(int userId) {
            calls.add("inbox " + userId);
            return CompletableFuture.completedFuture(view);
        }

        public CompletableFuture<ClaimResult> claim(int userId, String key, List<DeliveryRequest.Line> lines) {
            DeliveryRequest.Line line = lines.get(0);
            calls.add("claim " + userId + " " + line.itemBlueprintId() + " x" + line.amount());
            keys.add(key);
            if (nextClaim != null) {
                return nextClaim;
            }
            long accepted = Math.min(line.amount(), accepts);
            return CompletableFuture.completedFuture(new ClaimResult(accepted < line.amount() ? "PartiallyApplied" : "Applied",
                    false, accepted, accepted < line.amount() ? List.of("NotEnoughStock") : List.of(), view));
        }
    };

    /** Bread for every blueprint, without the API. */
    private final BlueprintItems items = new BlueprintItems(null, null) {
        @Override
        public Template cached(int blueprintId) {
            return new Template(new ItemStack(Material.BREAD), 64, true);
        }

        @Override
        public CompletableFuture<Template> template(int blueprintId, String fallbackName) {
            return CompletableFuture.completedFuture(cached(blueprintId));
        }
    };

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        player = server.addPlayer();
        plugin = MockBukkit.createMockPlugin();
        rewards = new RewardInbox(api, Runnable::run, () -> null);
        module = new InboxModule(plugin, new InboxSettings(true, true), api, items, uuid -> 3, inSiege::contains, rewards,
                Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC));
        module.start();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private InboxScreen open() {
        InboxScreen screen = module.open(player).join();
        assertInstanceOf(InboxScreen.class, player.getOpenInventory().getTopInventory().getHolder());
        return screen;
    }

    /** All slots full of dirt except one with {@code bread} bread (room for 64 - bread). */
    private void fillLeaving(int bread) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            player.getInventory().setItem(slot, new ItemStack(Material.DIRT, 64));
        }
        player.getInventory().setItem(0, new ItemStack(Material.BREAD, bread));
    }

    private int bread() {
        return player.getInventory().all(Material.BREAD).values().stream().mapToInt(ItemStack::getAmount).sum();
    }

    @Test
    void open_showsTheEntries_theExpiry_andTheInfo() {
        InboxScreen screen = open();

        assertEquals(List.of("inbox 3"), calls);
        ItemStack bread = screen.getInventory().getItem(0);
        assertEquals(Material.BREAD, bread.getType());
        assertEquals(64, bread.getAmount());
        assertTrue(bread.getItemMeta().getLore().stream().anyMatch(l -> l.contains("40 expire in 3 days")), bread.getItemMeta().getLore().toString());
        ItemStack sword = screen.getInventory().getItem(1);
        assertTrue(sword.getItemMeta().getLore().stream().anyMatch(l -> l.contains("can't be claimed in game yet")));
        assertTrue(screen.getInventory().getItem(InboxLayout.INFO_SLOT).getItemMeta().getLore().get(0).contains("101 of 2,304"));
        assertNull(screen.getInventory().getItem(InboxLayout.NEXT_SLOT));
    }

    @Test
    void claim_withPartialSpace_asksForWhatFits_andPlacesIt() {
        fillLeaving(54);
        InboxScreen screen = open();

        long claimed = module.claim(player, screen, screen.entryAt(0), TerminalLayout.Click.SHIFT).join();

        assertEquals(10, claimed);
        assertTrue(calls.contains("claim 3 11 x10"), calls.toString());
        assertEquals(64, bread());
        assertTrue(player.nextMessage().contains("Claimed"));
    }

    @Test
    void claim_placesExactlyWhatTheApiAccepted_withOneKeyPerClick() {
        InboxScreen screen = open();
        accepts = 5;

        module.claim(player, screen, screen.entryAt(0), TerminalLayout.Click.LEFT).join();
        module.claim(player, screen, screen.entryAt(0), TerminalLayout.Click.RIGHT).join();

        assertTrue(calls.contains("claim 3 11 x64"));
        assertTrue(calls.contains("claim 3 11 x1"));
        assertEquals(6, bread());
        assertEquals(2, keys.stream().distinct().count());
    }

    @Test
    void claim_withAFullInventory_isRefusedWithoutACall() {
        fillLeaving(64);
        InboxScreen screen = open();
        player.nextMessage();

        assertEquals(0L, module.claim(player, screen, screen.entryAt(0), TerminalLayout.Click.SHIFT).join());
        assertFalse(calls.stream().anyMatch(c -> c.startsWith("claim")));
        assertEquals("§cYour inventory is full. Make room, then claim again.", player.nextMessage());
    }

    @Test
    void claim_ofAUniqueItem_isRefusedInGame() {
        InboxScreen screen = open();

        module.claim(player, screen, screen.entryAt(1), TerminalLayout.Click.LEFT).join();

        assertFalse(calls.stream().anyMatch(c -> c.startsWith("claim")));
        assertTrue(player.nextMessage().contains("Unique items can't be claimed in game yet"));
    }

    @Test
    void claim_whileTheApiIsSwitchedOff_takesNothing() {
        InboxScreen screen = open();
        nextClaim = CompletableFuture.failedFuture(new CompletionException(new ApiException("x", 409, "Conflict",
                "{\"code\":\"ProductionDisabled\"}")));

        module.claim(player, screen, screen.entryAt(0), TerminalLayout.Click.LEFT).join();

        assertEquals(0, bread());
        assertEquals("§cThe reward inbox is switched off on the server right now.", player.nextMessage());
    }

    @Test
    void duringASiege_theInboxDoesNotOpen() {
        inSiege.add(player.getUniqueId());

        assertNull(module.open(player).join());
        assertTrue(calls.isEmpty());
        assertTrue(player.nextMessage().contains("during a siege"));
    }

    @Test
    void open_nearlyFull_warns() {
        view = new InboxView(3, 40, 2200, 2304, 95, 95, null, false, view.entries());

        open();

        assertEquals("§6" + InboxText.warning(95), player.nextMessage());
    }

    private PlayerNotification notification(InboxNotice notice) {
        return new PlayerNotification(1, 3, player.getUniqueId().toString(), player.getName(),
                PlayerNotification.TYPE_INBOX_UPDATED, null, null, null, null, null, null, notice);
    }

    @Test
    void notices_lossWarningReminderExpiry() {
        module.onNotice(player, notification(new InboxNotice("Delivered", 9L, "Gift", 4, 2, 80, 81, 2304, 0, null)));
        assertEquals("§cYour reward inbox is full: 2 items were lost.", player.nextMessage());
        assertEquals("§a4 items arrived in your reward inbox (/inbox).", player.nextMessage());
        assertEquals("§6" + InboxText.warning(80), player.nextMessage());

        module.onNotice(player, notification(new InboxNotice("ExpiryReminder", null, null, 0, 0, null, 0, 2304, 12,
                NOW.plus(Duration.ofDays(1)).plusSeconds(5))));
        assertEquals("§612 items in your reward inbox expire in 1 day. Claim them with /inbox.", player.nextMessage());

        module.onNotice(player, notification(new InboxNotice("Expired", null, null, 0, 3, null, 0, 2304, 0, null)));
        assertEquals("§c3 items in your reward inbox expired and are gone.", player.nextMessage());
    }

    @Test
    void aDeliveryThisServerAlreadyTold_onlyAddsItsWarning() {
        RewardInbox told = new RewardInbox(new ItemDeliveryApi() {
            public CompletableFuture<DeliveryResult> deliver(DeliveryRequest request) {
                return CompletableFuture.completedFuture(new DeliveryResult(9, "Inboxed", 4, 0, 2304, null, false));
            }

            public CompletableFuture<InboxView> inbox(int userId) {
                return api.inbox(userId);
            }

            public CompletableFuture<ClaimResult> claim(int userId, String key, List<DeliveryRequest.Line> lines) {
                return api.claim(userId, key, lines);
            }
        }, Runnable::run, () -> null);
        told.send(null, 3, DeliveryRequest.Reason.KIT, "Kit", "Starter", "k",
                List.of(new RewardInbox.Leftover(new ItemStack(Material.BREAD, 4), 11)), s -> { }).join();
        InboxModule withTold = new InboxModule(plugin, new InboxSettings(true, true), api, items, uuid -> 3, id -> false, told,
                Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC));

        withTold.onNotice(player, notification(new InboxNotice("Delivered", 9L, "Kit", 4, 0, 80, 81, 2304, 0, null)));

        assertEquals("§6" + InboxText.warning(80), player.nextMessage());
        assertNull(player.nextMessage());
    }

    @Test
    void joinSummary_saysWhatWaits() {
        module.joinSummary(player).join();

        assertEquals("§aYou have 101 items in your reward inbox (/inbox).", player.nextMessage());
        assertEquals("§6Some of them expire in 3 days.", player.nextMessage());
    }

    @Test
    void theCommand_withTheFlagOff_saysTheInboxIsOff() {
        new InboxCommand(() -> null).onCommand(player, null, "inbox", new String[0]);
        assertEquals("§7" + InboxCommand.OFF, player.nextMessage());

        new InboxCommand(() -> module).onCommand(player, null, "inbox", new String[0]);
        assertInstanceOf(InboxScreen.class, player.getOpenInventory().getTopInventory().getHolder());
    }

    @Test
    void config_bundledDefaults_offWithJoinSummary() throws Exception {
        try (var reader = new InputStreamReader(getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
            InboxSettings settings = InboxConfig.read(YamlConfiguration.loadConfiguration(reader));
            assertFalse(settings.enabled(), "production.enabled is off by default (A21)");
            assertTrue(settings.joinSummary());
        }
        YamlConfiguration on = new YamlConfiguration();
        on.set("production.enabled", true);
        on.set("production.inbox.join-summary", false);
        InboxSettings settings = InboxConfig.read(on);
        assertTrue(settings.enabled());
        assertFalse(settings.joinSummary());
        assertFalse(InboxConfig.read(null).enabled());
    }
}
