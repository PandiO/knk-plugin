package net.knightsandkings.knk.paper.production.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.knightsandkings.knk.core.items.identity.ItemIdentityResolution;
import net.knightsandkings.knk.core.production.shop.SellMenuLayout;
import net.knightsandkings.knk.core.production.shop.ShopRefusal;
import net.knightsandkings.knk.core.production.shop.ShopSellLine;
import net.knightsandkings.knk.paper.menu.MenuService;
import net.knightsandkings.knk.paper.production.terminal.BlueprintItems;

/**
 * PandiO/knk-workspace#687 (shops DESIGN § 3.2 and § 8, A18): the sell menu picks stacks from the player's inventory,
 * shows the buy-back prices and the total, sells all lines or none at the total shown, takes the items only when the sale
 * is sent and gives them back when it is refused; damaged, unresolved and changed items are refused.
 */
class SellModuleTest {

    private ServerMock server;
    private PlayerMock player;
    private Plugin plugin;
    private final FakeShopsApi api = new FakeShopsApi();
    private final Set<UUID> inSiege = new HashSet<>();
    private SellModule module;

    /** Bread is blueprint 11, a named diamond sword is unique item 900, everything else is unresolved. */
    private final Function<ItemStack, ItemIdentityResolution> identity = stack -> {
        if (stack.getType() == Material.BREAD) {
            return new ItemIdentityResolution(ItemIdentityResolution.Kind.VANILLA_MATCH, 11, null, null, false);
        }
        if (stack.getType() == Material.DIAMOND_SWORD) {
            return new ItemIdentityResolution(ItemIdentityResolution.Kind.INSTANCE, 20, 900L, null, false);
        }
        if (stack.getType() == Material.IRON_SWORD) {
            return new ItemIdentityResolution(ItemIdentityResolution.Kind.VANILLA_MATCH, 21, null, null, false);
        }
        return new ItemIdentityResolution(ItemIdentityResolution.Kind.UNRESOLVED, null, null,
                ItemIdentityResolution.UnresolvedReason.NO_MATCH, false);
    };

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
        ShopService shops = new ShopService(api, items, uuid -> 3, uuid -> 1000L, inSiege::contains, Runnable::run,
                Clock.systemUTC(), () -> mock(MenuService.class));
        shops.offer(5);
        module = new SellModule(plugin, shops, uuid -> 3, uuid -> 1000L, inSiege::contains, () -> identity, Runnable::run,
                () -> null);
        module.start();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private SellView openWith(ItemStack... stacks) {
        for (int i = 0; i < stacks.length; i++) {
            player.getInventory().setItem(i, stacks[i]);
        }
        SellView view = module.open(player, 5);
        assertNotNull(view);
        return view;
    }

    private int count(Material material) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == material) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    @Test
    void open_showsTheLegacyLayout() {
        SellView view = openWith();
        assertSame(view, player.getOpenInventory().getTopInventory().getHolder());
        assertEquals(Material.GOLD_INGOT, view.getInventory().getItem(SellMenuLayout.COINS_SLOT).getType());
        assertEquals(Material.CHEST, view.getInventory().getItem(SellMenuLayout.SELL_SLOT).getType());
        assertEquals(Material.ANVIL, view.getInventory().getItem(SellMenuLayout.INFO_SLOT).getType());
        assertEquals(Material.BOOK, view.getInventory().getItem(SellMenuLayout.HELP_SLOT).getType());
        assertEquals(Material.BARRIER, view.getInventory().getItem(SellMenuLayout.BACK_SLOT).getType());
    }

    @Test
    void pick_quotesTheLinesAndShowsThePrices() {
        SellView view = openWith(new ItemStack(Material.BREAD, 20), new ItemStack(Material.DIAMOND_SWORD),
                new ItemStack(Material.BREAD, 5));

        module.pick(player, view, 0);
        module.pick(player, view, 1);
        module.pick(player, view, 2);

        assertEquals(List.of(ShopSellLine.blueprint(11, 25), ShopSellLine.instance(900)), api.sellQuotes.get(2).lines());
        assertEquals(125, view.quote().total());
        assertEquals(Material.BREAD, view.getInventory().getItem(SellMenuLayout.pickSlot(0)).getType());
        assertEquals(Material.DIAMOND_SWORD, view.getInventory().getItem(SellMenuLayout.pickSlot(1)).getType());
        List<String> lore = view.getInventory().getItem(SellMenuLayout.pickSlot(0)).getItemMeta().getLore();
        assertTrue(lore.stream().anyMatch(line -> line.contains("Buy-back")), lore.toString());
        assertEquals(25, count(Material.BREAD)); // nothing leaves the inventory before the sale

        module.clickTop(player, view, SellMenuLayout.pickSlot(1));
        assertEquals(List.of(ShopSellLine.blueprint(11, 25)), api.sellQuotes.get(3).lines());
    }

    @Test
    void sell_sellsAtTheTotalShownAndTakesTheItems() {
        SellView view = openWith(new ItemStack(Material.BREAD, 20), new ItemStack(Material.DIAMOND_SWORD));
        module.pick(player, view, 0);
        module.pick(player, view, 1);

        var result = module.sell(player, view).join();

        assertNotNull(result);
        assertEquals(115L, api.sales.get(0).expectedTotal());
        assertTrue(api.sales.get(0).confirmed());
        assertTrue(api.sales.get(0).idempotencyKey().startsWith("sell-s5-u3-"));
        assertEquals(0, count(Material.BREAD));
        assertEquals(0, count(Material.DIAMOND_SWORD));
        assertTrue(player.nextMessage().contains("You sold 2 item stacks"));
    }

    @Test
    void sell_refused_givesTheItemsBack() {
        SellView view = openWith(new ItemStack(Material.BREAD, 20));
        module.pick(player, view, 0);
        api.failures.add(FakeShopsApi.conflict("TownFundsInsufficient"));

        assertNull(module.sell(player, view).join());

        assertEquals(20, count(Material.BREAD));
        assertEquals("§c" + ShopRefusal.TOWN_FUNDS_INSUFFICIENT.message(), player.nextMessage());
    }

    @Test
    void sell_priceChanged_givesBackAndQuotesAgain() {
        SellView view = openWith(new ItemStack(Material.BREAD, 20));
        module.pick(player, view, 0);
        api.failures.add(FakeShopsApi.conflict("PriceChanged"));
        int quotesBefore = api.sellQuotes.size();

        assertNull(module.sell(player, view).join());

        assertEquals(20, count(Material.BREAD));
        assertEquals("§e" + ShopRefusal.PRICE_CHANGED.message(), player.nextMessage());
        assertEquals(quotesBefore + 1, api.sellQuotes.size());
    }

    @Test
    void sell_lostAnswer_isSentOnceMoreWithTheSameKey() {
        SellView view = openWith(new ItemStack(Material.BREAD, 20));
        module.pick(player, view, 0);
        api.failures.add(FakeShopsApi.lost());

        assertNotNull(module.sell(player, view).join());

        assertEquals(2, api.sales.size());
        assertEquals(api.sales.get(0).idempotencyKey(), api.sales.get(1).idempotencyKey());
        assertEquals(0, count(Material.BREAD));
    }

    @Test
    void sell_refusesARefusedLineWithoutACall() {
        api.refusedBlueprint = 11;
        SellView view = openWith(new ItemStack(Material.BREAD, 20));
        module.pick(player, view, 0);

        module.sell(player, view).join();

        assertTrue(api.sales.isEmpty());
        assertTrue(player.nextMessage().contains("Take out the marked items"));
        List<String> lore = view.getInventory().getItem(SellMenuLayout.pickSlot(0)).getItemMeta().getLore();
        assertTrue(lore.stream().anyMatch(line -> line.contains(ShopRefusal.CATEGORY_NOT_ACCEPTED.message())), lore.toString());
    }

    @Test
    void sell_inventoryChanged_dropsThePickAndAsksAgain() {
        SellView view = openWith(new ItemStack(Material.BREAD, 20));
        module.pick(player, view, 0);
        player.getInventory().setItem(0, new ItemStack(Material.BREAD, 3));

        module.sell(player, view).join();

        assertTrue(api.sales.isEmpty());
        assertTrue(view.basket().isEmpty());
        assertEquals(3, count(Material.BREAD));
        assertTrue(player.nextMessage().contains("inventory changed"));
    }

    @Test
    void pick_refusesUnresolvedDamagedAndEnchantedPlainItems() {
        ItemStack damaged = new ItemStack(Material.IRON_SWORD);
        ItemMeta meta = damaged.getItemMeta();
        ((Damageable) meta).setDamage(10);
        damaged.setItemMeta(meta);
        ItemStack enchanted = new ItemStack(Material.IRON_SWORD);
        enchanted.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.SHARPNESS, 1);
        SellView view = openWith(new ItemStack(Material.DIRT), damaged, enchanted);

        module.pick(player, view, 0);
        assertEquals("§c" + ShopRefusal.NOT_RESOLVED.message(), player.nextMessage());
        module.pick(player, view, 1);
        assertTrue(player.nextMessage().contains("Damaged"));
        module.pick(player, view, 2);
        assertTrue(player.nextMessage().contains("Enchanted"));
        assertTrue(view.basket().isEmpty());
        assertTrue(api.sellQuotes.isEmpty());
    }

    @Test
    void open_refusedDuringASiegeOrWhenTheShopDoesntBuy() {
        inSiege.add(player.getUniqueId());
        assertNull(module.open(player, 5));
        assertTrue(player.nextMessage().contains("siege"));
    }
}
