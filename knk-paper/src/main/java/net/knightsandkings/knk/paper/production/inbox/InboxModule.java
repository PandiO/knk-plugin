package net.knightsandkings.knk.paper.production.inbox;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.core.domain.users.PlayerNotification;
import net.knightsandkings.knk.core.ports.api.ItemDeliveryApi;
import net.knightsandkings.knk.core.production.inbox.ClaimResult;
import net.knightsandkings.knk.core.production.inbox.DeliveryRequest;
import net.knightsandkings.knk.core.production.inbox.InboxLayout;
import net.knightsandkings.knk.core.production.inbox.InboxNotice;
import net.knightsandkings.knk.core.production.inbox.InboxSettings;
import net.knightsandkings.knk.core.production.inbox.InboxText;
import net.knightsandkings.knk.core.production.inbox.InboxView;
import net.knightsandkings.knk.core.production.inbox.RewardKeys;
import net.knightsandkings.knk.core.production.terminal.TerminalLayout;
import net.knightsandkings.knk.paper.lootbox.LootboxDelivery;
import net.knightsandkings.knk.paper.production.terminal.BlueprintItems;

/**
 * The reward inbox in game (PandiO/knk-workspace#684, production DESIGN § 4.6, D7, A15): {@code /inbox} opens a paged view
 * of the player's inbox; a click claims what fits (partial space) with its own idempotency key and places exactly what the
 * API accepted. It also shows the {@code InboxUpdated} notices (loss, near-cap warning, expiry reminders, expiry) and,
 * with {@code production.inbox.join-summary}, what waits in the inbox when a player joins. Only built with
 * {@code production.enabled} (A21).
 * <ol>
 *   <li>Not during a siege (hub or match): the inventory is the siege one then and is replaced afterwards.</li>
 *   <li>Unique items wait for the instance-to-item builder (#897): shown, not claimable in game.</li>
 *   <li>A claim with no answer is sent once more with the same key; claimed items that no longer fit (the inventory
 *       changed while the API answered) drop at the player's feet, owner-locked.</li>
 * </ol>
 */
public final class InboxModule implements Listener {

    private static final Logger LOGGER = Logger.getLogger(InboxModule.class.getName());
    static final long JOIN_SUMMARY_DELAY_TICKS = 100L;

    private final Plugin plugin;
    private final InboxSettings settings;
    private final ItemDeliveryApi api;
    private final BlueprintItems items;
    private final Function<UUID, Integer> userIds;
    private final Predicate<UUID> inSiege;
    private final RewardInbox rewards;
    private final Executor mainThread;
    private final Clock clock;
    private final Set<UUID> claiming = ConcurrentHashMap.newKeySet();

    public InboxModule(Plugin plugin, InboxSettings settings, ItemDeliveryApi api, BlueprintItems items,
                       Function<UUID, Integer> userIds, Predicate<UUID> inSiege, RewardInbox rewards, Executor mainThread,
                       Clock clock) {
        this.plugin = plugin;
        this.settings = settings;
        this.api = api;
        this.items = items;
        this.userIds = userIds;
        this.inSiege = inSiege != null ? inSiege : id -> false;
        this.rewards = rewards;
        this.mainThread = mainThread;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        LOGGER.info("Reward inbox on (production.enabled): /inbox, kit and lootbox leftovers go to the inbox");
    }

    public void stop() {
        HandlerList.unregisterAll(this);
    }

    public RewardInbox rewards() {
        return rewards;
    }

    // ===== /inbox =====

    /** Main thread: opens the player's inbox view. Completes on the main thread with the screen, or null when refused. */
    public CompletableFuture<InboxScreen> open(Player player) {
        if (inSiege.test(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You can't claim rewards during a siege. Your inbox waits for you.");
            return CompletableFuture.completedFuture(null);
        }
        Integer userId = userIds.apply(player.getUniqueId());
        if (userId == null) {
            player.sendMessage(ChatColor.RED + "Your account isn't loaded yet - try again in a moment.");
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<InboxScreen> done = new CompletableFuture<>();
        api.inbox(userId)
                .thenCompose(view -> prefetch(view).thenApply(ignored -> view))
                .whenComplete((view, error) -> mainThread.execute(() -> {
                    if (error != null) {
                        player.sendMessage(ChatColor.RED + "Could not read your reward inbox (" + RewardInbox.why(error) + ").");
                        done.complete(null);
                        return;
                    }
                    if (!player.isOnline()) {
                        done.complete(null);
                        return;
                    }
                    InboxScreen screen = new InboxScreen(userId);
                    screen.show(view, 1, items, clock.instant());
                    player.openInventory(screen.getInventory());
                    if (view.warningPercent() != null) {
                        player.sendMessage(ChatColor.GOLD + InboxText.warning(view.warningPercent()));
                    }
                    done.complete(screen);
                }));
        return done;
    }

    private CompletableFuture<Void> prefetch(InboxView view) {
        List<CompletableFuture<?>> calls = new ArrayList<>();
        for (InboxView.Entry entry : view.entries()) {
            if (!entry.unique()) {
                calls.add(items.template(entry.itemBlueprintId(), entry.displayName()));
            }
        }
        return CompletableFuture.allOf(calls.toArray(new CompletableFuture[0]));
    }

    // ===== clicks =====

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof InboxScreen screen) || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        Inventory clicked = event.getClickedInventory();
        if (clicked == null || !clicked.equals(screen.getInventory())) {
            return;
        }
        TerminalLayout.Click click = click(event.getClick());
        if (click == null) {
            return;
        }
        int slot = event.getSlot();
        InboxView view = screen.view();
        if (view == null) {
            return;
        }
        if (slot == InboxLayout.PREVIOUS_SLOT && screen.page() > 1) {
            screen.show(view, screen.page() - 1, items, clock.instant());
        } else if (slot == InboxLayout.NEXT_SLOT && screen.page() < InboxLayout.pageCount(view.entries().size())) {
            screen.show(view, screen.page() + 1, items, clock.instant());
        } else {
            InboxView.Entry entry = screen.entryAt(slot);
            if (entry != null) {
                claim(player, screen, entry, click);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof InboxScreen) {
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

    /** Main thread: claims what fits of {@code entry} and places exactly what the API accepted. */
    public CompletableFuture<Long> claim(Player player, InboxScreen screen, InboxView.Entry entry, TerminalLayout.Click click) {
        if (entry.unique()) {
            player.sendMessage(ChatColor.YELLOW + "Unique items can't be claimed in game yet; ask a staff member.");
            return CompletableFuture.completedFuture(0L);
        }
        if (inSiege.test(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You can't claim rewards during a siege.");
            return CompletableFuture.completedFuture(0L);
        }
        BlueprintItems.Template template = items.cached(entry.itemBlueprintId());
        if (template == null) {
            player.sendMessage(ChatColor.RED + "This item can't be built on this server right now; try again later.");
            return CompletableFuture.completedFuture(0L);
        }
        long amount = TerminalLayout.takeAmount(click, entry.amount(), template.maxStack(), room(player, template));
        if (amount <= 0) {
            player.sendMessage(ChatColor.RED + "Your inventory is full. Make room, then claim again.");
            return CompletableFuture.completedFuture(0L);
        }
        if (screen.busy() || !claiming.add(player.getUniqueId())) {
            return CompletableFuture.completedFuture(0L);
        }
        screen.busy(true);
        int userId = screen.userId();
        String key = RewardKeys.claim(userId);
        List<DeliveryRequest.Line> lines = List.of(DeliveryRequest.Line.of(entry.itemBlueprintId(), amount));
        CompletableFuture<Long> done = new CompletableFuture<>();
        claimWithRetry(userId, key, lines, 1).whenComplete((result, error) -> mainThread.execute(() -> {
            screen.busy(false);
            claiming.remove(player.getUniqueId());
            if (error != null) {
                player.sendMessage(ChatColor.RED + (RewardInbox.productionDisabled(error)
                        ? "The reward inbox is switched off on the server right now."
                        : "Could not claim (" + RewardInbox.why(error) + "). Nothing was taken from your inbox."));
                done.complete(0L);
                return;
            }
            long accepted = result.accepted();
            if (accepted > 0) {
                List<ItemStack> spill = give(player, template, accepted);
                if (!spill.isEmpty()) {
                    LootboxDelivery.dropOwned(player, spill);
                    player.sendMessage(ChatColor.GRAY + "Your inventory filled up meanwhile: the rest was dropped at your feet.");
                }
                player.sendMessage(ChatColor.GRAY + "Claimed " + ChatColor.WHITE + InboxText.items(accepted) + " "
                        + entry.displayName() + (accepted < amount ? ChatColor.YELLOW + " ("
                        + InboxText.claimShort(result.reasons()) + ")" : ""));
            } else {
                player.sendMessage(ChatColor.RED + "Nothing claimed: " + InboxText.claimShort(result.reasons()) + ".");
            }
            if (player.getOpenInventory().getTopInventory().getHolder() == screen) {
                screen.show(result.view(), screen.page(), items, clock.instant());
            }
            done.complete(accepted);
        }));
        return done;
    }

    private CompletableFuture<ClaimResult> claimWithRetry(int userId, String key, List<DeliveryRequest.Line> lines, int attempt) {
        CompletableFuture<ClaimResult> call;
        try {
            call = api.claim(userId, key, lines);
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        return call.handle((result, error) -> {
            if (error == null) {
                return CompletableFuture.completedFuture(result);
            }
            if (attempt >= 2 || !RewardInbox.retryable(error)) {
                return CompletableFuture.<ClaimResult>failedFuture(error);
            }
            CompletableFuture<ClaimResult> again = new CompletableFuture<>();
            CompletableFuture.delayedExecutor(RewardInbox.RETRY_DELAY_SECONDS, TimeUnit.SECONDS).execute(() ->
                    claimWithRetry(userId, key, lines, attempt + 1).whenComplete((r, e) -> {
                        if (e != null) {
                            again.completeExceptionally(e);
                        } else {
                            again.complete(r);
                        }
                    }));
            return again;
        }).thenCompose(f -> f);
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

    /** Main thread: {@code amount} of the item into the inventory in stacks; returns what didn't fit. */
    static List<ItemStack> give(Player player, BlueprintItems.Template template, long amount) {
        long left = amount;
        int max = Math.max(1, template.maxStack());
        List<ItemStack> spill = new ArrayList<>();
        while (left > 0) {
            int n = (int) Math.min(left, max);
            ItemStack stack = template.item().clone();
            stack.setAmount(n);
            spill.addAll(player.getInventory().addItem(stack).values());
            left -= n;
        }
        return spill;
    }

    // ===== notices and join =====

    /** An {@code InboxUpdated} notification for an online player (main thread). */
    public void onNotice(Player player, PlayerNotification notification) {
        InboxNotice notice = notification == null ? null : notification.inbox();
        if (notice == null) {
            return;
        }
        if (notice.delivered() && rewards != null && rewards.wasReported(notice.deliveryId())) {
            // This server already told the player where the items went; only the warning is new.
            if (notice.warningPercent() != null) {
                player.sendMessage(ChatColor.GOLD + InboxText.warning(notice.warningPercent()));
            }
            return;
        }
        for (String line : InboxText.notice(notice, clock.instant())) {
            player.sendMessage(colour(line) + line);
        }
    }

    static ChatColor colour(String line) {
        if (line.contains("were lost") || line.contains("was lost") || line.contains("expired and")) {
            return ChatColor.RED;
        }
        if (line.contains("% full") || line.contains("expire")) {
            return ChatColor.GOLD;
        }
        return ChatColor.GREEN;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!settings.joinSummary()) {
            return;
        }
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> joinSummary(player), JOIN_SUMMARY_DELAY_TICKS);
    }

    /** Main thread: tells an online player what waits in their inbox (nothing for an empty inbox). */
    public CompletableFuture<Void> joinSummary(Player player) {
        Integer userId = player.isOnline() ? userIds.apply(player.getUniqueId()) : null;
        if (userId == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        api.inbox(userId).whenComplete((view, error) -> mainThread.execute(() -> {
            if (error == null && player.isOnline()) {
                for (String line : InboxText.joinSummary(view, clock.instant())) {
                    player.sendMessage(colour(line) + line);
                }
            } else if (error != null) {
                LOGGER.fine("Reward inbox: join summary for user " + userId + " failed: " + RewardInbox.why(error));
            }
            done.complete(null);
        }));
        return done;
    }
}
