package net.knightsandkings.knk.paper.siege.survival.mysterybox;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.ConfigurationDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MysteryBoxEntryDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MysteryBoxRollDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MysteryBoxRolledEnchantmentDto;
import net.knightsandkings.knk.api.impl.SiegeSurvivalMatchApiImpl;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.survival.economy.KillBlow;
import net.knightsandkings.knk.core.survival.economy.KillRewardSettings;
import net.knightsandkings.knk.core.survival.economy.MatchEconomy;
import net.knightsandkings.knk.core.survival.mysterybox.MysteryBoxRollState;
import net.knightsandkings.knk.core.survival.mysterybox.MysteryBoxRollState.ForfeitReason;
import net.knightsandkings.knk.paper.siege.SiegeLobbyRuntime;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfigurationMapper;
import net.knightsandkings.knk.paper.siege.survival.SurvivalGhosts;
import net.knightsandkings.knk.paper.siege.survival.SurvivalMatch;
import net.knightsandkings.knk.paper.siege.survival.items.MatchItems;
import net.knightsandkings.knk.paper.siege.survival.items.MatchItemsTest;
import net.knightsandkings.knk.paper.siege.survival.items.SurvivalItemFactory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The roll → animation → claim/forfeit flow against a mocked match API (DESIGN §8a; S39, S59). The World mock is a
 * field (CLAUDE.md); the scheduler and the main-thread executor are synchronous.
 */
class MysteryBoxStationsTest {

    private static final String CONFIG = """
        { "siegeScenarioId": 1, "minPlayers": 1, "humanTeamId": 1, "npcTeamId": 2,
          "endConditions": { "scenarioAllCaptured": false }, "objectives": [], "gates": [], "stuck": {},
          "spawn": { "zones": [] }, "difficulty": { "id": 1, "name": "n", "countCurve": { "preset": "Linear", "base": 2, "step": 1 },
            "statCurve": { "preset": "Linear", "base": 1, "step": 0.5 }, "statWeights": {}, "perPlayer": {}, "maxAlive": 40, "statCap": 8 },
          "monsterTypes": [ { "entityType": "ZOMBIE", "weight": 1 } ], "readiness": { "isReady": true },
          "mysteryBoxPoints": [ { "id": 7, "name": "Box", "location": { "x": 5, "y": 64, "z": 5, "world": "w" }, "interactionRange": 4,
            "blockType": "minecraft:end_portal_frame", "rollPrice": 100, "claimTimeoutMillis": 10000,
            "pool": { "variant": "Items", "entries": [
              { "id": 21, "itemBlueprintId": 100, "itemBlueprintName": "Bow", "chanceBasisPoints": 4000,
                "enchantments": [ { "enchantmentDefinitionId": 50, "level": 2, "chanceBasisPoints": 5000 } ] },
              { "id": 22, "itemBlueprintId": 101, "itemBlueprintName": "Sword", "chanceBasisPoints": 6000 } ] } } ] }
        """;

    private final World world = mock(World.class);
    private final SurvivalMatch match = mock(SurvivalMatch.class);
    private final SiegeSurvivalMatchApiImpl api = mock(SiegeSurvivalMatchApiImpl.class);
    private final SurvivalItemFactory factory = mock(SurvivalItemFactory.class);
    private final MatchItems items = new MatchItems(new NamespacedKey("knk", "survival_match"));
    private final MatchEconomy economy = new MatchEconomy("m-1", KillRewardSettings.defaults());
    private final SurvivalGhosts ghosts = mock(SurvivalGhosts.class);
    private final ManualScheduler scheduler = new ManualScheduler();
    private final AtomicLong clock = new AtomicLong(100_000);
    private final Map<UUID, Player> online = new HashMap<>();
    private final Player alice = player("alice");
    private final Player bob = player("bob");
    private final ItemStack bowFace = MatchItemsTest.itemWithPdc(Material.BOW);
    private final ItemStack prize = MatchItemsTest.itemWithPdc(Material.BOW);
    private final ItemDisplay display = MysteryBoxAnimationTest.entity(ItemDisplay.class);
    private final TextDisplay label = MysteryBoxAnimationTest.entity(TextDisplay.class);
    private final Interaction hitbox = MysteryBoxAnimationTest.entity(Interaction.class);
    private final Block block = mock(Block.class);
    private final MysteryBoxStations stations;

    @SuppressWarnings("unchecked")
    MysteryBoxStationsTest() throws Exception {
        SurvivalConfiguration config = SurvivalConfigurationMapper.map(new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).readValue(CONFIG, ConfigurationDto.class));
        when(world.getName()).thenReturn("w");
        when(world.getBlockAt(any(Location.class))).thenReturn(block);
        when(block.getType()).thenReturn(Material.END_PORTAL_FRAME);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(5);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(5);
        when(world.spawn(any(Location.class), eq(ItemDisplay.class), any(Consumer.class))).thenReturn(display);
        when(world.spawn(any(Location.class), eq(TextDisplay.class), any(Consumer.class))).thenReturn(label);
        when(world.spawn(any(Location.class), eq(Interaction.class), any(Consumer.class))).thenReturn(hitbox);
        SiegeLobbyRuntime lobby = mock(SiegeLobbyRuntime.class);
        when(lobby.id()).thenReturn(1);
        when(lobby.key()).thenReturn("test");
        when(match.config()).thenReturn(config);
        when(match.economy()).thenReturn(economy);
        when(match.ghosts()).thenReturn(ghosts);
        when(match.lobby()).thenReturn(lobby);
        when(match.matchKey()).thenReturn("m-1");
        when(match.now()).thenAnswer(inv -> clock.get());
        when(match.onlinePlayer()).thenReturn(online::get);
        when(factory.items()).thenReturn(items);
        when(factory.blueprintItem(eq(100), eq(1), any(), eq("m-1"))).thenReturn(CompletableFuture.completedFuture(Optional.of(bowFace)));
        ItemStack swordFace = MatchItemsTest.itemWithPdc(Material.IRON_SWORD);
        when(factory.blueprintItem(eq(101), eq(1), any(), eq("m-1"))).thenReturn(CompletableFuture.completedFuture(Optional.of(swordFace)));
        when(factory.blueprintItem(eq(100), isNull(), any(), eq("m-1"))).thenReturn(CompletableFuture.completedFuture(Optional.of(prize)));
        economy.join(alice.getUniqueId());
        economy.join(bob.getUniqueId());
        for (int i = 0; i < 3; i++) economy.creditKill(i, new KillBlow(alice.getUniqueId(), false, false)); // 210
        stations = new MysteryBoxStations(match, api, () -> OptionalLong.of(42), id -> OptionalInt.of(id.equals(alice.getUniqueId()) ? 7 : 8),
            factory, new NamespacedKey("knk", "survival_box"), name -> "w".equals(name) ? world : null, scheduler,
            MysteryBoxAnimation.Effects.none(), Runnable::run, 4, null);
        stations.start();
    }

    private Player player(String name) {
        Player p = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(p.getUniqueId()).thenReturn(id);
        when(p.getName()).thenReturn(name);
        when(p.isOnline()).thenReturn(true);
        when(p.getLocation()).thenReturn(new Location(world, 6, 64, 5));
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.addItem(any())).thenReturn(new HashMap<>());
        when(p.getInventory()).thenReturn(inventory);
        online.put(id, p);
        return p;
    }

    private static String plain(Component c) {
        return c == null ? null : PlainTextComponentSerializer.plainText().serialize(c);
    }

    private MysteryBoxRollDto rolled(String rollId) {
        MysteryBoxEntryDto entry = new MysteryBoxEntryDto(21, 1, 0, 100, "Bow", null, null, null, null, 4000, List.of());
        return new MysteryBoxRollDto(rollId, 42, 7, 7, "Items", entry, List.of(new MysteryBoxRolledEnchantmentDto(50, 2)),
            List.of(22, 21, 22, 21), 100, "Rolled", null, null, null, null, null);
    }

    private MysteryBoxStations.Station station() {
        return stations.stations().get(0);
    }

    @Test
    void aRollChargesCallsTheApiPlaysTheReelAndTheRollerClaims() {
        when(api.roll(eq(42L), eq(7), anyString(), eq(7), eq(100))).thenAnswer(inv -> CompletableFuture.completedFuture(rolled(inv.getArgument(2))));
        when(api.claim(anyLong(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        assertNull(stations.roll(alice, station()));
        assertEquals(110, economy.balance(alice.getUniqueId()));
        assertTrue(station().busy());
        String rollId = station().roll().orElseThrow().rollId();
        assertTrue(rollId.startsWith("m42-p7-"));
        assertEquals(List.of(rollId), stations.pendingRollIds());
        assertEquals(Optional.of(rollId), stations.pendingRollOf(alice.getUniqueId()));
        assertEquals(MysteryBoxRollState.Phase.ROLLING, station().roll().orElseThrow().phase());
        assertTrue(plain(stations.roll(bob, station())).contains("busy"));
        assertTrue(plain(stations.roll(alice, station())).contains("still running"));
        assertTrue(plain(stations.claim(alice, station())).contains("Wait for the reel"));

        assertEquals(3, scheduler.runAll(), "three more faces after the first");
        assertEquals(MysteryBoxRollState.Phase.SHOWN, station().roll().orElseThrow().phase());
        assertTrue(stations.stationOf(hitbox).isPresent());
        assertTrue(plain(stations.claim(bob, station())).contains("Only alice"));
        assertNull(stations.claim(alice, station()));
        assertFalse(station().busy());
        verify(alice.getInventory()).addItem(prize);
        verify(api).claim(42L, rollId, SiegeSurvivalMatchApiImpl.DELIVERY_INVENTORY);
        verify(display).remove();
        verify(hitbox).remove();
        assertTrue(stations.pendingRollIds().isEmpty());
        assertTrue(stations.statusLines().get(0).contains("rolls 1, claims 1, forfeits 0"));
    }

    @Test
    void anUnclaimedItemTimesOutWithoutARefund() {
        when(api.roll(anyLong(), anyInt(), anyString(), anyInt(), anyInt())).thenAnswer(inv -> CompletableFuture.completedFuture(rolled(inv.getArgument(2))));
        when(api.forfeit(anyLong(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        stations.roll(alice, station());
        scheduler.runAll();
        String rollId = station().roll().orElseThrow().rollId();
        stations.ticked(clock.get() + 9_999);
        assertTrue(station().busy());
        stations.ticked(clock.get() + 10_000);
        assertFalse(station().busy());
        assertEquals(ForfeitReason.TIMEOUT, station().roll().orElseThrow().forfeitReason().orElseThrow());
        verify(api).forfeit(42L, rollId, SiegeSurvivalMatchApiImpl.FORFEIT_TIMEOUT);
        verify(display).remove();
        assertEquals(110, economy.balance(alice.getUniqueId()), "no refund (S59)");
        assertTrue(plain(stations.claim(alice, station())).contains("Nothing to claim"));
        assertNull(stations.roll(alice, station()), "the box is free again");
    }

    @Test
    void deathLeaveAndMatchEndForfeitTheRollersRollOnly() {
        when(api.roll(anyLong(), anyInt(), anyString(), anyInt(), anyInt())).thenAnswer(inv -> CompletableFuture.completedFuture(rolled(inv.getArgument(2))));
        when(api.forfeit(anyLong(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        stations.roll(alice, station());
        String first = station().roll().orElseThrow().rollId();
        stations.playerBecameGhost(bob.getUniqueId());
        assertTrue(station().busy(), "bob's death leaves alice's roll alone");
        stations.playerBecameGhost(alice.getUniqueId());
        assertFalse(station().busy());
        verify(api).forfeit(42L, first, SiegeSurvivalMatchApiImpl.FORFEIT_ROLLER_DIED);

        stations.roll(alice, station());
        String second = station().roll().orElseThrow().rollId();
        scheduler.runAll();
        stations.memberLeft(alice.getUniqueId());
        verify(api).forfeit(42L, second, SiegeSurvivalMatchApiImpl.FORFEIT_ROLLER_LEFT);

        for (int i = 10; i < 12; i++) economy.creditKill(i, new KillBlow(alice.getUniqueId(), false, false));
        assertNull(stations.roll(alice, station()));
        String third = station().roll().orElseThrow().rollId();
        stations.ended();
        verify(api).forfeit(42L, third, SiegeSurvivalMatchApiImpl.FORFEIT_MATCH_ENDED);
        assertTrue(plain(stations.roll(alice, station())).contains("over"));
        stations.ended();
        verify(api, org.mockito.Mockito.times(1)).forfeit(42L, third, SiegeSurvivalMatchApiImpl.FORFEIT_MATCH_ENDED);
    }

    @Test
    void anApiConflictCancelsLocallyAndTheChargeStands() {
        ApiException conflict = new ApiException("url", 409, "conflict", "{\"code\":\"PoolInvalid\"}");
        when(api.roll(anyLong(), anyInt(), anyString(), anyInt(), anyInt())).thenReturn(CompletableFuture.failedFuture(conflict));
        when(api.conflictCode(conflict)).thenReturn(SiegeSurvivalMatchApiImpl.POOL_INVALID);
        assertNull(stations.roll(alice, station()));
        assertFalse(station().busy());
        assertEquals(110, economy.balance(alice.getUniqueId()));
        verify(api, never()).forfeit(anyLong(), anyString(), anyString());
        verify(world, never()).spawn(any(Location.class), eq(ItemDisplay.class), any(Consumer.class));
    }

    @Test
    void deniesGhostsTheBrokeTheFarAndTheOutsider() {
        assertTrue(plain(stations.roll(bob, station())).contains("100 match coins per roll; you have 0"));
        when(ghosts.isGhost(alice.getUniqueId())).thenReturn(true);
        assertTrue(plain(stations.roll(alice, station())).contains("Ghosts"));
        when(ghosts.isGhost(alice.getUniqueId())).thenReturn(false);
        when(alice.getLocation()).thenReturn(new Location(world, 20, 64, 5));
        assertTrue(plain(stations.roll(alice, station())).contains("too far"));
        Player outsider = player("carol");
        assertTrue(plain(stations.roll(outsider, station())).contains("not in this match"));
        verify(api, never()).roll(anyLong(), anyInt(), anyString(), anyInt(), anyInt());
        assertEquals(7, stations.stationAt(block).orElseThrow().spec().id());
    }

    @Test
    void transportFailuresAreNetworkErrorsAnd409sAreNot() {
        assertTrue(MysteryBoxStations.isNetwork(new IOException("reset")));
        assertTrue(MysteryBoxStations.isNetwork(new ApiException("url", "io", new IOException("reset"))));
        assertTrue(MysteryBoxStations.isNetwork(new ApiException("url", 503, "down", "")));
        assertFalse(MysteryBoxStations.isNetwork(new ApiException("url", 409, "conflict", "{}")));
        assertFalse(MysteryBoxStations.isNetwork(new IllegalStateException()));
        ApiException inner = new ApiException("url", 409, "c", "{}");
        assertEquals(inner, MysteryBoxStations.unwrap(new CompletionException(new RuntimeException("wrapped", inner))));
        assertEquals(SiegeSurvivalMatchApiImpl.FORFEIT_ROLLER_LEFT, MysteryBoxStations.apiReason(ForfeitReason.ROLLER_LEFT));
        assertEquals(SiegeSurvivalMatchApiImpl.FORFEIT_TIMEOUT, MysteryBoxStations.apiReason(ForfeitReason.TIMEOUT));
    }
}
