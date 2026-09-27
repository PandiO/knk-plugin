package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.lootbox.ActiveLootboxCache;
import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import net.knightsandkings.knk.core.ports.api.LootboxesCommandApi;
import net.knightsandkings.knk.paper.lootbox.LootboxAnnouncer;
import net.knightsandkings.knk.paper.lootbox.LootboxDelivery;
import net.knightsandkings.knk.paper.lootbox.LootboxRuntime;
import net.knightsandkings.knk.paper.lootbox.LootboxSettings;
import net.knightsandkings.knk.paper.lootbox.LootboxTokenDelivery;
import net.knightsandkings.knk.core.lootbox.KnkLootboxToken;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Lootboxes Phase 3: {@code /knk lootbox} gates every action on its own node and parses its arguments. */
class LootboxAdminCommandTest {

    private final LootboxRuntime runtime = mock(LootboxRuntime.class);
    private final LootboxesCommandApi api = mock(LootboxesCommandApi.class);
    private final LootboxDelivery delivery = mock(LootboxDelivery.class);
    private final LootboxAreaCommand area = mock(LootboxAreaCommand.class);
    private final Player admin = mock(Player.class);
    private final Player steve = mock(Player.class);
    private final List<String> granted = new ArrayList<>();
    private int reloads;
    private static final java.util.UUID STEVE_ID = java.util.UUID.fromString("00000000-0000-0000-0000-00000000057e");
    private boolean steveInSiege;
    private LootboxAdminCommand command;

    @BeforeEach
    void setUp() {
        when(runtime.config()).thenReturn(LootboxCommandTest.CONFIG);
        when(runtime.cache()).thenReturn(new ActiveLootboxCache());
        when(runtime.settings()).thenReturn(LootboxSettings.defaults());
        when(runtime.clock()).thenReturn(Clock.systemUTC());
        when(steve.getName()).thenReturn("Steve");
        when(steve.getUniqueId()).thenReturn(STEVE_ID);
        command = new LootboxAdminCommand(runtime, api, delivery, mock(LootboxAnnouncer.class), area,
                (player, node) -> granted.contains(node), p -> p == steve ? 9 : 42,
                name -> "Steve".equalsIgnoreCase(name) ? steve : null, () -> reloads++, Runnable::run);
    }

    @Test
    void everyActionNeedsItsOwnNode() {
        command.execute(admin, new String[]{"give", "Steve", "weapons"});
        command.execute(admin, new String[]{"area", "list"});
        command.execute(admin, new String[]{"reload"});

        verify(api, never()).adminGive(any(), anyInt(), anyInt(), any(), any());
        verify(area, never()).execute(any(), any());
        assertEquals(0, reloads);

        granted.add("knk.lootbox.admin.area");
        command.execute(admin, new String[]{"area", "list"});
        verify(area).execute(eq(admin), eq(new String[]{"list"}));
    }

    @Test
    void console_passesTheNodeCheck() {
        CommandSender console = mock(CommandSender.class);
        command.execute(console, new String[]{"reload"});
        assertEquals(1, reloads);
        assertTrue(command.allowed(console, "give"));
        assertFalse(command.allowed(admin, "give"));
    }

    @Test
    void give_parsesPlayerCategoryAndStars_andNamesTheStaffMember() {
        granted.add("knk.lootbox.admin.give");
        when(api.adminGive(eq(42), eq(9), eq(3), eq(4), anyString())).thenReturn(new CompletableFuture<KnkLootboxClaimResult>());

        command.execute(admin, new String[]{"give", "steve", "Weapons", "4"});
        command.execute(admin, new String[]{"give", "steve", "Weapons", "4"});

        // Each command carries its own idempotency key: a resent request replays that give, a second command gives again.
        org.mockito.ArgumentCaptor<String> keys = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(api, org.mockito.Mockito.times(2)).adminGive(eq(42), eq(9), eq(3), eq(4), keys.capture());
        assertTrue(keys.getAllValues().get(0).startsWith("admin-give:"));
        assertFalse(keys.getAllValues().get(0).equals(keys.getAllValues().get(1)));
    }

    @Test
    void give_badInput_makesNoCall() {
        granted.add("knk.lootbox.admin.give");

        command.execute(admin, new String[]{"give", "Nobody", "weapons"});
        command.execute(admin, new String[]{"give", "Steve", "boots"});
        command.execute(admin, new String[]{"give", "Steve", "weapons", "9"});
        command.execute(admin, new String[]{"give", "Steve"});

        verify(api, never()).adminGive(any(), anyInt(), anyInt(), any(), any());
        verify(admin).sendMessage(contains("not online"));
        verify(admin).sendMessage(contains("No enabled lootbox type"));
        verify(admin).sendMessage(contains("1-5"));
    }

    @Test
    void spawn_isPlayerOnly() {
        CommandSender console = mock(CommandSender.class);
        command.execute(console, new String[]{"spawn", "weapons"});
        verify(console).sendMessage(contains("Only players"));
        verify(api, never()).adminSpawn(any(), anyInt(), any(), anyString(), anyInt(), anyInt(), anyInt(), anyString());
    }

    @Test
    void despawnById_callsTheApi() {
        granted.add("knk.lootbox.admin.despawn");
        when(api.despawn(42, 17)).thenReturn(CompletableFuture.completedFuture(null));

        command.execute(admin, new String[]{"despawn", "#17"});

        verify(api).despawn(42, 17);
        verify(runtime).gone(17);
    }

    @Test
    void unknownAction_showsUsage() {
        command.execute(admin, new String[]{"explode"});
        verify(admin).sendMessage(contains("/knk lootbox spawn"));
        verify(api, never()).despawn(any(), anyInt());
    }

    @Test
    void tabComplete_offersOnlyAllowedActions() {
        granted.add("knk.lootbox.admin.list");
        assertEquals(List.of("list"), command.tabComplete(admin, new String[]{""}));
        assertEquals(List.of("weapons"), command.tabComplete(admin, new String[]{"spawn", "w"}));
        verify(api, never()).despawn(isNull(), anyInt());
    }

    // ===== token (Phase 5) =====

    private LootboxAdminCommand withTokens(LootboxTokenDelivery tokens) {
        return new LootboxAdminCommand(runtime, api, delivery, mock(LootboxAnnouncer.class), area,
                (player, node) -> granted.contains(node), p -> p == steve ? 9 : 42,
                name -> "Steve".equalsIgnoreCase(name) ? steve : null, () -> reloads++, Runnable::run, tokens,
                id -> steveInSiege && id.equals(STEVE_ID));
    }

    @Test
    void token_parsesStarsAndAmount_issuesAsTheStaffMember_andGivesTheItems() {
        granted.add("knk.lootbox.admin.token");
        LootboxTokenDelivery tokens = mock(LootboxTokenDelivery.class);
        KnkLootboxToken issued = new KnkLootboxToken(7, java.util.UUID.randomUUID(), 3, "Weapons Lootbox", "Weapons", 5,
                "Legendary Weapons Lootbox", "Issued", "Admin", 9);
        when(api.issueTokens(eq(42), eq(9), eq(3), eq(5), eq(2), eq("Admin"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of(issued, issued)));
        when(api.issueTokens(eq(42), eq(9), eq(3), isNull(), eq(3), eq("Admin"), anyString()))
                .thenReturn(new CompletableFuture<>());
        when(steve.isOnline()).thenReturn(true);

        withTokens(tokens).execute(admin, new String[]{"token", "Steve", "weapons", "5", "2"});
        withTokens(tokens).execute(admin, new String[]{"token", "Steve", "weapons", "any", "3"});

        verify(tokens).give(steve, 9, List.of(issued, issued));
        verify(api).issueTokens(eq(42), eq(9), eq(3), isNull(), eq(3), eq("Admin"), anyString());
    }

    @Test
    void token_needsItsNode_andABoundedAmount() {
        LootboxTokenDelivery tokens = mock(LootboxTokenDelivery.class);
        LootboxAdminCommand command = withTokens(tokens);

        command.execute(admin, new String[]{"token", "Steve", "weapons"});
        granted.add("knk.lootbox.admin.token");
        command.execute(admin, new String[]{"token", "Steve", "weapons", "5", "65"});

        verify(api, never()).issueTokens(any(), anyInt(), anyInt(), any(), anyInt(), any(), any());
        verify(admin).sendMessage(contains("Amount is 1-64"));
    }

    // ===== siege =====

    @Test
    void giveAndToken_toAPlayerInASiege_areRefusedBeforeAnyApiCall_evenFromTheConsole() {
        granted.add("knk.lootbox.admin.give");
        granted.add("knk.lootbox.admin.token");
        steveInSiege = true;
        LootboxAdminCommand command = withTokens(mock(LootboxTokenDelivery.class));
        CommandSender console = mock(CommandSender.class);

        command.execute(admin, new String[]{"give", "Steve", "weapons", "5"});
        command.execute(admin, new String[]{"token", "Steve", "weapons", "5", "2"});
        command.execute(console, new String[]{"give", "Steve", "weapons"});
        command.execute(console, new String[]{"token", "Steve", "weapons"});

        verify(api, never()).adminGive(any(), anyInt(), anyInt(), any(), any());
        verify(api, never()).issueTokens(any(), anyInt(), anyInt(), any(), anyInt(), any(), any());
        verify(admin, org.mockito.Mockito.times(2)).sendMessage(contains("Steve is in a siege"));
        verify(console, org.mockito.Mockito.times(2)).sendMessage(contains("would be lost"));
    }

    @Test
    void give_toAPlayerNotInASiege_stillCallsTheApi() {
        granted.add("knk.lootbox.admin.give");
        when(api.adminGive(any(), anyInt(), anyInt(), any(), any())).thenReturn(new CompletableFuture<>());

        withTokens(mock(LootboxTokenDelivery.class)).execute(admin, new String[]{"give", "Steve", "weapons", "5"});

        verify(api).adminGive(eq(42), eq(9), eq(3), eq(5), anyString());
    }

    @Test
    void tabComplete_suggestsOnlinePlayersForGiveAndToken() {
        granted.add("knk.lootbox.admin.give");
        granted.add("knk.lootbox.admin.token");
        command.setOnlinePlayerNames(() -> List.of("Alex", "Steve", "steveo"));

        assertEquals(List.of("Steve", "steveo"), command.tabComplete(admin, new String[]{"give", "st"}));
        assertEquals(List.of("Alex", "Steve", "steveo"), command.tabComplete(admin, new String[]{"token", ""}));
        assertEquals(List.of("weapons"), command.tabComplete(admin, new String[]{"give", "Steve", "w"}));
    }
}
