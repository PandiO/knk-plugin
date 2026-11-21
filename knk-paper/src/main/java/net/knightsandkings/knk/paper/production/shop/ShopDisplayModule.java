package net.knightsandkings.knk.paper.production.shop;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.knightsandkings.knk.core.ports.api.ShopDisplaySpotsApi;
import net.knightsandkings.knk.core.production.shop.ShopItemRef;
import net.knightsandkings.knk.core.production.shop.ShopOffer;
import net.knightsandkings.knk.core.production.shop.ShopOfferItem;
import net.knightsandkings.knk.core.production.shop.display.DisplaySelection;
import net.knightsandkings.knk.core.production.shop.display.DisplaySettings;
import net.knightsandkings.knk.core.production.shop.display.DisplaySpot;
import net.knightsandkings.knk.core.production.shop.display.DisplaySpotIndex;
import net.knightsandkings.knk.core.production.shop.display.DisplayText;
import net.knightsandkings.knk.paper.production.terminal.BlueprintItems;

/**
 * Display stalls (P12, PandiO/knk-workspace#250, shops DESIGN § 7): shows a shop's items on its registered display spots
 * and turns clicks on them into the shop menus of P10b (#687). Only built with {@code production.enabled} (A21).
 * <ol>
 *   <li>The spots are the API's list ({@code api/ShopDisplaySpots}), read at start, every {@code refresh-minutes} and after
 *       {@code /knk shop display}; never found by scanning.</li>
 *   <li>Every {@code selection-refresh-seconds}, and when a chunk with spots loads or a trade changes the offer, the shop's
 *       offer and best sellers ({@code ShopTradeStats}, {@code best-seller-hours}) are read off the main thread and the
 *       spots are filled by {@link DisplaySelection}: the spot's pin, the catalogue's pins, then in-stock items and best
 *       sellers; sold-out items greyed out or hidden.</li>
 *   <li>The entities ({@link ShopDisplayPresenter}) are non-persistent; once a second the spots whose entities went away
 *       are spawned again, and stray copies are removed when their chunk's entities load.</li>
 *   <li>Right-click: the item information menu ({@code shops.item}), no purchase. Shift-right-click: the quick buy, which
 *       opens the purchase confirmation ({@code shops.confirm}); every purchase is confirmed there.</li>
 * </ol>
 */
public final class ShopDisplayModule implements Listener {

    private static final Logger LOGGER = Logger.getLogger(ShopDisplayModule.class.getName());
    /** Best sellers change hourly: read them again at most this often per shop. */
    static final long SOLD_TTL_MILLIS = 10 * 60_000L;
    /** Two clicks of one player on stalls within this are one (the off-hand and repeated packets). */
    static final long CLICK_DEBOUNCE_MILLIS = 250L;
    /** How far {@code /knk shop display add} looks for the block. */
    static final int TARGET_RANGE = 6;

    /** Opens the item information menu or the purchase confirmation ({@link ShopService#openItem}). */
    @FunctionalInterface
    public interface Opener {
        CompletableFuture<?> open(Player player, int shopId, ShopItemRef ref, boolean quickBuy);
    }

    private record Sold(long at, Map<Integer, Long> units) {
    }

    private final Plugin plugin;
    private final DisplaySettings settings;
    private final ShopDisplaySpotsApi api;
    private final ShopService shops;
    private final Opener opener;
    private final Executor mainThread;
    private final Clock clock;
    private final DisplaySpotIndex index = new DisplaySpotIndex();
    private final ShopDisplayPresenter presenter = new ShopDisplayPresenter();
    private final Map<Integer, Map<Integer, DisplaySelection.Shown>> selections = new HashMap<>();
    private final Map<Integer, Sold> sold = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<Void>> refreshing = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private volatile String lastRefresh = "never";
    private final List<BukkitTask> tasks = new ArrayList<>();

    public ShopDisplayModule(Plugin plugin, DisplaySettings settings, ShopDisplaySpotsApi api, ShopService shops, Opener opener,
                             Executor mainThread, Clock clock) {
        this.plugin = plugin;
        this.settings = settings;
        this.api = api;
        this.shops = shops;
        this.opener = opener != null ? opener : shops::openItem;
        this.mainThread = mainThread;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        shops.onOfferChanged(shopId -> mainThread.execute(() -> {
            if (!index.ofShop(shopId).isEmpty()) {
                refreshShop(shopId);
            }
        }));
        for (World world : Bukkit.getWorlds()) {
            presenter.purgeOrphans(world.getEntities());
        }
        long spotsPeriod = settings.refreshMinutes() * 60L * 20L;
        long selectionPeriod = settings.selectionRefreshSeconds() * 20L;
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> reload("timer"), 20L, spotsPeriod));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::refreshLoaded, 40L + selectionPeriod, selectionPeriod));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::respawn, 20L, 20L));
        LOGGER.info("Shop display stalls on: spots re-read every " + settings.refreshMinutes() + " min, shown items chosen every "
                + settings.selectionRefreshSeconds() + " s (best sellers of the last " + settings.bestSellerHours() + " h)");
    }

    public void stop() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        shops.onOfferChanged(null);
        presenter.hideAll();
        selections.clear();
    }

    public DisplaySpotIndex index() {
        return index;
    }

    public ShopDisplayPresenter presenter() {
        return presenter;
    }

    public ShopDisplaySpotsApi api() {
        return api;
    }

    public String lastRefresh() {
        return lastRefresh;
    }

    /** What a spot shows now, or empty. */
    public Optional<DisplaySelection.Shown> shown(int spotId) {
        return index.byId(spotId).map(spot -> selections.getOrDefault(spot.shopId(), Map.of()).get(spotId));
    }

    // ===== reading =====

    /** Re-reads every spot; then fills the stalls in loaded chunks. */
    public CompletableFuture<Integer> reload(String why) {
        CompletableFuture<List<DisplaySpot>> call;
        try {
            call = api.list(null);
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        CompletableFuture<Integer> done = new CompletableFuture<>();
        call.whenComplete((all, error) -> mainThread.execute(() -> {
            if (error != null) {
                lastRefresh = Instant.now() + " (" + why + ": failed, " + ShopService.why(error) + ")";
                LOGGER.log(Level.WARNING, "Shop displays: the display spots could not be read: " + ShopService.why(error));
                done.completeExceptionally(error);
                return;
            }
            List<Integer> before = index.all().stream().map(DisplaySpot::id).toList();
            index.replace(all);
            for (Integer id : before) {
                if (index.byId(id).isEmpty()) {
                    presenter.hide(id);
                }
            }
            selections.keySet().removeIf(shopId -> index.ofShop(shopId).isEmpty());
            lastRefresh = Instant.now() + " (" + why + ", " + all.size() + " spots)";
            refreshLoaded();
            done.complete(all.size());
        }));
        return done;
    }

    /** Chooses again what the stalls in loaded chunks show. */
    void refreshLoaded() {
        for (Integer shopId : index.shops()) {
            if (index.ofShop(shopId).stream().anyMatch(this::loaded)) {
                refreshShop(shopId);
            }
        }
    }

    /** Reads the shop's offer and best sellers off the main thread, then fills its spots. */
    public CompletableFuture<Void> refreshShop(int shopId) {
        CompletableFuture<Void> running = refreshing.get(shopId);
        if (running != null) {
            return running;
        }
        CompletableFuture<ShopOffer> offer = shops.fetchOffer(shopId);
        CompletableFuture<Map<Integer, Long>> units = unitsSold(shopId);
        CompletableFuture<Void> work = offer.thenCombine(units, Read::new)
                .thenCompose(read -> prefetch(read.offer()).thenApply(ignored -> read))
                .thenAccept(read -> mainThread.execute(() -> apply(shopId, read.offer(), read.unitsSold())));
        refreshing.put(shopId, work);
        return work.whenComplete((ignored, error) -> {
            refreshing.remove(shopId, work);
            if (error != null) {
                LOGGER.log(Level.FINE, "Shop displays: shop " + shopId + " could not be read: " + ShopService.why(error));
            }
        });
    }

    private CompletableFuture<Map<Integer, Long>> unitsSold(int shopId) {
        Sold cached = sold.get(shopId);
        if (cached != null && clock.millis() - cached.at() < SOLD_TTL_MILLIS) {
            return CompletableFuture.completedFuture(cached.units());
        }
        CompletableFuture<Map<Integer, Long>> call;
        try {
            call = api.unitsSold(shopId, Instant.now(clock).minus(settings.bestSellerHours(), ChronoUnit.HOURS));
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        return call.handle((units, error) -> {
            if (error != null) {
                LOGGER.log(Level.FINE, "Shop displays: best sellers of shop " + shopId + " could not be read", error);
                return cached != null ? cached.units() : Map.<Integer, Long>of(); // the stalls still fill, without best sellers
            }
            Map<Integer, Long> value = units == null ? Map.of() : Map.copyOf(units);
            sold.put(shopId, new Sold(clock.millis(), value));
            return value;
        });
    }

    private record Read(ShopOffer offer, Map<Integer, Long> unitsSold) {
    }

    /** The items of unique offer items (the offer read builds the others), so the stalls show the real item. */
    private CompletableFuture<Void> prefetch(ShopOffer offer) {
        BlueprintItems items = shops.items();
        if (offer == null || items == null) {
            return CompletableFuture.completedFuture(null);
        }
        List<CompletableFuture<?>> calls = new ArrayList<>();
        for (ShopOfferItem item : offer.items()) {
            if (item.unique() && items.cached(item.itemBlueprintId()) == null) {
                calls.add(items.template(item.itemBlueprintId(), item.itemBlueprintName()).exceptionally(error -> null));
            }
        }
        return CompletableFuture.allOf(calls.toArray(new CompletableFuture[0]));
    }

    /** Main thread: fills the shop's spots from the offer and the best sellers. */
    void apply(int shopId, ShopOffer offer, Map<Integer, Long> unitsSold) {
        List<DisplaySpot> spots = index.ofShop(shopId);
        Map<Integer, DisplaySelection.Shown> chosen = DisplaySelection.select(spots, offer, unitsSold);
        selections.put(shopId, chosen);
        for (DisplaySpot spot : spots) {
            DisplaySelection.Shown shown = chosen.get(spot.id());
            if (shown == null) {
                presenter.hide(spot.id());
            } else if (loaded(spot)) {
                render(shown);
            }
        }
    }

    private void render(DisplaySelection.Shown shown) {
        ShopOfferItem item = shown.item();
        BlueprintItems items = shops.items();
        BlueprintItems.Template template = items == null ? null : items.cached(item.itemBlueprintId());
        ItemStack stack = template != null && template.item() != null ? template.copy(1) : new ItemStack(Material.PAPER);
        List<String> label = settings.label() ? DisplayText.label(item, shown.greyed(), settings.hints()) : List.of();
        presenter.show(shown.spot(), shown.ref().toString(), stack, shown.greyed(), label, settings.itemScale());
    }

    /** Once a second: spots whose entities went away (a chunk reload, something removed them) are spawned again. */
    void respawn() {
        presenter.prune();
        for (Map.Entry<Integer, Map<Integer, DisplaySelection.Shown>> shop : selections.entrySet()) {
            for (DisplaySelection.Shown shown : shop.getValue().values()) {
                if (!presenter.isShown(shown.spot().id()) && loaded(shown.spot())) {
                    render(shown);
                }
            }
        }
    }

    private boolean loaded(DisplaySpot spot) {
        World world = Bukkit.getWorld(spot.world());
        return world != null && world.isChunkLoaded(spot.chunkX(), spot.chunkZ());
    }

    // ===== world events =====

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        List<Integer> shopIds = index.shopsInChunk(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        for (Integer shopId : shopIds) {
            Map<Integer, DisplaySelection.Shown> chosen = selections.get(shopId);
            if (chosen == null) {
                refreshShop(shopId);
                continue;
            }
            for (DisplaySelection.Shown shown : chosen.values()) {
                if (shown.spot().chunkX() == chunk.getX() && shown.spot().chunkZ() == chunk.getZ()
                        && shown.spot().world().equals(chunk.getWorld().getName())) {
                    render(shown);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        int removed = presenter.purgeOrphans(event.getEntities());
        if (removed > 0) {
            LOGGER.fine("Shop displays: removed " + removed + " stray stall entities in chunk " + event.getChunk().getX() + ","
                    + event.getChunk().getZ());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        for (DisplaySpot spot : index.all()) {
            if (spot.chunkX() == chunk.getX() && spot.chunkZ() == chunk.getZ() && spot.world().equals(chunk.getWorld().getName())) {
                presenter.hide(spot.id());
            }
        }
    }

    // ===== clicks =====

    /** Right-click: item information; shift-right-click: the quick buy (the confirmation). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent event) {
        Optional<Integer> spotId = ShopDisplayPresenter.spotOf(event.getRightClicked());
        if (spotId.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return; // the off-hand copy of the same click
        }
        click(event.getPlayer(), spotId.get(), event.getPlayer().isSneaking());
    }

    /** A left-click does nothing (and can't hurt or move the stall). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        if (ShopDisplayPresenter.isStallEntity(event.getAttacked())) {
            event.setCancelled(true);
        }
    }

    /** Main thread: what a click on a stall does. Returns true when a menu is being opened. */
    public boolean click(Player player, int spotId, boolean quickBuy) {
        long now = clock.millis();
        Long last = lastClick.get(player.getUniqueId());
        if (last != null && now - last < CLICK_DEBOUNCE_MILLIS) {
            return false;
        }
        lastClick.put(player.getUniqueId(), now);
        if (lastClick.size() > 512) {
            lastClick.clear();
        }
        Optional<DisplaySelection.Shown> shown = shown(spotId);
        if (shown.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "Nothing is on display here right now.");
            return false;
        }
        opener.open(player, shown.get().spot().shopId(), shown.get().ref(), quickBuy);
        return true;
    }

    // ===== admin (/knk shop display) =====

    /** The registered spot the player looks at, if any. */
    public Optional<DisplaySpot> target(Player player) {
        Block block = player.getTargetBlockExact(TARGET_RANGE);
        return block == null ? Optional.empty()
                : index.at(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    /** Registers the block the player looks at as a spot of the shop, facing the player. */
    public CompletableFuture<DisplaySpot> register(Player player, int shopId, String label) {
        Block block = player.getTargetBlockExact(TARGET_RANGE);
        if (block == null || block.getType().isAir()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Look at the shelf or stall block (within "
                    + TARGET_RANGE + " blocks)."));
        }
        Optional<DisplaySpot> taken = index.at(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        if (taken.isPresent()) {
            return CompletableFuture.failedFuture(new IllegalStateException("That block is already display spot "
                    + taken.get().describe() + " of shop #" + taken.get().shopId() + "."));
        }
        DisplaySpot.Facing facing = DisplaySpot.Facing.towards(player.getLocation().getYaw());
        ShopDisplaySpotsApi.SpotDraft draft = new ShopDisplaySpotsApi.SpotDraft(shopId, block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), facing, label);
        CompletableFuture<DisplaySpot> done = new CompletableFuture<>();
        api.create(draft).whenComplete((spot, error) -> mainThread.execute(() -> {
            if (error != null) {
                done.completeExceptionally(error);
                return;
            }
            index.put(spot);
            refreshShop(spot.shopId());
            done.complete(spot);
        }));
        return done;
    }

    /** Removes a spot (its entities go at once). */
    public CompletableFuture<DisplaySpot> remove(int spotId) {
        Optional<DisplaySpot> known = index.byId(spotId);
        CompletableFuture<DisplaySpot> done = new CompletableFuture<>();
        api.delete(spotId).whenComplete((ignored, error) -> mainThread.execute(() -> {
            if (error != null && ShopService.status(error) != 404) {
                done.completeExceptionally(error);
                return;
            }
            presenter.hide(spotId);
            index.remove(spotId);
            known.ifPresent(spot -> {
                Map<Integer, DisplaySelection.Shown> chosen = selections.get(spot.shopId());
                if (chosen != null) {
                    chosen.remove(spotId);
                }
                refreshShop(spot.shopId());
            });
            done.complete(known.orElse(null));
        }));
        return done;
    }

    /** Pins a catalogue entry of the spot's shop to the spot; null clears the pin. */
    public CompletableFuture<DisplaySpot> pin(int spotId, Integer catalogueEntryId) {
        CompletableFuture<DisplaySpot> done = new CompletableFuture<>();
        api.pin(spotId, catalogueEntryId).whenComplete((spot, error) -> mainThread.execute(() -> {
            if (error != null) {
                done.completeExceptionally(error);
                return;
            }
            index.put(spot);
            refreshShop(spot.shopId());
            done.complete(spot);
        }));
        return done;
    }

    /** {@code /knk shop display list}: one line per spot of the shop with what it shows. */
    public List<String> describe(int shopId) {
        List<String> lines = new ArrayList<>();
        Map<Integer, DisplaySelection.Shown> chosen = selections.getOrDefault(shopId, Map.of());
        for (DisplaySpot spot : index.ofShop(shopId)) {
            DisplaySelection.Shown shown = chosen.get(spot.id());
            String what = !spot.enabled() ? "off" : shown == null ? "nothing"
                    : shown.item().itemBlueprintName() + (shown.greyed() ? " (sold out)" : "");
            lines.add(spot.describe() + " facing " + spot.facing().apiName().toLowerCase(java.util.Locale.ROOT)
                    + (spot.pinnedCatalogueEntryId() == null ? "" : ", pinned to entry " + spot.pinnedCatalogueEntryId())
                    + ": " + what);
        }
        return lines;
    }

}
