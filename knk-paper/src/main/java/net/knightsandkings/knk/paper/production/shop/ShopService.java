package net.knightsandkings.knk.paper.production.shop;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.ports.api.ShopsApi;
import net.knightsandkings.knk.core.production.shop.ShopAmounts;
import net.knightsandkings.knk.core.production.shop.ShopItemRef;
import net.knightsandkings.knk.core.production.shop.ShopKeys;
import net.knightsandkings.knk.core.production.shop.ShopMenuKeys;
import net.knightsandkings.knk.core.production.shop.ShopOffer;
import net.knightsandkings.knk.core.production.shop.ShopOfferItem;
import net.knightsandkings.knk.core.production.shop.ShopPurchaseRequest;
import net.knightsandkings.knk.core.production.shop.ShopPurchaseResult;
import net.knightsandkings.knk.core.production.shop.ShopQuote;
import net.knightsandkings.knk.core.production.shop.ShopRefusal;
import net.knightsandkings.knk.core.production.shop.ShopText;
import net.knightsandkings.knk.paper.lootbox.LootboxDelivery;
import net.knightsandkings.knk.paper.menu.MenuService;
import net.knightsandkings.knk.paper.production.inbox.InboxModule;
import net.knightsandkings.knk.paper.production.terminal.BlueprintItems;

/**
 * The shop menus' state and calls (PandiO/knk-workspace#687, shops DESIGN § 3.1 and § 8): the offer per shop, the quote
 * per player and item, the amount a player picked, and the purchase after the confirmation. Providers run on the main
 * thread and never do I/O: they read what the last call left and start a call when nothing is there; when it lands the
 * open shop menus repaint. Only built with {@code production.enabled} (A21).
 * <ol>
 *   <li>A purchase sends the price the player saw ({@code expectedUnitPrice}), {@code confirmed: true}, where it goes
 *       ({@code deliverTo}) and a fresh idempotency key; a call with no answer (or a 5xx) is sent once more with the same
 *       key, so it is booked once.</li>
 *   <li>{@code Inventory}: the room is checked first and exactly the bought amount is placed; what no longer fits drops
 *       at the player's feet, owner-locked. {@code Inbox}: the API delivers through P7.</li>
 *   <li>409 {@code PriceChanged}: the confirmation stays open with the new price and asks again; every other refusal is
 *       told to the player and nothing is placed.</li>
 *   <li>Unique items are listed but not bought in game until the instance-to-item builder exists (#897).</li>
 * </ol>
 */
public final class ShopService {

    private static final Logger LOGGER = Logger.getLogger(ShopService.class.getName());
    static final long OFFER_TTL_MILLIS = 10_000L;
    static final long QUOTE_TTL_MILLIS = 10_000L;
    static final int MAX_REMEMBERED = 512;

    private record Cached<T>(long at, T value) {
    }

    private final ShopsApi api;
    private final BlueprintItems items;
    private final Function<UUID, Integer> userIds;
    private final Function<UUID, Long> coins;
    private final Predicate<UUID> inSiege;
    private final Executor mainThread;
    private final Clock clock;
    private final Supplier<MenuService> menuService;
    private final Map<Integer, Cached<ShopOffer>> offers = new ConcurrentHashMap<>();
    private final Map<String, Cached<ShopQuote>> quotes = new ConcurrentHashMap<>();
    private final Map<String, Long> amounts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> changedFrom = new ConcurrentHashMap<>();
    private final Set<String> fetching = ConcurrentHashMap.newKeySet();
    private final Map<Integer, CompletableFuture<ShopOffer>> reading = new ConcurrentHashMap<>();
    private final Set<UUID> buying = ConcurrentHashMap.newKeySet();

    public ShopService(ShopsApi api, BlueprintItems items, Function<UUID, Integer> userIds, Function<UUID, Long> coins,
                       Predicate<UUID> inSiege, Executor mainThread, Clock clock, Supplier<MenuService> menuService) {
        this.api = api;
        this.items = items;
        this.userIds = userIds;
        this.coins = coins != null ? coins : id -> null;
        this.inSiege = inSiege != null ? inSiege : id -> false;
        this.mainThread = mainThread;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.menuService = menuService;
    }

    public ShopsApi api() {
        return api;
    }

    public BlueprintItems items() {
        return items;
    }

    // ===== opening =====

    /**
     * Main thread: reads the shop's offer and opens the shop menu ({@code /knk shop <id>}, later the shopkeeper). Completes
     * with true when the menu opened.
     */
    public CompletableFuture<Boolean> open(Player player, int shopId) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        if (inSiege.test(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "Shops are closed to you during a siege.");
            done.complete(false);
            return done;
        }
        fetchOffer(shopId).whenComplete((offer, error) -> mainThread.execute(() -> {
            if (error != null) {
                player.sendMessage(ChatColor.RED + (status(error) == 404 ? "There is no shop #" + shopId + "."
                        : "Could not open the shop (" + why(error) + ")."));
                done.complete(false);
                return;
            }
            MenuService service = menuService.get();
            if (service == null || !player.isOnline()) {
                done.complete(false);
                return;
            }
            service.openMenu(player, ShopMenuKeys.SHOP_MENU,
                    MenuContextParams.of(Map.of(ShopMenuKeys.CTX_SHOP_ID, String.valueOf(shopId))));
            done.complete(true);
        }));
        return done;
    }

    // ===== reads for the menus (main thread, no I/O) =====

    /** The shop's offer as last read, or null (a read starts). */
    public ShopOffer offer(int shopId) {
        Cached<ShopOffer> cached = offers.get(shopId);
        if (cached == null || clock.millis() - cached.at() >= OFFER_TTL_MILLIS) {
            fetchOffer(shopId).exceptionally(error -> {
                LOGGER.log(Level.FINE, "shops: offer of shop " + shopId + " could not be read", error);
                return null;
            });
        }
        return cached == null ? null : cached.value();
    }

    /** Reads the offer and builds its items (so the rows show the real items), then repaints the open shop menus. */
    CompletableFuture<ShopOffer> fetchOffer(int shopId) {
        CompletableFuture<ShopOffer> running = reading.get(shopId);
        if (running != null) {
            return running;
        }
        CompletableFuture<ShopOffer> call;
        try {
            call = api.offer(shopId);
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        CompletableFuture<ShopOffer> read = call.thenCompose(offer -> prefetch(offer).thenApply(ignored -> offer));
        reading.put(shopId, read);
        return read.whenComplete((offer, error) -> {
            reading.remove(shopId, read);
            if (error == null && offer != null) {
                offers.put(shopId, new Cached<>(clock.millis(), offer));
                trim(offers);
                mainThread.execute(this::repaint);
            }
        });
    }

    private CompletableFuture<Void> prefetch(ShopOffer offer) {
        if (offer == null || items == null) {
            return CompletableFuture.completedFuture(null);
        }
        List<CompletableFuture<?>> calls = new ArrayList<>();
        for (ShopOfferItem item : offer.items()) {
            if (!item.unique()) {
                calls.add(items.template(item.itemBlueprintId(), item.itemBlueprintName()));
            }
        }
        return CompletableFuture.allOf(calls.toArray(new CompletableFuture[0]));
    }

    /** The root {@code shop} for a player and the menu's context. */
    public ShopView view(Player player, MenuContextParams ctx) {
        Integer shopId = shopId(ctx);
        if (shopId == null || player == null) {
            return new ShopView(shopId == null ? 0 : shopId, null, null, 1, null, null, null);
        }
        ShopOffer offer = offer(shopId);
        ShopItemRef ref = ShopItemRef.parse(ctx.get(ShopMenuKeys.CTX_ITEM));
        ShopOfferItem item = offer == null ? null : offer.find(ref);
        long amount = amountFor(player.getUniqueId(), shopId, ref, item, ctx);
        ShopQuote quote = null;
        Long from = null;
        if (item != null && ctx.has(ShopMenuKeys.CTX_AMOUNT)) { // the confirmation
            quote = quote(player, shopId, item.ref(), amount);
            from = changedFrom.get(player.getUniqueId());
        }
        return new ShopView(shopId, offer, item, amount, quote, from, coins.apply(player.getUniqueId()));
    }

    /** Rows of {@code shops.offer}: the catalogue, a page of it. */
    public CompletableFuture<Page<ShopOfferRow>> offerRows(Player player, MenuContextParams ctx, PagedQuery query) {
        Integer shopId = shopId(ctx);
        ShopOffer offer = shopId == null || player == null ? null : offer(shopId);
        List<ShopOfferRow> rows = new ArrayList<>();
        if (offer != null) {
            for (ShopOfferItem item : offer.items()) {
                rows.add(row(item));
            }
        }
        return CompletableFuture.completedFuture(page(rows, query));
    }

    /** The one row of {@code shops.item}: the item of {@code ctx.item}. */
    public CompletableFuture<Page<ShopOfferRow>> itemRow(Player player, MenuContextParams ctx) {
        Integer shopId = shopId(ctx);
        ShopOffer offer = shopId == null || player == null ? null : offer(shopId);
        ShopOfferItem item = offer == null ? null : offer.find(ShopItemRef.parse(ctx.get(ShopMenuKeys.CTX_ITEM)));
        List<ShopOfferRow> rows = item == null ? List.of() : List.of(row(item));
        return CompletableFuture.completedFuture(new Page<>(rows, rows.size(), 1, Math.max(1, rows.size())));
    }

    ShopOfferRow row(ShopOfferItem item) {
        return new ShopOfferRow(item, items == null || item.unique() ? null : items.cached(item.itemBlueprintId()));
    }

    /** Condition {@code shops.buys}: the shop buys from players (the sell button). */
    public boolean buys(MenuContextParams ctx) {
        Integer shopId = shopId(ctx);
        ShopOffer offer = shopId == null ? null : offer(shopId);
        return offer != null && offer.buysFromPlayers();
    }

    /** Condition {@code shops.buyable}: the item is on offer, in stock and not a unique item; a reason when not. */
    public String notBuyable(MenuContextParams ctx) {
        Integer shopId = shopId(ctx);
        ShopOffer offer = shopId == null ? null : offer(shopId);
        ShopOfferItem item = offer == null ? null : offer.find(ShopItemRef.parse(ctx == null ? null : ctx.get(ShopMenuKeys.CTX_ITEM)));
        if (item == null) {
            return "This item is no longer for sale here.";
        }
        if (item.soldOut()) {
            return ShopRefusal.SOLD_OUT.message();
        }
        if (item.unique()) {
            return "Unique items can't be bought in game yet.";
        }
        return null;
    }

    // ===== amount =====

    /** Action {@code shops.amount}: the next amount step for this item. */
    public void cycleAmount(Player player, MenuContextParams ctx) {
        Integer shopId = shopId(ctx);
        ShopItemRef ref = ShopItemRef.parse(ctx == null ? null : ctx.get(ShopMenuKeys.CTX_ITEM));
        if (player == null || shopId == null || ref == null) {
            return;
        }
        String key = amountKey(player.getUniqueId(), shopId, ref);
        amounts.put(key, ShopAmounts.next(amounts.getOrDefault(key, 1L)));
        trim(amounts);
        repaint(player);
    }

    long amountFor(UUID playerId, int shopId, ShopItemRef ref, ShopOfferItem item, MenuContextParams ctx) {
        String fromCtx = ctx == null ? null : ctx.get(ShopMenuKeys.CTX_AMOUNT);
        long chosen;
        if (fromCtx != null && !fromCtx.isBlank()) {
            try {
                chosen = Long.parseLong(fromCtx.trim());
            } catch (NumberFormatException e) {
                chosen = 1L;
            }
        } else {
            chosen = ref == null ? 1L : amounts.getOrDefault(amountKey(playerId, shopId, ref), 1L);
        }
        return ShopAmounts.of(chosen, item);
    }

    // ===== quote =====

    /** The quote for this amount as last read, or null (a quote starts). */
    ShopQuote quote(Player player, int shopId, ShopItemRef ref, long amount) {
        String key = quoteKey(player.getUniqueId(), shopId, ref, amount);
        Cached<ShopQuote> cached = quotes.get(key);
        if ((cached == null || clock.millis() - cached.at() >= QUOTE_TTL_MILLIS) && fetching.add(key)) {
            Integer userId = userIds.apply(player.getUniqueId());
            CompletableFuture<ShopQuote> call;
            try {
                call = api.quote(shopId, ShopPurchaseRequest.quote(userId == null ? 0 : userId, ref, amount));
            } catch (RuntimeException e) {
                call = CompletableFuture.failedFuture(e);
            }
            call.whenComplete((quote, error) -> {
                fetching.remove(key);
                if (error != null) {
                    LOGGER.log(Level.FINE, "shops: quote failed", error);
                    return;
                }
                quotes.put(key, new Cached<>(clock.millis(), quote));
                trim(quotes);
                mainThread.execute(() -> repaint(player));
            });
        }
        return cached == null ? null : cached.value();
    }

    // ===== purchase =====

    /**
     * Action {@code shops.purchase} (main thread): buys the confirmation's amount at the quoted price. Completes with the
     * result, or null when nothing was bought.
     */
    public CompletableFuture<ShopPurchaseResult> purchase(Player player, MenuContextParams ctx, ShopPurchaseRequest.DeliverTo deliverTo) {
        Integer shopId = shopId(ctx);
        ShopItemRef ref = ShopItemRef.parse(ctx == null ? null : ctx.get(ShopMenuKeys.CTX_ITEM));
        if (player == null || shopId == null || ref == null) {
            return CompletableFuture.completedFuture(null);
        }
        ShopOffer offer = offer(shopId);
        ShopOfferItem item = offer == null ? null : offer.find(ref);
        if (item == null) {
            return refuse(player, "This item is no longer for sale here.");
        }
        if (item.unique()) {
            return refuse(player, "Unique items can't be bought in game yet; ask a staff member.");
        }
        if (inSiege.test(player.getUniqueId())) {
            return refuse(player, "Shops are closed to you during a siege.");
        }
        Integer userId = userIds.apply(player.getUniqueId());
        if (userId == null) {
            return refuse(player, "Your account isn't loaded yet; try again in a moment.");
        }
        long amount = amountFor(player.getUniqueId(), shopId, ref, item, ctx);
        ShopQuote quote = quote(player, shopId, ref, amount);
        if (quote == null) {
            return refuse(player, "The shop is still working out the price; click again in a moment.");
        }
        if (!quote.buyable()) {
            return refuse(player, ShopRefusal.describe(quote.refusal(), quote.refusalMessage()));
        }
        BlueprintItems.Template template = items == null ? null : items.cached(item.itemBlueprintId());
        if (deliverTo == ShopPurchaseRequest.DeliverTo.INVENTORY) {
            if (template == null) {
                return refuse(player, "This item can't be built on this server right now; send it to your reward inbox instead.");
            }
            if (InboxModule.room(player, template) < amount) {
                return refuse(player, "Your inventory has no room for " + ShopText.units(amount)
                        + ". Make room, or send it to your reward inbox.");
            }
        }
        if (!buying.add(player.getUniqueId())) {
            return CompletableFuture.completedFuture(null);
        }
        String key = ShopKeys.purchase(shopId, userId);
        ShopPurchaseRequest request = ShopPurchaseRequest.purchase(userId, ref, amount, quote.unitPrice(), deliverTo, key);
        CompletableFuture<ShopPurchaseResult> done = new CompletableFuture<>();
        purchaseWithRetry(shopId, request, 1).whenComplete((result, error) -> mainThread.execute(() -> {
            buying.remove(player.getUniqueId());
            done.complete(error == null ? afterPurchase(player, shopId, item, template, request, result)
                    : afterRefusal(player, shopId, ref, amount, quote, key, error));
        }));
        return done;
    }

    private ShopPurchaseResult afterPurchase(Player player, int shopId, ShopOfferItem item, BlueprintItems.Template template,
                                             ShopPurchaseRequest request, ShopPurchaseResult result) {
        boolean inbox = request.deliverTo() == ShopPurchaseRequest.DeliverTo.INBOX;
        long amount = result.amount() > 0 ? result.amount() : request.amount();
        if (!inbox && template != null) {
            List<ItemStack> spill = player.isOnline() ? InboxModule.give(player, template, amount) : List.of();
            if (!spill.isEmpty()) {
                LootboxDelivery.dropOwned(player, spill);
                player.sendMessage(ChatColor.GRAY + "Your inventory filled up meanwhile: the rest was dropped at your feet.");
            }
            if (!player.isOnline()) {
                LOGGER.warning("Shop " + shopId + ": " + player.getName() + " left before purchase " + request.idempotencyKey()
                        + " was placed (" + amount + " x blueprint " + item.itemBlueprintId() + "); give it back by hand");
            }
        }
        changedFrom.remove(player.getUniqueId());
        forget(shopId);
        player.sendMessage(colour(ShopText.bought(amount, item.itemBlueprintName(),
                result.totalPrice() > 0 ? result.totalPrice() : amount * request.expectedUnitPrice(), inbox, result.coinsAfter())));
        MenuService service = menuService.get();
        if (service != null && player.isOnline()) {
            service.goBack(player); // back to the item information menu, which shows the new stock and coins
        }
        return result;
    }

    private ShopPurchaseResult afterRefusal(Player player, int shopId, ShopItemRef ref, long amount, ShopQuote quote,
                                            String key, Throwable error) {
        int status = status(error);
        if (status == 409) {
            ShopRefusal refusal = ShopRefusal.fromBody(body(error));
            if (refusal == ShopRefusal.PRICE_CHANGED) {
                changedFrom.put(player.getUniqueId(), quote.unitPrice());
                quotes.remove(quoteKey(player.getUniqueId(), shopId, ref, amount));
                offers.remove(shopId);
                player.sendMessage(ChatColor.YELLOW + refusal.message());
                repaint(player); // the confirmation asks again with the new price
                return null;
            }
            player.sendMessage(ChatColor.RED + refusal.message() + " Nothing was charged.");
        } else if (status <= 0 || status >= 500) {
            LOGGER.warning("Shop " + shopId + ": purchase " + key + " by " + player.getName() + " got no answer ("
                    + why(error) + "); check the town account ledger for this key");
            player.sendMessage(ChatColor.RED + "The shop didn't answer. If coins were taken, show staff this code: " + key);
        } else {
            player.sendMessage(ChatColor.RED + "Could not buy (" + why(error) + "). Nothing was charged.");
        }
        forget(shopId);
        repaint(player);
        return null;
    }

    private CompletableFuture<ShopPurchaseResult> purchaseWithRetry(int shopId, ShopPurchaseRequest request, int attempt) {
        CompletableFuture<ShopPurchaseResult> call;
        try {
            call = api.purchase(shopId, request);
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        return call.handle((result, error) -> {
            if (error == null) {
                return CompletableFuture.completedFuture(result);
            }
            if (attempt >= 2 || !retryable(error)) {
                return CompletableFuture.<ShopPurchaseResult>failedFuture(error);
            }
            return purchaseWithRetry(shopId, request, attempt + 1);
        }).thenCompose(f -> f);
    }

    // ===== helpers =====

    /** Drops the cached offer and quotes of a shop (after a trade), so the menus read them again. */
    public void forget(int shopId) {
        offers.remove(shopId);
        String marker = "|" + shopId + "|";
        quotes.keySet().removeIf(k -> k.contains(marker));
    }

    /** Forgets a player's picks and price notes (on quit). */
    public void forget(UUID playerId) {
        String prefix = playerId + "|";
        amounts.keySet().removeIf(k -> k.startsWith(prefix));
        quotes.keySet().removeIf(k -> k.startsWith(prefix));
        changedFrom.remove(playerId);
        buying.remove(playerId);
    }

    private void repaint() {
        MenuService service = menuService.get();
        if (service != null) {
            service.refreshOpenMenus(open -> open.menuKey() != null && ShopMenuKeys.MENU_KEYS.contains(open.menuKey()));
        }
    }

    private void repaint(Player player) {
        MenuService service = menuService.get();
        if (service != null && player != null && player.isOnline()) {
            service.refreshOpenMenu(player);
        }
    }

    private CompletableFuture<ShopPurchaseResult> refuse(Player player, String message) {
        player.sendMessage(ChatColor.RED + message);
        return CompletableFuture.completedFuture(null);
    }

    static Integer shopId(MenuContextParams ctx) {
        String raw = ctx == null ? null : ctx.get(ShopMenuKeys.CTX_SHOP_ID);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int id = Integer.parseInt(raw.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String amountKey(UUID playerId, int shopId, ShopItemRef ref) {
        return playerId + "|" + shopId + "|" + ref;
    }

    private static String quoteKey(UUID playerId, int shopId, ShopItemRef ref, long amount) {
        return playerId + "|" + shopId + "|" + ref + "|" + amount;
    }

    private static <K, V> void trim(Map<K, V> map) {
        if (map.size() > MAX_REMEMBERED) {
            map.clear();
        }
    }

    static <T> Page<T> page(List<T> rows, PagedQuery query) {
        int size = Math.max(1, query == null ? Math.max(1, rows.size()) : query.pageSize());
        int number = Math.max(1, query == null ? 1 : query.pageNumber());
        int from = Math.min(rows.size(), (number - 1) * size);
        int to = Math.min(rows.size(), from + size);
        return new Page<>(List.copyOf(rows.subList(from, to)), rows.size(), number, size);
    }

    static String colour(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    static ApiException apiException(Throwable error) {
        Throwable t = error;
        while (t != null) {
            if (t instanceof ApiException api) {
                return api;
            }
            if (!(t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() == null) {
                return null;
            }
            t = t.getCause();
        }
        return null;
    }

    static int status(Throwable error) {
        ApiException api = apiException(error);
        return api == null ? 0 : Math.max(0, api.getStatusCode());
    }

    static String body(Throwable error) {
        ApiException api = apiException(error);
        return api == null ? null : api.getResponseBody();
    }

    static boolean retryable(Throwable error) {
        int status = status(error);
        return status <= 0 || status >= 500;
    }

    static String why(Throwable error) {
        int status = status(error);
        if (status > 0) {
            return "HTTP " + status;
        }
        Throwable t = error;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t == null || t.getMessage() == null ? "no answer" : t.getMessage();
    }
}
