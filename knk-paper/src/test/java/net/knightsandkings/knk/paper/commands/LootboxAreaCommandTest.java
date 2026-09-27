package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.lootbox.ActiveLootboxCache;
import net.knightsandkings.knk.core.lootbox.KnkLootboxArea;
import net.knightsandkings.knk.core.lootbox.KnkLootboxAreaDeleteResult;
import net.knightsandkings.knk.core.lootbox.KnkLootboxRuntimeConfig;
import net.knightsandkings.knk.core.lootbox.LootboxRejectedException;
import net.knightsandkings.knk.core.ports.api.LootboxesCommandApi;
import net.knightsandkings.knk.paper.lootbox.LootboxRegions;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lootboxes Phase 3: {@code /knk lootbox area}. Bad input is refused before any region or API call; an API refusal
 * removes the new region again; delete needs a repeat within 10 s and only ever removes {@code lootbox_} regions.
 */
class LootboxAreaCommandTest {

    private static final Instant T0 = Instant.parse("2026-09-26T12:00:00Z");

    private final LootboxRegions regions = mock(LootboxRegions.class);
    private final LootboxesCommandApi api = mock(LootboxesCommandApi.class);
    private final World world = mock(World.class);
    private final Player admin = mock(Player.class);
    private final List<Integer> removedBoxes = new ArrayList<>();
    private int refreshes;
    private Instant now = T0;
    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };
    private List<KnkLootboxArea> areas = new ArrayList<>();
    private LootboxAreaCommand command;

    private static KnkLootboxArea area(int id, String name, String regionId) {
        return new KnkLootboxArea(id, name, "world", regionId, true, 3, 600, 100, 3, 24, 30, List.of(), List.of(), 0, 7);
    }

    @BeforeEach
    void setUp() {
        when(world.getName()).thenReturn("world");
        when(admin.getWorld()).thenReturn(world);
        when(admin.getName()).thenReturn("Admin");
        command = new LootboxAreaCommand(
                () -> new KnkLootboxRuntimeConfig(true, 15, 10, 5, 6, null, null, null, List.of(), List.of(), areas),
                new ActiveLootboxCache(), regions, api, p -> 42, name -> "world".equals(name) ? world : null,
                () -> refreshes++, removedBoxes::add, Runnable::run, clock);
    }

    private void run(CommandSender sender, String... args) {
        command.execute(sender, args);
    }

    @Test
    void invalidName_isRefusedWithoutARegionOrApiCall() {
        run(admin, "create", "spawn area!");
        run(admin, "create", "x".repeat(33));

        verify(admin, org.mockito.Mockito.times(2)).sendMessage(contains("1-32 letters"));
        verify(regions, never()).createFullHeightFromSelection(any(), anyString());
        verify(api, never()).createAreaInGame(any(), anyString(), anyString(), anyString());
    }

    @Test
    void noSelection_isRefusedWithoutAnApiCall() {
        when(regions.createFullHeightFromSelection(admin, "lootbox_spawn"))
                .thenReturn(new LootboxRegions.CreateResult(LootboxRegions.CreateStatus.NO_SELECTION, null));

        run(admin, "create", "Spawn");

        verify(admin).sendMessage(contains("//wand"));
        verify(api, never()).createAreaInGame(any(), anyString(), anyString(), anyString());
    }

    @Test
    void existingRegion_isRefusedWithoutCreatingOrCalling() {
        when(regions.regionExists(world, "lootbox_spawn")).thenReturn(true);

        run(admin, "create", "spawn");

        verify(admin).sendMessage(contains("already exists"));
        verify(regions, never()).createFullHeightFromSelection(any(), anyString());
        verify(api, never()).createAreaInGame(any(), anyString(), anyString(), anyString());
    }

    @Test
    void existingAreaName_isRefusedWithoutARegion() {
        areas.add(area(1, "spawn", "lootbox_spawn"));

        run(admin, "create", "SPAWN");

        verify(regions, never()).createFullHeightFromSelection(any(), anyString());
    }

    @Test
    void created_callsTheApiWithTheSlugRegion_andRefreshes() {
        when(regions.createFullHeightFromSelection(admin, "lootbox_market-2")).thenReturn(LootboxRegions.CreateResult.created());
        when(api.createAreaInGame(42, "Market-2", "world", "lootbox_market-2"))
                .thenReturn(CompletableFuture.completedFuture(area(5, "Market-2", "lootbox_market-2")));

        run(admin, "create", "Market-2");

        verify(admin).sendMessage(contains("Area Market-2 created (max 3 boxes, every 600 s, ≥3 online)"));
        verify(regions, never()).removeRegion(any(), anyString());
        assertEquals(1, refreshes);
    }

    @Test
    void apiConflict_removesTheNewRegionAgain() {
        when(regions.createFullHeightFromSelection(admin, "lootbox_spawn")).thenReturn(LootboxRegions.CreateResult.created());
        when(api.createAreaInGame(42, "spawn", "world", "lootbox_spawn")).thenReturn(CompletableFuture.failedFuture(
                new LootboxRejectedException(409, LootboxRejectedException.NAME_TAKEN, "taken", null, null, null)));

        run(admin, "create", "spawn");

        verify(regions).removeRegion(world, "lootbox_spawn");
        verify(admin).sendMessage(contains("removed again"));
        assertEquals(0, refreshes);
    }

    @Test
    void apiDown_removesTheNewRegionAgain() {
        when(regions.createFullHeightFromSelection(admin, "lootbox_spawn")).thenReturn(LootboxRegions.CreateResult.created());
        when(api.createAreaInGame(42, "spawn", "world", "lootbox_spawn"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("connection refused")));

        run(admin, "create", "spawn");

        verify(regions).removeRegion(world, "lootbox_spawn");
    }

    @Test
    void delete_needsARepeatWithinTenSeconds() {
        areas.add(area(1, "spawn", "lootbox_spawn"));
        when(api.deleteAreaInGame(42, 1)).thenReturn(CompletableFuture.completedFuture(
                new KnkLootboxAreaDeleteResult(1, "spawn", "world", "lootbox_spawn", List.of(3, 4))));

        run(admin, "delete", "spawn");
        verify(api, never()).deleteAreaInGame(any(), anyInt());
        verify(admin).sendMessage(contains("Repeat the command"));

        run(admin, "delete", "spawn");
        verify(api).deleteAreaInGame(42, 1);
        verify(regions).removeRegion(world, "lootbox_spawn");
        assertEquals(List.of(3, 4), removedBoxes);
        assertEquals(1, refreshes);
    }

    @Test
    void delete_repeatAfterTheWindow_asksAgain() {
        areas.add(area(1, "spawn", "lootbox_spawn"));
        run(admin, "delete", "spawn");

        now = T0.plusSeconds(11);
        run(admin, "delete", "spawn");

        verify(api, never()).deleteAreaInGame(any(), anyInt());
        verify(admin, org.mockito.Mockito.times(2)).sendMessage(contains("Repeat the command"));
    }

    @Test
    void delete_neverRemovesABorrowedRegion() {
        areas.add(area(2, "town", "domain_14"));
        when(api.deleteAreaInGame(42, 2)).thenReturn(CompletableFuture.completedFuture(
                new KnkLootboxAreaDeleteResult(2, "town", "world", "domain_14", List.of())));

        run(admin, "delete", "town");
        run(admin, "delete", "town");

        verify(api).deleteAreaInGame(42, 2);
        verify(regions, never()).removeRegion(any(), anyString());
        verify(admin).sendMessage(contains("region domain_14 kept"));
    }

    @Test
    void list_andInfo_readTheRuntimeConfig() {
        areas.add(area(1, "spawn", "lootbox_spawn"));
        when(regions.bounds(eq(world), eq("lootbox_spawn"))).thenReturn(java.util.Optional.empty());

        run(admin, "list");
        run(admin, "info", "spawn");

        verify(admin).sendMessage(contains("spawn " + org.bukkit.ChatColor.GREEN + "enabled"));
        verify(admin).sendMessage(contains("region missing"));
    }
}
