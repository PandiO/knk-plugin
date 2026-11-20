package net.knightsandkings.knk.paper.production.terminal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.items.identity.ItemIdentityResolution;
import net.knightsandkings.knk.core.ports.api.StorageContainersApi;
import net.knightsandkings.knk.core.production.terminal.BlockKey;
import net.knightsandkings.knk.core.production.terminal.ContainerIndex;
import net.knightsandkings.knk.core.production.terminal.DisplaySample;
import net.knightsandkings.knk.core.production.terminal.DisplayThrottle;
import net.knightsandkings.knk.core.production.terminal.StockMoveResult;
import net.knightsandkings.knk.core.production.terminal.StorageContainerInfo;
import net.knightsandkings.knk.core.production.terminal.TerminalLayout;
import net.knightsandkings.knk.core.production.terminal.TerminalMessages;
import net.knightsandkings.knk.core.production.terminal.TerminalPage;
import net.knightsandkings.knk.core.production.terminal.TerminalSettings;

/**
 * Storage terminals and display containers in the world (production DESIGN § 6.2, PandiO/knk-workspace#74, A14). Only
 * built with {@code production.enabled: true} (A21).
 * <ol>
 *   <li>The bound containers are the API's list ({@link ContainerIndex}), read at start, every
 *       {@code production.storage.refresh-minutes} and after {@code /knk storage bind|unbind}; never found by scanning.</li>
 *   <li><b>Terminal:</b> right-clicking the block opens a paged view of the storage ({@link TerminalView}) instead of its
 *       inventory. A click on an entry takes, a click on an item of the player's inventory puts; each is one ledger move
 *       through {@code SYS_WORLD} with its own idempotency key. Put resolves the item with item identity (#141) and holds
 *       the items back until the API answers: what it refused goes back to the player.</li>
 *   <li><b>Display:</b> the block's inventory holds a tagged sample of the storage, refreshed when its chunk loads and after
 *       a terminal move on the same storage, at most once per {@code display-refresh-seconds}. Opening it shows a
 *       read-only copy ({@link DisplayView}).</li>
 *   <li>A bound block can't be broken (unbind it first), explosions leave it, and hoppers don't move items in or out.</li>
 * </ol>
 * API calls run off the main thread; inventories are only touched on it.
 */
public final class StorageTerminalModule implements Listener {

    private static final Logger LOGGER = Logger.getLogger(StorageTerminalModule.class.getName());
    /** Retries of a put whose answer was lost (same idempotency key, so it applies once). */
    static final int PUT_RETRIES = 1;

    private final Plugin plugin;
    private final TerminalSettings settings;
    private final StorageContainersApi api;
    private final BlueprintItems items;
    private final Function<UUID, Integer> userIds;
    private final Supplier<Function<ItemStack, ItemIdentityResolution>> identity;
    private final ContainerIndex index = new ContainerIndex();
    private final DisplayThrottle throttle;
    private final Set<UUID> moving = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile String lastRefresh = "never";
    private BukkitTask refreshTask;
    private BukkitTask displayTask;

    /**
     * @param userIds  the knk user id of an online player (the user cache), or null
     * @param identity the item identity resolver, or null while {@code items.identity.enabled} is off (puts are refused)
     */
    public StorageTerminalModule(Plugin plugin, TerminalSettings settings, StorageContainersApi api, BlueprintItems items,
                                 Function<UUID, Integer> userIds,
                                 Supplier<Function<ItemStack, ItemIdentityResolution>> identity) {
        this.plugin = plugin;
        this.settings = settings;
        this.api = api;
        this.items = items;
        this.userIds = userIds;
        this.identity = identity;
        this.throttle = new DisplayThrottle(settings.displayRefreshSeconds() * 1000L);
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        long period = settings.refreshMinutes() * 60L * 20L;
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> reload("timer"), 20L, period);
        displayTask = Bukkit.getScheduler().runTaskTimer(plugin, this::runDueDisplays, 40L, 20L);
        LOGGER.info("Storage terminals on: bound containers re-read every " + settings.refreshMinutes()
                + " min, displays refreshed at most every " + settings.displayRefreshSeconds() + " s");
    }

    public void stop() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        if (displayTask != null) {
            displayTask.cancel();
        }
    }

    public ContainerIndex index() {
        return index;
    }

    public StorageContainersApi api() {
        return api;
    }

    public String lastRefresh() {
        return lastRefresh;
    }

    /** Re-reads every binding; then refreshes the displays in loaded chunks. */
    public CompletableFuture<Integer> reload(String why) {
        return api.list(null, null).thenApplyAsync(all -> {
            index.replace(all);
            lastRefresh = java.time.Instant.now() + " (" + why + ", " + all.size() + " containers)";
            for (StorageContainerInfo display : index.displays()) {
                if (loaded(display)) {
                    requestDisplay(display);
                }
            }
            return all.size();
        }, mainThread()).whenComplete((count, error) -> {
            if (error != null) {
                lastRefresh = java.time.Instant.now() + " (" + why + ": failed, " + describe(error) + ")";
                LOGGER.log(Level.WARNING, "Storage terminals: the bound containers could not be read: " + describe(error));
            }
        });
    }

    // ===== opening =====

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || !ContainerBlocks.bindable(event.getClickedBlock())) {
            return;
        }
        StorageContainerInfo info = bindingOf(event.getClickedBlock());
        if (info == null) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setCancelled(true);
        if (info.mode() == StorageContainerInfo.Mode.TERMINAL) {
            openTerminal(event.getPlayer(), info);
        } else {
            openDisplay(event.getPlayer(), info, event.getClickedBlock());
        }
    }

    /** The binding of a block (either half of a double chest), or null. */
    public StorageContainerInfo bindingOf(Block block) {
        if (block == null) {
            return null;
        }
        Block canonical = ContainerBlocks.canonical(block);
        return index.at(ContainerBlocks.key(canonical)).orElse(null);
    }

    public void openTerminal(Player player, StorageContainerInfo info) {
        Integer userId = userIds.apply(player.getUniqueId());
        if (userId == null) {
            player.sendMessage(ChatColor.RED + "Your account isn't loaded yet; try again in a moment.");
            return;
        }
        TerminalView view = new TerminalView(info);
        loadPage(player, view, 1, true);
    }

    void openDisplay(Player player, StorageContainerInfo info, Block block) {
        Inventory own = ContainerBlocks.inventory(ContainerBlocks.canonical(block));
        ItemStack[] contents = own == null ? new ItemStack[0] : own.getContents();
        player.openInventory(new DisplayView(info, contents).getInventory());
        requestDisplay(info);
    }

    /** Reads a page and shows it (opening the view when {@code open}). */
    void loadPage(Player player, TerminalView view, int page, boolean open) {
        Integer userId = userIds.apply(player.getUniqueId());
        api.terminal(view.container().id(), page, TerminalLayout.ENTRIES_PER_PAGE, userId)
                .thenCompose(result -> prefetch(result.entries().stream().map(TerminalPage.Entry::itemBlueprintId).toList(),
                        result.entries().stream().map(TerminalPage.Entry::name).toList()).thenApply(ignored -> result))
                .whenCompleteAsync((result, error) -> {
                    if (error != null) {
                        player.sendMessage(ChatColor.RED + TerminalMessages.forStatus(status(error), body(error)));
                        if (!open) {
                            player.closeInventory();
                        }
                        return;
                    }
                    if (!player.isOnline()) {
                        return;
                    }
                    view.show(result, items);
                    if (open) {
                        player.openInventory(view.getInventory());
                    }
                }, mainThread());
    }

    // ===== clicks =====

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder(false);
        if (holder instanceof DisplayView) {
            event.setCancelled(true);
            return;
        }
        if (!(holder instanceof TerminalView view) || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return;
        }
        TerminalLayout.Click click = click(event.getClick());
        if (click == null) {
            return;
        }
        if (clicked.equals(view.getInventory())) {
            int slot = event.getSlot();
            TerminalPage page = view.page();
            if (slot == TerminalLayout.PREVIOUS_SLOT && page != null && page.hasPrevious()) {
                loadPage(player, view, page.page() - 1, false);
            } else if (slot == TerminalLayout.NEXT_SLOT && page != null && page.hasNext()) {
                loadPage(player, view, page.page() + 1, false);
            } else {
                TerminalPage.Entry entry = view.entryAt(slot);
                if (entry != null) {
                    take(player, view, entry, click);
                }
            }
        } else {
            put(player, view, event.getSlot(), click);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder(false);
        if (holder instanceof DisplayView || holder instanceof TerminalView) {
            event.setCancelled(true);
        }
    }

    static TerminalLayout.Click click(ClickType type) {
        return switch (type) {
            case LEFT -> TerminalLayout.Click.LEFT;
            case RIGHT -> TerminalLayout.Click.RIGHT;
            case SHIFT_LEFT, SHIFT_RIGHT -> TerminalLayout.Click.SHIFT;
            default -> null;
        };
    }

    void take(Player player, TerminalView view, TerminalPage.Entry entry, TerminalLayout.Click click) {
        if (entry.unique()) {
            player.sendMessage(ChatColor.YELLOW + "Unique items can't be taken out in game yet; use the web app.");
            return;
        }
        Integer userId = userIds.apply(player.getUniqueId());
        BlueprintItems.Template template = items.cached(entry.itemBlueprintId());
        if (userId == null || template == null) {
            player.sendMessage(ChatColor.RED + "Still loading; try again in a moment.");
            return;
        }
        long amount = TerminalLayout.takeAmount(click, entry.amount(), template.maxStack(), room(player, template));
        if (amount <= 0) {
            player.sendMessage(ChatColor.RED + "Your inventory is full.");
            return;
        }
        if (!begin(player, view)) {
            return;
        }
        int containerId = view.container().id();
        api.take(containerId, userId, entry.itemBlueprintId(), null, amount, key())
                .whenCompleteAsync((result, error) -> {
                    end(player, view);
                    if (error != null) {
                        player.sendMessage(ChatColor.RED + TerminalMessages.forStatus(status(error), body(error)));
                        return;
                    }
                    if (result.accepted() > 0) {
                        give(player, template, result.accepted());
                        player.sendMessage(ChatColor.GRAY + "Took " + ChatColor.WHITE + result.accepted() + " " + entry.name()
                                + (result.accepted() < amount ? ChatColor.YELLOW + " (" + reasons(result) + ")" : ""));
                    } else {
                        player.sendMessage(ChatColor.RED + "Nothing taken: " + reasons(result) + ".");
                    }
                    afterMove(player, view);
                }, mainThread());
    }

    void put(Player player, TerminalView view, int slot, TerminalLayout.Click click) {
        ItemStack stack = player.getInventory().getItem(slot);
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        String refusal = refusal(stack);
        Function<ItemStack, ItemIdentityResolution> resolver = identity.get();
        ItemIdentityResolution resolution = null;
        if (refusal == null && resolver == null) {
            refusal = "Item identity is off on this server (items.identity.enabled), so terminals can't take items in.";
        }
        if (refusal == null) {
            resolution = resolver.apply(stack);
            if (resolution == null || !resolution.resolved() || resolution.blueprintId() == null) {
                refusal = "This item isn't in the item catalogue"
                        + (resolution != null && resolution.reason() != null ? " (" + resolution.reason() + ")" : "") + ".";
            } else if (resolution.kind() == ItemIdentityResolution.Kind.INSTANCE) {
                refusal = "Unique items can't be put in a terminal in game yet; use the web app.";
            } else if (resolution.kind() == ItemIdentityResolution.Kind.VANILLA_MATCH && enchanted(stack)) {
                refusal = "Enchanted items can't go into a terminal (they would come back without their enchantments).";
            }
        }
        if (refusal != null) {
            player.sendMessage(ChatColor.RED + refusal);
            return;
        }
        Integer userId = userIds.apply(player.getUniqueId());
        if (userId == null) {
            player.sendMessage(ChatColor.RED + "Your account isn't loaded yet; try again in a moment.");
            return;
        }
        int amount = TerminalLayout.putAmount(click, stack.getAmount());
        if (amount <= 0 || !begin(player, view)) {
            return;
        }
        // Hold the items back while the API answers; what it refuses goes back to the player.
        ItemStack held = stack.clone();
        held.setAmount(amount);
        stack.setAmount(stack.getAmount() - amount);
        player.getInventory().setItem(slot, stack.getAmount() <= 0 ? null : stack);
        int blueprintId = resolution.blueprintId();
        String key = key();
        putWithRetry(view.container().id(), userId, blueprintId, amount, key, PUT_RETRIES)
                .whenCompleteAsync((result, error) -> {
                    end(player, view);
                    long accepted = error == null ? Math.max(0, Math.min(amount, result.accepted())) : 0;
                    long back = amount - accepted;
                    if (back > 0) {
                        ItemStack rest = held.clone();
                        rest.setAmount((int) back);
                        Map<Integer, ItemStack> left = player.getInventory().addItem(rest);
                        left.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
                    }
                    if (error != null) {
                        player.sendMessage(ChatColor.RED + TerminalMessages.forStatus(status(error), body(error)));
                        if (status(error) == 0) {
                            LOGGER.warning("Terminal put of " + amount + " x blueprint " + blueprintId + " by "
                                    + player.getName() + " (key " + key + ") got no answer; the items went back to the player");
                        }
                        return;
                    }
                    if (accepted > 0) {
                        player.sendMessage(ChatColor.GRAY + "Put in " + ChatColor.WHITE + accepted + ChatColor.GRAY
                                + (back > 0 ? ChatColor.YELLOW + " (" + back + " back: " + reasons(result) + ")" : ""));
                    } else {
                        player.sendMessage(ChatColor.RED + "Nothing put in: " + reasons(result) + ".");
                    }
                    afterMove(player, view);
                }, mainThread());
    }

    /** A put whose answer is lost (no status) is sent again with the same key; the API applies it once. */
    private CompletableFuture<StockMoveResult> putWithRetry(int containerId, int userId, int blueprintId, long amount,
                                                           String key, int retries) {
        return api.put(containerId, userId, blueprintId, null, amount, key)
                .handle((result, error) -> {
                    if (error == null) {
                        return CompletableFuture.completedFuture(result);
                    }
                    if (retries > 0 && status(error) == 0) {
                        return putWithRetry(containerId, userId, blueprintId, amount, key, retries - 1);
                    }
                    return CompletableFuture.<StockMoveResult>failedFuture(error);
                })
                .thenCompose(f -> f);
    }

    /**
     * Why an item may not go in even when it resolves (agent decision, see the PR): damaged items and vanilla items with
     * enchantments come back out as the plain blueprint item, so they would be repaired or lose their enchantments.
     */
    static String refusal(ItemStack stack) {
        if (DisplayWriter.isSample(stack)) {
            return "That is a display sample, not a real item.";
        }
        ItemMeta meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
        if (meta instanceof Damageable damageable && damageable.hasDamage() && damageable.getDamage() > 0) {
            return "Damaged items can't go into a terminal (they would come back repaired).";
        }
        return null;
    }

    static boolean enchanted(ItemStack stack) {
        ItemMeta meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
        if (meta == null) {
            return false;
        }
        return meta.hasEnchants() || (meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta stored
                && stored.hasStoredEnchants());
    }

    private boolean begin(Player player, TerminalView view) {
        if (view.busy() || !moving.add(player.getUniqueId())) {
            return false;
        }
        view.busy(true);
        return true;
    }

    private void end(Player player, TerminalView view) {
        view.busy(false);
        moving.remove(player.getUniqueId());
    }

    private void afterMove(Player player, TerminalView view) {
        if (player.getOpenInventory().getTopInventory().getHolder(false) == view) {
            loadPage(player, view, view.pageNumber(), false);
        }
        for (StorageContainerInfo display : index.displaysOf(view.container().storageId())) {
            if (loaded(display)) {
                requestDisplay(display);
            }
        }
    }

    /** Units of {@code template} the player's inventory can still hold. */
    static long room(Player player, BlueprintItems.Template template) {
        long room = 0;
        int max = Math.max(1, template.maxStack());
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType().isAir()) {
                room += max;
            } else if (slot.isSimilar(template.item())) {
                room += Math.max(0, max - slot.getAmount());
            }
        }
        return room;
    }

    static void give(Player player, BlueprintItems.Template template, long amount) {
        long left = amount;
        int max = Math.max(1, template.maxStack());
        List<ItemStack> stacks = new ArrayList<>();
        while (left > 0) {
            int n = (int) Math.min(left, max);
            stacks.add(template.copy(n));
            left -= n;
        }
        Map<Integer, ItemStack> rest = player.getInventory().addItem(stacks.toArray(new ItemStack[0]));
        rest.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
    }

    // ===== displays =====

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        for (StorageContainerInfo display : index.displaysInChunk(event.getWorld().getName(), event.getChunk().getX(),
                event.getChunk().getZ())) {
            requestDisplay(display);
        }
    }

    /** Refreshes a display now, or once its interval has passed. */
    public void requestDisplay(StorageContainerInfo display) {
        if (throttle.tryRun(display.id(), System.currentTimeMillis())) {
            refreshDisplay(display);
        }
    }

    private void runDueDisplays() {
        for (Integer id : throttle.due(System.currentTimeMillis())) {
            index.byId(id).filter(this::loaded).ifPresent(this::refreshDisplay);
        }
    }

    CompletableFuture<Integer> refreshDisplay(StorageContainerInfo display) {
        return api.display(display.id())
                .thenCompose(sample -> prefetch(sample.items().stream().map(DisplaySample.Item::itemBlueprintId).toList(),
                        sample.items().stream().map(DisplaySample.Item::itemBlueprintName).toList()).thenApply(ignored -> sample))
                .thenApplyAsync(sample -> {
                    World world = Bukkit.getWorld(display.worldName());
                    if (world == null || !world.isChunkLoaded(display.x() >> 4, display.z() >> 4)) {
                        return 0;
                    }
                    Block block = world.getBlockAt(display.x(), display.y(), display.z());
                    Inventory inventory = ContainerBlocks.bindable(block) ? ContainerBlocks.inventory(block) : null;
                    if (inventory == null) {
                        return 0;
                    }
                    return DisplayWriter.write(inventory, sample, items::cached);
                }, mainThread())
                .exceptionally(error -> {
                    int status = status(error);
                    if (status == 404) {
                        index.remove(display.id());
                    } else {
                        LOGGER.log(Level.FINE, "Display #" + display.id() + " could not be refreshed: " + describe(error));
                    }
                    return 0;
                });
    }

    private boolean loaded(StorageContainerInfo display) {
        World world = Bukkit.getWorld(display.worldName());
        return world != null && world.isChunkLoaded(display.x() >> 4, display.z() >> 4);
    }

    // ===== protection =====

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!ContainerBlocks.bindable(event.getBlock())) {
            return;
        }
        StorageContainerInfo info = bindingOf(event.getBlock());
        if (info != null) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "This " + info.mode().apiName().toLowerCase(java.util.Locale.ROOT)
                    + " is bound to " + info.displayName() + ". Unbind it first (/knk storage unbind).");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> ContainerBlocks.bindable(block) && bindingOf(block) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> ContainerBlocks.bindable(block) && bindingOf(block) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent event) {
        if (index.size() == 0) {
            return;
        }
        if (bound(event.getSource()) || bound(event.getDestination())) {
            event.setCancelled(true);
        }
    }

    private boolean bound(Inventory inventory) {
        Block block = ContainerBlocks.canonical(inventory);
        return block != null && index.at(ContainerBlocks.key(block)).isPresent();
    }

    // ===== helpers =====

    private CompletableFuture<Void> prefetch(List<Integer> blueprintIds, List<String> names) {
        Set<Integer> seen = new HashSet<>();
        List<CompletableFuture<BlueprintItems.Template>> all = new ArrayList<>();
        for (int i = 0; i < blueprintIds.size(); i++) {
            if (seen.add(blueprintIds.get(i))) {
                all.add(items.template(blueprintIds.get(i), names.get(i)));
            }
        }
        return CompletableFuture.allOf(all.toArray(new CompletableFuture[0]));
    }

    private java.util.concurrent.Executor mainThread() {
        return task -> {
            if (Bukkit.isPrimaryThread()) {
                task.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        };
    }

    static String key() {
        return "terminal-" + UUID.randomUUID();
    }

    static String reasons(StockMoveResult result) {
        if (result.rejectReasons().isEmpty()) {
            return "refused";
        }
        return String.join(", ", result.rejectReasons().stream().map(TerminalMessages::rejectReason).distinct().toList());
    }

    static int status(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof ApiException api) {
                return api.getStatusCode();
            }
            cause = cause.getCause();
        }
        return 0;
    }

    static String body(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof ApiException api) {
                return api.getResponseBody();
            }
            cause = cause.getCause();
        }
        return null;
    }

    static String describe(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        int status = status(cause);
        return status > 0 ? "status " + status : String.valueOf(cause.getMessage());
    }

    /** The key used for a block in the index (for the command). */
    public static BlockKey keyOf(Block block) {
        return ContainerBlocks.key(ContainerBlocks.canonical(block));
    }
}
