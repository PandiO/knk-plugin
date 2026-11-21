package net.knightsandkings.knk.paper.production.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.core.items.identity.ItemIdentityResolution;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.production.shop.SellBasket;
import net.knightsandkings.knk.core.production.shop.SellMenuLayout;
import net.knightsandkings.knk.core.production.shop.ShopKeys;
import net.knightsandkings.knk.core.production.shop.ShopMenuKeys;
import net.knightsandkings.knk.core.production.shop.ShopOffer;
import net.knightsandkings.knk.core.production.shop.ShopRefusal;
import net.knightsandkings.knk.core.production.shop.ShopSellLine;
import net.knightsandkings.knk.core.production.shop.ShopSellQuote;
import net.knightsandkings.knk.core.production.shop.ShopSellRequest;
import net.knightsandkings.knk.core.production.shop.ShopSellResult;
import net.knightsandkings.knk.core.production.shop.ShopText;
import net.knightsandkings.knk.paper.lootbox.LootboxDelivery;
import net.knightsandkings.knk.paper.menu.MenuService;
import net.knightsandkings.knk.paper.production.terminal.DisplayWriter;
import net.knightsandkings.knk.paper.production.terminal.StorageTerminalModule;

/**
 * The sell menu (PandiO/knk-workspace#687, shops DESIGN § 3.2 and § 8, A18): opened from the shop menu's Sell chest.
 * <ol>
 *   <li>A click on a stack in the player's inventory adds it (item identity, #141: a blueprint, or a unique item as its
 *       instance); a click on a picked stack takes it out. After each change {@code sell-quote} shows the buy-back price per
 *       item, a refusal per line and the total.</li>
 *   <li>The Sell chest sells all lines or none at the total shown ({@code expectedTotal}, {@code confirmed: true}, a fresh
 *       idempotency key; a lost answer is sent once more with the same key). The picked items are taken from the
 *       inventory only when the sale is sent, after checking they are still there, and given back when it is refused.</li>
 *   <li>409 {@code PriceChanged}: a new quote, and the player clicks again; every other refusal is told to the player.</li>
 *   <li>Refused before the API: display samples, damaged items and enchanted vanilla items (they would be resold repaired
 *       or without their enchantments, as in the terminal), unresolved items, and selling during a siege.</li>
 * </ol>
 */
public final class SellModule implements Listener {

    private static final Logger LOGGER = Logger.getLogger(SellModule.class.getName());

    private final Plugin plugin;
    private final ShopService shops;
    private final Function<UUID, Integer> userIds;
    private final Function<UUID, Long> coins;
    private final Predicate<UUID> inSiege;
    private final Supplier<Function<ItemStack, ItemIdentityResolution>> identity;
    private final Executor mainThread;
    private final Supplier<MenuService> menuService;

    public SellModule(Plugin plugin, ShopService shops, Function<UUID, Integer> userIds, Function<UUID, Long> coins,
                      Predicate<UUID> inSiege, Supplier<Function<ItemStack, ItemIdentityResolution>> identity,
                      Executor mainThread, Supplier<MenuService> menuService) {
        this.plugin = plugin;
        this.shops = shops;
        this.userIds = userIds;
        this.coins = coins != null ? coins : id -> null;
        this.inSiege = inSiege != null ? inSiege : id -> false;
        this.identity = identity != null ? identity : () -> null;
        this.mainThread = mainThread;
        this.menuService = menuService;
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void stop() {
        HandlerList.unregisterAll(this);
    }

    /** Main thread: opens the sell menu of {@code shopId}; null when refused. */
    public SellView open(Player player, int shopId) {
        if (inSiege.test(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You can't sell to shops during a siege.");
            return null;
        }
        Integer userId = userIds.apply(player.getUniqueId());
        if (userId == null) {
            player.sendMessage(ChatColor.RED + "Your account isn't loaded yet; try again in a moment.");
            return null;
        }
        ShopOffer offer = shops.offer(shopId);
        if (offer != null && !offer.buysFromPlayers()) {
            player.sendMessage(ChatColor.RED + "This shop doesn't buy items from players.");
            return null;
        }
        SellView view = new SellView(shopId, userId, offer);
        view.show(coins.apply(player.getUniqueId()));
        player.openInventory(view.getInventory());
        return view;
    }

    // ===== clicks =====

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof SellView view) || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        Inventory clicked = event.getClickedInventory();
        if (clicked == null || view.busy()) {
            return;
        }
        if (clicked.equals(view.getInventory())) {
            clickTop(player, view, event.getSlot());
        } else if (clicked.equals(player.getInventory())) {
            pick(player, view, event.getSlot());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SellView) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        shops.forget(event.getPlayer().getUniqueId());
    }

    void clickTop(Player player, SellView view, int slot) {
        if (slot == SellMenuLayout.SELL_SLOT) {
            sell(player, view);
        } else if (slot == SellMenuLayout.BACK_SLOT) {
            MenuService service = menuService.get();
            if (service != null) {
                service.openMenu(player, ShopMenuKeys.SHOP_MENU,
                        MenuContextParams.of(Map.of(ShopMenuKeys.CTX_SHOP_ID, String.valueOf(view.shopId()))));
            } else {
                player.closeInventory();
            }
        } else {
            int index = SellMenuLayout.pickIndex(slot);
            if (index >= 0 && view.remove(index) != null) {
                requote(player, view);
            }
        }
    }

    /** A click on the player's own inventory slot {@code slot}: adds that stack to the basket. */
    void pick(Player player, SellView view, int slot) {
        ItemStack stack = player.getInventory().getItem(slot);
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        if (view.basket().holdsSlot(slot)) {
            player.sendMessage(ChatColor.YELLOW + "That stack is already in the sale; click it in the menu to take it out.");
            return;
        }
        if (view.basket().full()) {
            player.sendMessage(ChatColor.YELLOW + "You can sell " + SellBasket.CAPACITY + " stacks at a time.");
            return;
        }
        SellBasket.Pick pick;
        try {
            pick = resolve(stack, slot);
        } catch (IllegalArgumentException refused) {
            player.sendMessage(ChatColor.RED + refused.getMessage());
            return;
        }
        if (view.add(pick, stack)) {
            requote(player, view);
        }
    }

    /** What the stack in {@code slot} sells as; throws with the reason when it can't be sold. */
    SellBasket.Pick resolve(ItemStack stack, int slot) {
        String refusal = refusal(stack);
        if (refusal != null) {
            throw new IllegalArgumentException(refusal);
        }
        Function<ItemStack, ItemIdentityResolution> resolver = identity.get();
        if (resolver == null) {
            throw new IllegalArgumentException("Item identity is off on this server (items.identity.enabled), so shops can't buy items.");
        }
        ItemIdentityResolution resolution = resolver.apply(stack);
        if (resolution == null || !resolution.resolved() || (resolution.blueprintId() == null && resolution.instanceId() == null)) {
            throw new IllegalArgumentException(ShopRefusal.NOT_RESOLVED.message());
        }
        String name = stack.hasItemMeta() && stack.getItemMeta().hasDisplayName() ? stack.getItemMeta().getDisplayName()
                : stack.getType().name();
        if (resolution.kind() == ItemIdentityResolution.Kind.INSTANCE) {
            return new SellBasket.Pick(slot, resolution.blueprintId(), resolution.instanceId(), 1, name);
        }
        if (resolution.kind() == ItemIdentityResolution.Kind.VANILLA_MATCH && StorageTerminalModule.enchanted(stack)) {
            throw new IllegalArgumentException("Enchanted items can't be sold as plain items (they would lose their enchantments).");
        }
        return new SellBasket.Pick(slot, resolution.blueprintId(), null, stack.getAmount(), name);
    }

    /**
     * Why an item may not be sold even when it resolves (as in the terminal, L18): a display sample is no real item, and
     * a damaged item would be resold repaired.
     */
    static String refusal(ItemStack stack) {
        if (DisplayWriter.isSample(stack)) {
            return "That is a display sample, not a real item.";
        }
        ItemMeta meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
        if (meta instanceof Damageable damageable && damageable.hasDamage() && damageable.getDamage() > 0) {
            return "Damaged items can't be sold to a shop (it would resell them repaired).";
        }
        return null;
    }

    void requote(Player player, SellView view) {
        if (view.basket().isEmpty()) {
            view.quoted(view.startQuote(), null, null);
            view.show(coins.apply(player.getUniqueId()));
            return;
        }
        int seq = view.startQuote();
        view.show(coins.apply(player.getUniqueId()));
        CompletableFuture<ShopSellQuote> call;
        try {
            call = shops.api().sellQuote(view.shopId(), ShopSellRequest.quote(view.userId(), view.basket().lines()));
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        call.whenComplete((quote, error) -> mainThread.execute(() -> {
            String why = error == null ? null : ShopService.status(error) == 409
                    ? ShopRefusal.fromBody(ShopService.body(error)).message()
                    : "Could not ask the shop (" + ShopService.why(error) + ")";
            if (view.quoted(seq, error == null ? quote : null, why) && open(player, view)) {
                view.show(coins.apply(player.getUniqueId()));
            }
        }));
    }

    /** The Sell chest: sells every line at the total shown. */
    CompletableFuture<ShopSellResult> sell(Player player, SellView view) {
        ShopSellQuote quote = view.quote();
        if (view.basket().isEmpty()) {
            return done(player, ChatColor.YELLOW + "Click items in your inventory to add them first.");
        }
        if (quote == null) {
            return done(player, ChatColor.YELLOW + "The shop is still working out the prices; click again in a moment.");
        }
        if (!quote.accepted()) {
            return done(player, ChatColor.RED + "Take out the marked items first: the shop buys all of them or none.");
        }
        if (!quote.townCanPay()) {
            return done(player, ChatColor.RED + ShopRefusal.TOWN_FUNDS_INSUFFICIENT.message());
        }
        if (inSiege.test(player.getUniqueId())) {
            return done(player, ChatColor.RED + "You can't sell to shops during a siege.");
        }
        List<SellBasket.Pick> picks = view.basket().picks();
        List<ItemStack> held = take(player, picks);
        if (held == null) {
            for (int i = picks.size() - 1; i >= 0; i--) {
                if (!stillThere(player, picks.get(i))) {
                    view.remove(i);
                }
            }
            requote(player, view);
            return done(player, ChatColor.YELLOW + "Your inventory changed; check the sale and click again.");
        }
        view.busy(true);
        List<ShopSellLine> lines = view.basket().lines();
        String key = ShopKeys.sale(view.shopId(), view.userId());
        ShopSellRequest request = ShopSellRequest.sell(view.userId(), lines, quote.total(), key);
        CompletableFuture<ShopSellResult> done = new CompletableFuture<>();
        sellWithRetry(view.shopId(), request, 1).whenComplete((result, error) -> mainThread.execute(() -> {
            view.busy(false);
            if (error == null) {
                view.clear();
                shops.forget(view.shopId());
                player.sendMessage(ShopService.colour(ShopText.sold(result.total(), lines.size(), result.coinsAfter())));
                if (open(player, view)) {
                    player.closeInventory();
                }
                done.complete(result);
                return;
            }
            giveBack(player, held);
            int status = ShopService.status(error);
            if (status == 409) {
                ShopRefusal refusal = ShopRefusal.fromBody(ShopService.body(error));
                player.sendMessage((refusal == ShopRefusal.PRICE_CHANGED ? ChatColor.YELLOW : ChatColor.RED) + refusal.message());
            } else if (status <= 0 || status >= 500) {
                LOGGER.warning("Shop " + view.shopId() + ": sale " + key + " by " + player.getName() + " got no answer ("
                        + ShopService.why(error) + "); the items went back to the player");
                player.sendMessage(ChatColor.RED + "The shop didn't answer; your items are back. If you were paid anyway, show staff this code: " + key);
            } else {
                player.sendMessage(ChatColor.RED + "Could not sell (" + ShopService.why(error) + "). Your items are back.");
            }
            if (open(player, view)) {
                requote(player, view);
            }
            done.complete(null);
        }));
        return done;
    }

    private CompletableFuture<ShopSellResult> sellWithRetry(int shopId, ShopSellRequest request, int attempt) {
        CompletableFuture<ShopSellResult> call;
        try {
            call = shops.api().sell(shopId, request);
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        return call.handle((result, error) -> {
            if (error == null) {
                return CompletableFuture.completedFuture(result);
            }
            if (attempt >= 2 || !ShopService.retryable(error)) {
                return CompletableFuture.<ShopSellResult>failedFuture(error);
            }
            return sellWithRetry(shopId, request, attempt + 1);
        }).thenCompose(f -> f);
    }

    /** Takes the picked amounts out of their slots; null (and nothing taken) when one is no longer there. */
    List<ItemStack> take(Player player, List<SellBasket.Pick> picks) {
        for (SellBasket.Pick pick : picks) {
            if (!stillThere(player, pick)) {
                return null;
            }
        }
        List<ItemStack> held = new ArrayList<>();
        for (SellBasket.Pick pick : picks) {
            ItemStack stack = player.getInventory().getItem(pick.inventorySlot());
            ItemStack taken = stack.clone();
            taken.setAmount((int) pick.amount());
            held.add(taken);
            int left = stack.getAmount() - (int) pick.amount();
            if (left <= 0) {
                player.getInventory().setItem(pick.inventorySlot(), null);
            } else {
                stack.setAmount(left);
                player.getInventory().setItem(pick.inventorySlot(), stack);
            }
        }
        return held;
    }

    /** The pick's slot still holds at least that many of the same item (re-resolved). */
    boolean stillThere(Player player, SellBasket.Pick pick) {
        ItemStack stack = player.getInventory().getItem(pick.inventorySlot());
        if (stack == null || stack.getType().isAir() || stack.getAmount() < pick.amount()) {
            return false;
        }
        try {
            SellBasket.Pick now = resolve(stack, pick.inventorySlot());
            return java.util.Objects.equals(now.blueprintId(), pick.blueprintId())
                    && java.util.Objects.equals(now.instanceId(), pick.instanceId());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static void giveBack(Player player, List<ItemStack> held) {
        if (!player.isOnline()) {
            held.forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
            LOGGER.warning("Shop sale: " + player.getName() + " left before a refused sale; the items were dropped where they stood");
            return;
        }
        List<ItemStack> spill = new ArrayList<>();
        for (ItemStack item : held) {
            spill.addAll(player.getInventory().addItem(item).values());
        }
        if (!spill.isEmpty()) {
            LootboxDelivery.dropOwned(player, spill);
        }
    }

    private static boolean open(Player player, SellView view) {
        return player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == view;
    }

    private static CompletableFuture<ShopSellResult> done(Player player, String message) {
        player.sendMessage(message);
        return CompletableFuture.completedFuture(null);
    }
}
