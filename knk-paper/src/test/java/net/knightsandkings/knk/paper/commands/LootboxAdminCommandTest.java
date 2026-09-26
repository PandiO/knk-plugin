package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.lootbox.ActiveLootboxCache;
import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import net.knightsandkings.knk.core.ports.api.LootboxesCommandApi;
import net.knightsandkings.knk.paper.lootbox.LootboxAnnouncer;
import net.knightsandkings.knk.paper.lootbox.LootboxDelivery;
import net.knightsandkings.knk.paper.lootbox.LootboxRuntime;
import net.knightsandkings.knk.paper.lootbox.LootboxSettings;
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
    private LootboxAdminCommand command;

    @BeforeEach
    void setUp() {
        when(runtime.config()).thenReturn(LootboxCommandTest.CONFIG);
        when(runtime.cache()).thenReturn(new ActiveLootboxCache());
        when(runtime.settings()).thenReturn(LootboxSettings.defaults());
        when(runtime.clock()).thenReturn(Clock.systemUTC());
        when(steve.getName()).thenReturn("Steve");
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
        when(api.adminGive(42, 9, 3, 4, null)).thenReturn(new CompletableFuture<KnkLootboxClaimResult>());

        command.execute(admin, new String[]{"give", "steve", "Weapons", "4"});

        verify(api).adminGive(42, 9, 3, 4, null);
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
}
