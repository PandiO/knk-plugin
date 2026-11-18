package net.knightsandkings.knk.paper.regions;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Protection of WorldGuard's global region (QOL hotfix, 2026-10-25): where no region but {@code __global__}
 * applies - outside every domain, arena and other region - a default player may not
 * <ul>
 *   <li>break or place blocks (buckets included),</li>
 *   <li>place, break, use or hit item frames, paintings and armor stands,</li>
 *   <li>use crafting blocks (crafting table, furnaces, anvils, enchanting table, ...; {@link #DEFAULT_CRAFTING_BLOCKS},
 *       the {@code crafting-blocks} list in {@code config.yml}),</li>
 * </ul>
 * and explosions (TNT, creepers, beds, ...) break no blocks, item frames, paintings or armor stands there. Doors,
 * trapdoors, gates, chests and other storage stay usable: nothing here touches them. Holders of
 * {@code knk.region.bypass} and of WorldGuard's region bypass (ops) are not restricted. Inside a region nothing
 * changes: the domain's own rules and WorldGuard's flags decide there, as before.
 * <p>
 * A plugin rule, not flags on {@code __global__}: WorldGuard's {@code block-break}/{@code block-place} would also
 * bind the members of every region (membership only lifts {@code build}) and would have to be written into each
 * world's region file. Switch: {@code regions.global.protection} (default on), read on every event.
 * <p>
 * The refused player sees a short action-bar notice, at most once per {@value #NOTICE_COOLDOWN_MILLIS} ms, so a
 * held mouse button does not flood them.
 */
public final class GlobalRegionProtectionListener implements Listener {

    /** {@code config.yml} switch; absent means on. */
    public static final String CONFIG_PATH = "regions.global.protection";
    /** {@code config.yml} list of material names a default player may not use there; absent means the defaults. */
    public static final String CRAFTING_BLOCKS_PATH = "regions.global.crafting-blocks";
    static final long NOTICE_COOLDOWN_MILLIS = 2000L;
    static final String BUILD_NOTICE = "You can't build here.";
    static final String USE_NOTICE = "You can't use that here.";

    /** Crafting and processing blocks: everything with a work-station menu. Not storage (chests, barrels, hoppers). */
    public static final Set<Material> DEFAULT_CRAFTING_BLOCKS = Set.of(
        Material.CRAFTING_TABLE, Material.CRAFTER, Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER,
        Material.BREWING_STAND, Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL,
        Material.ENCHANTING_TABLE, Material.SMITHING_TABLE, Material.GRINDSTONE, Material.STONECUTTER,
        Material.LOOM, Material.CARTOGRAPHY_TABLE, Material.FLETCHING_TABLE);

    private final BooleanSupplier enabled;
    private final Set<Material> craftingBlocks;
    private final Function<Location, Set<String>> regionsAt;
    private final Predicate<Player> bypass;
    private final LongSupplier clock;
    private final Map<UUID, Long> lastNoticeAt = new ConcurrentHashMap<>();

    /**
     * @param enabled        the config switch, read on every event so a config reload takes effect
     * @param craftingBlocks the blocks a default player may not use there ({@link #craftingBlocks(List, Consumer)})
     * @param regionsAt      the WorldGuard region ids at a location without {@code __global__} ({@link RegionIds#at})
     * @param bypass         who is never restricted ({@code knk.region.bypass}, WorldGuard's region bypass)
     */
    public GlobalRegionProtectionListener(BooleanSupplier enabled, Set<Material> craftingBlocks,
                                          Function<Location, Set<String>> regionsAt, Predicate<Player> bypass) {
        this(enabled, craftingBlocks, regionsAt, bypass, System::currentTimeMillis);
    }

    GlobalRegionProtectionListener(BooleanSupplier enabled, Set<Material> craftingBlocks,
                                   Function<Location, Set<String>> regionsAt, Predicate<Player> bypass,
                                   LongSupplier clock) {
        this.enabled = Objects.requireNonNull(enabled, "enabled");
        this.craftingBlocks = craftingBlocks == null || craftingBlocks.isEmpty()
            ? DEFAULT_CRAFTING_BLOCKS : Set.copyOf(craftingBlocks);
        this.regionsAt = Objects.requireNonNull(regionsAt, "regionsAt");
        this.bypass = Objects.requireNonNull(bypass, "bypass");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The {@code crafting-blocks} config list as materials: unknown names go to {@code warn} and are skipped; an
     * empty or absent list means {@link #DEFAULT_CRAFTING_BLOCKS}.
     */
    public static Set<Material> craftingBlocks(List<String> names, Consumer<String> warn) {
        if (names == null || names.isEmpty()) {
            return DEFAULT_CRAFTING_BLOCKS;
        }
        Set<Material> materials = EnumSet.noneOf(Material.class);
        for (String name : names) {
            Material material = name == null ? null : Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
            if (material == null) {
                warn.accept(CRAFTING_BLOCKS_PATH + ": unknown material '" + name + "' ignored");
            } else {
                materials.add(material);
            }
        }
        return materials.isEmpty() ? DEFAULT_CRAFTING_BLOCKS : Set.copyOf(materials);
    }

    // ---- the rule ----

    /** Whether the rule applies at {@code where}: switched on and no region but {@code __global__} there. */
    private boolean outside(Location where) {
        if (!enabled.getAsBoolean() || where == null) {
            return false;
        }
        Set<String> regions = regionsAt.apply(where);
        return regions == null || regions.isEmpty();
    }

    private boolean restricted(Player player, Location where) {
        return outside(where) && !bypass.test(player);
    }

    private static Player attackingPlayer(Entity actor) {
        if (actor instanceof Player player) {
            return player;
        }
        if (actor instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    /** Item frames, paintings and armor stands: the decoration entities the rule covers. */
    private static boolean decoration(Entity entity) {
        return entity instanceof Hanging || entity instanceof ArmorStand;
    }

    // ---- blocks ----
    // HIGH like the hub's building rules: after the LOWEST join-loading guard, before the gate listener at HIGHEST
    // (gate doors sit in their structure's region, so the rule never reaches them).

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (restricted(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            notify(event.getPlayer(), BUILD_NOTICE);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (restricted(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            notify(event.getPlayer(), BUILD_NOTICE);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (restricted(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            notify(event.getPlayer(), BUILD_NOTICE);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (restricted(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            notify(event.getPlayer(), BUILD_NOTICE);
        }
    }

    // ---- explosions: the blast stays, the blocks outside every region do not break ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::outsideBlock);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::outsideBlock);
    }

    private boolean outsideBlock(Block block) {
        return outside(block.getLocation());
    }

    // ---- item frames, paintings, armor stands ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        Location where = event.getEntity().getLocation();
        if (event.getPlayer() != null ? restricted(event.getPlayer(), where) : outside(where)) {
            event.setCancelled(true);
            if (event.getPlayer() != null) {
                notify(event.getPlayer(), BUILD_NOTICE);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ArmorStand)) {
            return;
        }
        Location where = event.getEntity().getLocation();
        if (event.getPlayer() != null ? restricted(event.getPlayer(), where) : outside(where)) {
            event.setCancelled(true);
            if (event.getPlayer() != null) {
                notify(event.getPlayer(), BUILD_NOTICE);
            }
        }
    }

    /**
     * A player (or their arrow) taking an item frame or painting down, or an explosion doing so. A frame falling
     * because its wall went (PHYSICS) is left alone: that wall went by a permitted action.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        Location where = event.getEntity().getLocation();
        if (event instanceof HangingBreakByEntityEvent byEntity) {
            Player remover = attackingPlayer(byEntity.getRemover());
            if (remover != null) {
                if (restricted(remover, where)) {
                    event.setCancelled(true);
                    notify(remover, USE_NOTICE);
                }
                return;
            }
        }
        if ((event.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION
                || event.getCause() == HangingBreakEvent.RemoveCause.ENTITY) && outside(where)) {
            event.setCancelled(true);
        }
    }

    /** Hitting an item frame (drops its item), a painting or an armor stand; explosions reaching them. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        if (!decoration(victim)) {
            return;
        }
        Player attacker = attackingPlayer(event.getDamager());
        if (attacker != null) {
            if (restricted(attacker, victim.getLocation())) {
                event.setCancelled(true);
                notify(attacker, USE_NOTICE);
            }
            return;
        }
        if ((event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) && outside(victim.getLocation())) {
            event.setCancelled(true);
        }
    }

    /** Rotating or filling an item frame; a right-click on an armor stand that is not an equipment swap. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity target = event.getRightClicked();
        if (decoration(target) && restricted(event.getPlayer(), target.getLocation())) {
            event.setCancelled(true);
            notify(event.getPlayer(), USE_NOTICE);
        }
    }

    /** Taking equipment off or putting it on an armor stand (its own handler list, so not {@link #onInteractEntity}). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (restricted(event.getPlayer(), event.getRightClicked().getLocation())) {
            event.setCancelled(true);
            notify(event.getPlayer(), USE_NOTICE);
        }
    }

    // ---- crafting blocks (doors, trapdoors, chests and other storage are not looked at) ----

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null
                || event.useInteractedBlock() == Event.Result.DENY || !craftingBlocks.contains(block.getType())) {
            return;
        }
        if (restricted(event.getPlayer(), block.getLocation())) {
            event.setUseInteractedBlock(Event.Result.DENY);
            notify(event.getPlayer(), USE_NOTICE);
        }
    }

    // ---- notice ----

    private void notify(Player player, String text) {
        long now = clock.getAsLong();
        Long last = lastNoticeAt.get(player.getUniqueId());
        if (last != null && now - last < NOTICE_COOLDOWN_MILLIS) {
            return;
        }
        lastNoticeAt.put(player.getUniqueId(), now);
        player.sendActionBar(Component.text(text, NamedTextColor.RED));
    }
}
