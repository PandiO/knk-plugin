package net.knightsandkings.knk.paper.siege.survival.purchase;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.ConfigurationDto;
import net.knightsandkings.knk.core.survival.economy.KillBlow;
import net.knightsandkings.knk.core.survival.economy.KillRewardSettings;
import net.knightsandkings.knk.core.survival.economy.MatchEconomy;
import net.knightsandkings.knk.paper.siege.SiegeLobbyRuntime;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfigurationMapper;
import net.knightsandkings.knk.paper.siege.survival.SurvivalGhosts;
import net.knightsandkings.knk.paper.siege.survival.SurvivalMatch;
import net.knightsandkings.knk.paper.siege.survival.SurvivalSpawner;
import net.knightsandkings.knk.paper.siege.survival.items.MatchItems;
import net.knightsandkings.knk.paper.siege.survival.items.MatchItemsTest;
import net.knightsandkings.knk.paper.siege.survival.items.SurvivalItemFactory;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Buying at a purchase point (DESIGN §8): charge, give, overflow, denials. The World mock is a field (CLAUDE.md). */
class PurchaseShopTest {

    private static final String CONFIG = """
        { "siegeScenarioId": 1, "minPlayers": 1, "humanTeamId": 1, "npcTeamId": 2,
          "endConditions": { "scenarioAllCaptured": false }, "objectives": [], "gates": [], "stuck": {},
          "spawn": { "zones": [] }, "difficulty": { "id": 1, "name": "n", "countCurve": { "preset": "Linear", "base": 2, "step": 1 },
            "statCurve": { "preset": "Linear", "base": 1, "step": 0.5 }, "statWeights": {}, "perPlayer": {}, "maxAlive": 40, "statCap": 8 },
          "monsterTypes": [ { "entityType": "ZOMBIE", "weight": 1 } ], "readiness": { "isReady": true },
          "purchasePoints": [ { "id": 4, "name": "Armory", "location": { "x": 10, "y": 64, "z": 10, "world": "w" }, "interactionRange": 3,
            "offers": [ { "id": 11, "itemBlueprintId": 100, "itemBlueprintName": "Arrows", "quantity": 70, "price": 40 },
                        { "id": 12, "itemBlueprintId": 101, "itemBlueprintName": "Sword", "quantity": 1, "price": 500 } ] } ] }
        """;

    private final World world = mock(World.class);
    private final SurvivalMatch match = mock(SurvivalMatch.class);
    private final SurvivalItemFactory factory = mock(SurvivalItemFactory.class);
    private final MatchItems items = new MatchItems(new NamespacedKey("knk", "survival_match"));
    private final MatchEconomy economy = new MatchEconomy("m-1", KillRewardSettings.defaults());
    private final SurvivalGhosts ghosts = mock(SurvivalGhosts.class);
    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final UUID alice = UUID.randomUUID();
    private final ItemStack arrows = MatchItemsTest.itemWithPdc(Material.ARROW);
    private final PurchaseShop shop;

    PurchaseShopTest() throws Exception {
        SurvivalConfiguration config = SurvivalConfigurationMapper.map(new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).readValue(CONFIG, ConfigurationDto.class));
        when(world.getName()).thenReturn("w");
        SiegeLobbyRuntime lobby = mock(SiegeLobbyRuntime.class);
        when(lobby.id()).thenReturn(1);
        when(lobby.key()).thenReturn("test");
        when(match.config()).thenReturn(config);
        when(match.economy()).thenReturn(economy);
        when(match.ghosts()).thenReturn(ghosts);
        when(match.lobby()).thenReturn(lobby);
        when(match.matchKey()).thenReturn("m-1");
        when(factory.items()).thenReturn(items);
        when(arrows.getMaxStackSize()).thenReturn(64);
        when(factory.blueprintItem(eq(100), eq(1), any(), eq("m-1"))).thenReturn(CompletableFuture.completedFuture(Optional.of(arrows)));
        when(factory.blueprintItem(eq(101), eq(1), any(), eq("m-1"))).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(player.getUniqueId()).thenReturn(alice);
        when(player.getName()).thenReturn("alice");
        when(player.getInventory()).thenReturn(inventory);
        when(player.getLocation()).thenReturn(new Location(world, 11, 64, 10));
        when(inventory.addItem(any())).thenReturn(new HashMap<>());
        economy.join(alice);
        shop = new PurchaseShop(match, factory, name -> "w".equals(name) ? world : null, Runnable::run, null);
        shop.prepare();
    }

    private static String plain(net.kyori.adventure.text.Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void preparesWhatCanBeAssembledAndFindsThePointByBlockAndByRange() {
        assertEquals(1, shop.preparedOffers());
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(10);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(10);
        assertEquals(4, shop.pointAt(block).orElseThrow().id());
        when(block.getZ()).thenReturn(11);
        assertTrue(shop.pointAt(block).isEmpty());
        assertEquals(4, shop.pointNear(player).orElseThrow().id());
        when(player.getLocation()).thenReturn(new Location(world, 20, 64, 10));
        assertTrue(shop.pointNear(player).isEmpty());
        assertTrue(shop.statusLines().get(0).contains("1 offers ready"));
    }

    @Test
    void buyingChargesTheLedgerAndGivesTheQuantityInStacks() {
        for (int i = 0; i < 2; i++) economy.creditKill(i, new KillBlow(alice, false, false)); // 140
        var point = match.config().purchasePoint(4).orElseThrow();
        String reply = plain(shop.buy(player, point, 11));
        assertTrue(reply.startsWith("Bought 70×"), reply);
        assertTrue(reply.contains("balance 100"), reply);
        assertEquals(100, economy.balance(alice));
        verify(inventory, org.mockito.Mockito.times(2)).addItem(any());
        verify(arrows).setAmount(64);
        verify(arrows).setAmount(6);
        assertTrue(shop.statusLines().get(0).contains("1 purchases, 40 spent"));
    }

    @Test
    void deniesTheBrokeTheGhostTheFarAndTheUnpreparedOffer() {
        var point = match.config().purchasePoint(4).orElseThrow();
        assertTrue(plain(shop.buy(player, point, 11)).contains("Not enough match coins: 40 needed, you have 0"));
        economy.creditKill(1, new KillBlow(alice, false, false));
        assertTrue(plain(shop.buy(player, point, 12)).contains("not available"));
        assertTrue(plain(shop.buy(player, point, 99)).contains("does not exist"));
        when(ghosts.isGhost(alice)).thenReturn(true);
        assertTrue(plain(shop.buy(player, point, 11)).contains("Ghosts cannot buy"));
        when(ghosts.isGhost(alice)).thenReturn(false);
        when(player.getLocation()).thenReturn(new Location(world, 20, 64, 10));
        assertTrue(plain(shop.buy(player, point, 11)).contains("too far"));
        when(player.getLocation()).thenReturn(new Location(world, 11, 64, 10));
        economy.leave(alice);
        assertTrue(plain(shop.buy(player, point, 11)).contains("not in this match"));
        shop.ended();
        assertTrue(plain(shop.buy(player, point, 11)).contains("over"));
        assertEquals(0, shop.preparedOffers());
    }

    @Test
    void overflowIsDroppedAsAnOwnedMatchItem() {
        economy.creditKill(1, new KillBlow(alice, false, false));
        ItemStack rest = MatchItemsTest.itemWithPdc(Material.ARROW);
        when(inventory.addItem(any())).thenReturn(new HashMap<>(java.util.Map.of(0, rest)));
        when(world.dropItem(any(), any(), any())).thenAnswer(inv -> {
            org.bukkit.entity.Item dropped = mock(org.bukkit.entity.Item.class);
            when(dropped.getUniqueId()).thenReturn(UUID.randomUUID());
            return dropped;
        });
        String reply = plain(shop.buy(player, match.config().purchasePoint(4).orElseThrow(), 11));
        assertTrue(reply.contains("dropped at your feet"), reply);
        assertEquals(2, items.trackedGroundItems("m-1"));
        verify(world, org.mockito.Mockito.times(2)).dropItem(any(), any(), any());
    }
}
