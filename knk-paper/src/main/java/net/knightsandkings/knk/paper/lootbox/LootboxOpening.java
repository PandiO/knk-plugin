package net.knightsandkings.knk.paper.lootbox;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import net.knightsandkings.knk.core.lootbox.KnkLootboxOdds;
import net.knightsandkings.knk.core.lootbox.KnkLootboxRuntimeConfig;
import net.knightsandkings.knk.core.lootbox.LootboxReel;
import net.knightsandkings.knk.core.ports.api.LootboxesQueryApi;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Opening a lootbox (docs/specs/lootboxes/DESIGN.md §3.9, smoke test 2026-09-27): a "wheel of fortune" in a chest menu.
 * The middle row scrolls past a marker, slows down and stops on the item the API already rolled; then the item goes
 * into the inventory (the usual {@link LootboxDelivery} hand-over and confirmation) and the menu closes a moment later.
 * Nearby players see particles and hear the chest open and the result, more for an announced (rare) drop.
 * <p>
 * The reel is only a presentation of a result that is already stored: closing the menu early hands the item over at
 * once, a player who quits mid-spin gets it when they rejoin (and, failing that, through the API's pending claims),
 * and a server stop hands every spinning item over first. The passing items follow the box's real odds (the odds
 * preview, cached for a few minutes) and are fetched with a short timeout; without them the reel shows only the
 * winner. {@code opening.style: instant} skips the reel.
 */
public final class LootboxOpening implements Listener {

    private static final Logger LOGGER = Logger.getLogger(LootboxOpening.class.getName());
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final int ROWS = 3;
    private static final int ROW_START = 9;
    private static final int VISIBLE = 9;
    private static final long CANDIDATE_TIMEOUT_MILLIS = 1500;
    private static final Duration ODDS_TTL = Duration.ofMinutes(5);

    private final Plugin plugin;
    private final LootboxDelivery delivery;
    private final LootboxAnnouncer announcer;
    private final LootboxesQueryApi queryApi;
    private final Supplier<LootboxSettings> settings;
    private final Supplier<KnkLootboxRuntimeConfig> config;
    private final DoubleSupplier random;

    // Main thread only.
    private final Map<UUID, Spin> spins = new HashMap<>();
    private final Map<UUID, List<Pending>> interrupted = new HashMap<>();
    private final Map<String, CachedOdds> odds = new HashMap<>();
    private final Map<Integer, ItemStack> previews = new HashMap<>();

    private record Pending(LootboxDelivery.Prepared prepared, String giftedBy) {
    }

    private record CachedOdds(Instant fetchedAt, List<LootboxReel.Weighted<Integer>> blueprints) {
    }

    public LootboxOpening(Plugin plugin, LootboxDelivery delivery, LootboxAnnouncer announcer, LootboxesQueryApi queryApi,
                          Supplier<LootboxSettings> settings, Supplier<KnkLootboxRuntimeConfig> config, DoubleSupplier random) {
        this.plugin = plugin;
        this.delivery = delivery;
        this.announcer = announcer;
        this.queryApi = queryApi;
        this.settings = settings;
        this.config = config;
        this.random = random;
    }

    /**
     * Main thread: shows the opening of {@code claim} to {@code player} and hands the item over. {@code giftedBy} names
     * the staff member of a {@code /knk lootbox give} (null for the player's own box). A claim this server already
     * handed over (a replay) does nothing.
     */
    public void open(Player player, KnkLootboxClaimResult claim, String giftedBy) {
        if (claim == null || claim.isDelivered() || delivery.wasHandedOver(claim.claimId())) {
            return;
        }
        LootboxSettings current = settings.get();
        publicOpeningEffect(player, current, false);
        delivery.prepare(claim).thenAccept(prepared -> {
            if (prepared == null) {
                if (player.isOnline()) {
                    player.sendMessage(LootboxMessages.STUCK_ITEM);
                }
                return;
            }
            if (!player.isOnline()) {
                remember(player.getUniqueId(), new Pending(prepared, giftedBy));
                return;
            }
            if (!current.opening().wheel() || spins.containsKey(player.getUniqueId())) {
                // Instant (configured, or a second box while a reel is still turning).
                finish(player, prepared, giftedBy, current);
                return;
            }
            candidates(claim).thenAccept(candidates -> {
                if (!player.isOnline()) {
                    remember(player.getUniqueId(), new Pending(prepared, giftedBy));
                } else if (spins.containsKey(player.getUniqueId())) {
                    finish(player, prepared, giftedBy, settings.get());
                } else {
                    spin(player, prepared, giftedBy, candidates);
                }
            });
        });
    }

    /** Main thread: hands over every item still spinning (a plugin stop), without the rest of the animation. */
    public void finishAll() {
        for (Spin spin : List.copyOf(spins.values())) {
            spin.stop();
            spins.remove(spin.playerId);
            Player player = Bukkit.getPlayer(spin.playerId);
            if (player != null && player.isOnline()) {
                if (player.getOpenInventory().getTopInventory().getHolder() == spin) {
                    player.closeInventory();
                }
                finish(player, spin.prepared, spin.giftedBy, settings.get());
            }
        }
    }

    // ===== The reel =====

    private void spin(Player player, LootboxDelivery.Prepared prepared, String giftedBy, List<LootboxReel.Weighted<ItemStack>> candidates) {
        LootboxSettings current = settings.get();
        KnkLootboxClaimResult claim = prepared.claim();
        LootboxReel<ItemStack> reel = LootboxReel.plan(candidates, prepared.item(), current.opening().reelSteps(), VISIBLE,
                current.opening().slowestStepTicks(), random);
        Spin spin = new Spin(player.getUniqueId(), prepared, giftedBy, reel);
        String title = current.coloredLabel(claim.boxLabel(), claim.boxStars());
        spin.inventory = Bukkit.createInventory(spin, ROWS * 9, LEGACY.deserialize(title));
        ItemStack frame = pane(frameColor(claim.boxStars()), " ");
        ItemStack marker = pane(Material.LIME_STAINED_GLASS_PANE, "&a▼");
        for (int slot = 0; slot < ROWS * 9; slot++) {
            spin.inventory.setItem(slot, frame);
        }
        spin.inventory.setItem(4, marker);
        spin.inventory.setItem(22, pane(Material.LIME_STAINED_GLASS_PANE, "&a▲"));
        render(spin);
        spins.put(spin.playerId, spin);
        player.openInventory(spin.inventory);
        scheduleStep(spin);
    }

    private void scheduleStep(Spin spin) {
        if (spin.step >= spin.reel.steps()) {
            land(spin);
            return;
        }
        long delay = spin.reel.delays().get(spin.step);
        spin.task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (spins.get(spin.playerId) != spin) {
                return;
            }
            Player player = Bukkit.getPlayer(spin.playerId);
            if (player == null || !player.isOnline()) {
                return; // the quit handler keeps the item for the rejoin
            }
            spin.step++;
            render(spin);
            float pitch = 0.8f + 1.2f * spin.step / Math.max(1, spin.reel.steps());
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, Math.min(2.0f, pitch));
            scheduleStep(spin);
        }, delay);
    }

    private void render(Spin spin) {
        List<ItemStack> window = spin.reel.window(spin.step);
        for (int i = 0; i < VISIBLE; i++) {
            ItemStack shown = window.get(i);
            spin.inventory.setItem(ROW_START + i, shown == null ? null : shown.clone());
        }
    }

    /** The reel stopped: hand the item over, celebrate, close the menu after a moment. */
    private void land(Spin spin) {
        Player player = Bukkit.getPlayer(spin.playerId);
        spins.remove(spin.playerId);
        spin.finished = true;
        if (player == null || !player.isOnline()) {
            remember(spin.playerId, new Pending(spin.prepared, spin.giftedBy));
            return;
        }
        LootboxSettings current = settings.get();
        finish(player, spin.prepared, spin.giftedBy, current);
        int showFor = current.opening().showResultTicks();
        spin.task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player now = Bukkit.getPlayer(spin.playerId);
            if (now != null && now.getOpenInventory().getTopInventory().getHolder() == spin) {
                now.closeInventory();
            }
        }, Math.max(1, showFor));
    }

    /** Hands the item over and says what it was (and, for an announced drop, broadcasts it). */
    private void finish(Player player, LootboxDelivery.Prepared prepared, String giftedBy, LootboxSettings current) {
        LootboxDelivery.Outcome outcome = delivery.handOver(player, prepared, false);
        KnkLootboxClaimResult claim = prepared.claim();
        if (outcome.given()) {
            announcer.opened(player, claim, outcome.item(), current, config.get(), giftedBy);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
            publicOpeningEffect(player, current, claim.announce() || claim.isSpecial());
        } else if (!outcome.alreadyHeld()) {
            player.sendMessage(LootboxMessages.STUCK_ITEM);
        }
    }

    /** Particles and sounds at the player, for everyone nearby: a small burst to start, a big one for a rare drop. */
    private static void publicOpeningEffect(Player player, LootboxSettings current, boolean rare) {
        if (!current.opening().publicEffects()) {
            return;
        }
        try {
            Location at = player.getLocation().add(0, 1.0, 0);
            if (at.getWorld() == null) {
                return;
            }
            if (rare) {
                at.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, at, 60, 0.6, 0.8, 0.6, 0.35);
                at.getWorld().spawnParticle(Particle.END_ROD, at, 30, 0.4, 0.8, 0.4, 0.08);
                at.getWorld().playSound(at, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.0f, 1.0f);
            } else {
                at.getWorld().spawnParticle(Particle.ENCHANT, at, 40, 0.5, 0.6, 0.5, 0.6);
                at.getWorld().playSound(at, Sound.BLOCK_ENDER_CHEST_OPEN, 0.7f, 1.1f);
            }
        } catch (RuntimeException e) {
            LOGGER.fine("Lootbox opening effect skipped: " + e.getMessage());
        }
    }

    // ===== What passes by =====

    /** The box's pool as display items weighted by their real chance; empty when it can't be read quickly. */
    private CompletableFuture<List<LootboxReel.Weighted<ItemStack>>> candidates(KnkLootboxClaimResult claim) {
        CompletableFuture<List<LootboxReel.Weighted<ItemStack>>> done = new CompletableFuture<>();
        oddsFor(claim.lootboxTypeId(), claim.boxStars()).thenAccept(weighted -> {
            List<CompletableFuture<LootboxReel.Weighted<ItemStack>>> items = new ArrayList<>();
            for (LootboxReel.Weighted<Integer> entry : weighted) {
                items.add(previewOf(entry.value()).thenApply(stack -> stack == null ? null : new LootboxReel.Weighted<>(stack, entry.weight())));
            }
            CompletableFuture.allOf(items.toArray(new CompletableFuture[0])).whenComplete((ignored, ex) ->
                    Bukkit.getScheduler().runTask(plugin, () -> done.complete(items.stream()
                            .map(f -> f.getNow(null)).filter(Objects::nonNull).toList())));
        });
        // Never keep the player waiting on a slow API: the reel then shows the winner only.
        CompletableFuture<List<LootboxReel.Weighted<ItemStack>>> bounded = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskLater(plugin, () -> bounded.complete(List.of()), CANDIDATE_TIMEOUT_MILLIS / 50);
        done.thenAccept(bounded::complete);
        return bounded;
    }

    /** Main thread: blueprint id → chance, from the odds preview (cached {@link #ODDS_TTL}). */
    private CompletableFuture<List<LootboxReel.Weighted<Integer>>> oddsFor(int typeId, int boxStars) {
        String key = typeId + ":" + boxStars;
        CachedOdds cached = odds.get(key);
        if (cached != null && cached.fetchedAt().plus(ODDS_TTL).isAfter(Instant.now())) {
            return CompletableFuture.completedFuture(cached.blueprints());
        }
        CompletableFuture<List<LootboxReel.Weighted<Integer>>> done = new CompletableFuture<>();
        queryApi.getOdds(typeId, boxStars).orTimeout(CANDIDATE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .whenComplete((result, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (ex != null || result == null) {
                        done.complete(List.of());
                        return;
                    }
                    List<LootboxReel.Weighted<Integer>> weighted = weighted(result);
                    odds.put(key, new CachedOdds(Instant.now(), weighted));
                    done.complete(weighted);
                }));
        return done;
    }

    static List<LootboxReel.Weighted<Integer>> weighted(KnkLootboxOdds odds) {
        List<LootboxReel.Weighted<Integer>> weighted = new ArrayList<>();
        for (KnkLootboxOdds.Item item : odds.items()) {
            if (item.itemBlueprintId() != null && item.percent() > 0) {
                weighted.add(new LootboxReel.Weighted<>(item.itemBlueprintId(), item.percent()));
            }
        }
        for (KnkLootboxOdds.Special special : odds.specials()) {
            if (special.itemBlueprintId() != null && special.percent() > 0) {
                weighted.add(new LootboxReel.Weighted<>(special.itemBlueprintId(), special.percent()));
            }
        }
        return weighted;
    }

    /** Main thread: a display copy of a blueprint, built once per server run. */
    private CompletableFuture<ItemStack> previewOf(int blueprintId) {
        ItemStack cached = previews.get(blueprintId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return delivery.preview(blueprintId).thenApply(stack -> {
            if (stack != null) {
                previews.put(blueprintId, stack);
            }
            return stack;
        });
    }

    /** Main thread: forget cached odds and item looks (a {@code /knk lootbox reload}). */
    public void clearCaches() {
        odds.clear();
        previews.clear();
    }

    // ===== The menu is look-only =====

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Spin) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Spin) {
            event.setCancelled(true);
        }
    }

    /** Closing the menu before the reel stops skips to the result: the item is handed over at once. */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Spin spin) || spin.finished || spins.get(spin.playerId) != spin) {
            return;
        }
        spin.stop();
        spin.finished = true;
        spins.remove(spin.playerId);
        if (event.getPlayer() instanceof Player player && player.isOnline()) {
            // After the close event: the inventory view is gone, so an item can't land in the menu.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    finish(player, spin.prepared, spin.giftedBy, settings.get());
                } else {
                    remember(spin.playerId, new Pending(spin.prepared, spin.giftedBy));
                }
            });
        }
    }

    /** A player who leaves mid-spin gets the item when they come back (the API's pending claims are the backstop). */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Spin spin = spins.remove(event.getPlayer().getUniqueId());
        if (spin != null) {
            spin.stop();
            spin.finished = true;
            remember(spin.playerId, new Pending(spin.prepared, spin.giftedBy));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        List<Pending> waiting = interrupted.remove(id);
        if (waiting == null || waiting.isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                interrupted.computeIfAbsent(id, ignored -> new ArrayList<>()).addAll(waiting);
                return;
            }
            for (Pending pending : waiting) {
                finish(player, pending.prepared(), pending.giftedBy(), settings.get());
            }
        }, 40L);
    }

    private void remember(UUID playerId, Pending pending) {
        interrupted.computeIfAbsent(playerId, ignored -> new ArrayList<>()).add(pending);
    }

    // ===== Looks =====

    private static ItemStack pane(Material material, String name) {
        ItemStack pane = new ItemStack(material);
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize(name));
            pane.setItemMeta(meta);
        }
        return pane;
    }

    /** The frame's colour follows the box grade's label colour (DESIGN.md §3.4 grade colours). */
    static Material frameColor(int boxStars) {
        return switch (Math.max(1, Math.min(boxStars, 10))) {
            case 1, 2 -> Material.BLUE_STAINED_GLASS_PANE;
            case 3, 4 -> Material.LIGHT_BLUE_STAINED_GLASS_PANE;
            case 5 -> Material.MAGENTA_STAINED_GLASS_PANE;
            case 6, 7 -> Material.ORANGE_STAINED_GLASS_PANE;
            case 8, 9 -> Material.RED_STAINED_GLASS_PANE;
            default -> Material.PURPLE_STAINED_GLASS_PANE;
        };
    }

    /** One player's spinning reel; also the menu's holder, so clicks in it are recognised. */
    static final class Spin implements InventoryHolder {
        final UUID playerId;
        final LootboxDelivery.Prepared prepared;
        final String giftedBy;
        final LootboxReel<ItemStack> reel;
        Inventory inventory;
        BukkitTask task;
        int step;
        boolean finished;

        Spin(UUID playerId, LootboxDelivery.Prepared prepared, String giftedBy, LootboxReel<ItemStack> reel) {
            this.playerId = playerId;
            this.prepared = prepared;
            this.giftedBy = giftedBy;
            this.reel = reel;
        }

        void stop() {
            if (task != null) {
                task.cancel();
                task = null;
            }
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
