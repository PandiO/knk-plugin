package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.dataaccess.TeleportDestinationsDataAccess;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.teleport.KnkTeleportDestination;
import net.knightsandkings.knk.core.menu.MenuActionException;
import net.knightsandkings.knk.core.teleport.TeleportKind;
import net.knightsandkings.knk.core.teleport.WarmupPolicy;
import net.knightsandkings.knk.paper.commands.SpawnCommand;
import net.knightsandkings.knk.paper.commands.WarpCommand;
import net.knightsandkings.knk.paper.menu.MenuActionContext;
import net.knightsandkings.knk.paper.menu.MenuContentSourceContext;
import net.knightsandkings.knk.paper.menu.MenuFeature;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;
import net.knightsandkings.knk.paper.teleport.TeleportAuditor;
import net.knightsandkings.knk.paper.teleport.TeleportNodes;
import net.knightsandkings.knk.paper.teleport.TeleportRequestService;
import net.knightsandkings.knk.paper.teleport.TeleportService;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Teleport menu (docs/specs/teleport DESIGN.md §3.8, KNG-17 Phase 6): {@code teleport.destinations},
 * opened by a bare {@code /warp} and the hub's Teleport tile. Registers
 * <ul>
 *   <li>row source {@code teleport.destinations} → {@link TeleportDestinationRow}: the viewer's warp
 *       list - the same cached {@code GET api/teleport-destinations} list as {@code /warps} - with
 *       their {@code knk.teleport.warp} and bypass nodes applied. The nodes (and the warmup ones)
 *       are read in the same async step and remembered for the root;</li>
 *   <li>root {@code teleport} → {@link TeleportMenuView}: the viewer's warmup, whether Spawn works
 *       for them, their pending {@code /tpa} requests (main thread, no I/O);</li>
 *   <li>action {@code teleport.warp {domainId}} → closes the menu and runs
 *       {@link WarpCommand#warpTo}, the {@code /warp <name>} path;</li>
 *   <li>action {@code teleport.spawn} → closes the menu and runs {@link SpawnCommand#teleportSelf};</li>
 *   <li>action {@code teleport.requests} → closes the menu and shows the pending requests in chat
 *       with their clickable {@code [Accept] [Deny]} ({@link TeleportRequestService#remind}).</li>
 * </ul>
 * Every click goes through the teleport engine ({@link TeleportService#start}) exactly as the
 * command does: warmup, cooldown, combat tag, freeze and every registered
 * {@link net.knightsandkings.knk.paper.teleport.TeleportRestriction} - the siege guard included,
 * which matters because {@code /menu} is on the siege command filter's allow list. Warps are charged
 * by the server after the warmup, as for {@code /warp}.
 * <p>
 * The teleport engine starts after the menu registries are locked (it needs services built later in
 * {@code onEnable}), so the feature reads it through a {@link Supplier}; while that supplies null
 * (engine or API client down) the grid shows a single "unavailable" row and the actions say so.
 */
public final class TeleportMenuFeature implements MenuFeature {

    public static final String MENU_KEY = "teleport.destinations";
    public static final String ROOT = "teleport";
    public static final String ROWS_SOURCE = "teleport.destinations";
    public static final String WARP_ACTION = "teleport.warp";
    public static final String SPAWN_ACTION = "teleport.spawn";
    public static final String REQUESTS_ACTION = "teleport.requests";
    static final String UNAVAILABLE = "Teleports aren't available right now.";
    static final int MAX_REMEMBERED = 256;

    private static final Logger LOGGER = Logger.getLogger(TeleportMenuFeature.class.getName());

    /**
     * The parts of the teleport engine the menu uses; any of the commands/services may be null when
     * they failed to start.
     */
    public record Teleports(
            TeleportService engine,
            TeleportDestinationsDataAccess destinations,
            TeleportAuditor.UserIdLookup userIds,
            TeleportService.PermissionLookup permissions,
            WarpCommand warps,
            SpawnCommand spawn,
            TeleportRequestService requests
    ) {
    }

    /** What the viewer's nodes allow, read with their rows. */
    record Access(boolean canWarp, boolean canSpawn, boolean bypassRequirements, boolean bypassCost, int warmupSeconds) {
    }

    private final Supplier<Teleports> teleports;
    private final Map<UUID, Access> accessByViewer = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Access> eldest) {
                    return size() > MAX_REMEMBERED;
                }
            });

    public TeleportMenuFeature(Supplier<Teleports> teleports) {
        this.teleports = teleports;
    }

    @Override
    public void registerMenuHandlers(MenuFeatureRegistries registries) {
        registries.variables().register(ROOT, TeleportMenuView.class, (player, ctx) -> viewFor(player));
        registries.contentSources().registerRows(ROWS_SOURCE, TeleportDestinationRow.class,
                (context, params, query) -> fetchRows(context));
        registries.actions().register(WARP_ACTION, this::warp);
        registries.actions().register(SPAWN_ACTION, this::spawn);
        registries.actions().register(REQUESTS_ACTION, this::requests);
    }

    private Teleports teleports() {
        return teleports != null ? teleports.get() : null;
    }

    // ===== root =====

    /** Main thread, no I/O. */
    TeleportMenuView viewFor(Player player) {
        Teleports t = teleports();
        if (player == null || t == null) {
            return new TeleportMenuView(null, false, List.of());
        }
        Access access = accessByViewer.get(player.getUniqueId());
        boolean spawn = t.spawn() != null && (access == null || access.canSpawn());
        List<TeleportRequestService.Pending> pending = t.requests() != null ? t.requests().pending(player) : List.of();
        return new TeleportMenuView(access != null ? access.warmupSeconds() : null, spawn, pending);
    }

    // ===== rows =====

    /** Main thread. Completes when the (cached) list and the viewer's nodes are in; never fails. */
    CompletableFuture<Page<TeleportDestinationRow>> fetchRows(MenuContentSourceContext context) {
        Player player = context.player();
        Teleports t = teleports();
        if (player == null || t == null || t.destinations() == null || t.warps() == null
                || t.userIds() == null || t.permissions() == null) {
            return CompletableFuture.completedFuture(page(List.of(TeleportDestinationRow.unavailable())));
        }
        UUID uuid = player.getUniqueId();
        CompletableFuture<List<KnkTeleportDestination>> list;
        CompletableFuture<Access> access;
        try {
            list = t.userIds().idOf(uuid).thenCompose(userId -> userId == null
                    ? CompletableFuture.completedFuture((List<KnkTeleportDestination>) null)
                    : t.destinations().listAsync(userId));
            access = access(t, player);
        } catch (RuntimeException e) {
            list = CompletableFuture.failedFuture(e);
            access = CompletableFuture.failedFuture(e);
        }
        return list
                .thenCombine(access, (destinations, viewer) -> {
                    accessByViewer.put(uuid, viewer);
                    return page(rows(destinations, viewer));
                })
                .exceptionally(ex -> {
                    LOGGER.log(Level.WARNING, "teleport.destinations: failed to load the warps of " + player.getName(), ex);
                    return page(List.of(TeleportDestinationRow.unavailable()));
                });
    }

    static List<TeleportDestinationRow> rows(List<KnkTeleportDestination> destinations, Access viewer) {
        if (destinations == null) {
            return List.of(TeleportDestinationRow.unavailable());
        }
        if (destinations.isEmpty()) {
            return List.of(TeleportDestinationRow.none());
        }
        List<TeleportDestinationRow> rows = new ArrayList<>(destinations.size());
        for (KnkTeleportDestination destination : destinations) {
            rows.add(TeleportDestinationRow.of(destination, viewer.canWarp(), viewer.bypassRequirements(), viewer.bypassCost()));
        }
        return rows;
    }

    /** The viewer's warp, spawn, bypass and warmup nodes (a failed lookup counts as not held). */
    private static CompletableFuture<Access> access(Teleports t, Player player) {
        CompletableFuture<Boolean> warp = has(t, player, TeleportNodes.WARP);
        CompletableFuture<Boolean> spawn = has(t, player, TeleportNodes.SPAWN);
        CompletableFuture<Boolean> requirements = has(t, player, TeleportNodes.BYPASS_REQUIREMENTS);
        CompletableFuture<Boolean> cost = has(t, player, TeleportNodes.BYPASS_COST);
        CompletableFuture<Boolean> shortWarmup = has(t, player, TeleportNodes.WARMUP_SHORT);
        CompletableFuture<Boolean> noWarmup = has(t, player, TeleportNodes.BYPASS_WARMUP);
        return CompletableFuture.allOf(warp, spawn, requirements, cost, shortWarmup, noWarmup).thenApply(done -> {
            int warmup = t.engine() != null
                    ? new WarmupPolicy(t.engine().settings()).warmupSeconds(TeleportKind.WARP, shortWarmup.join(), noWarmup.join())
                    : 0;
            return new Access(warp.join(), spawn.join(), requirements.join(), cost.join(), warmup);
        });
    }

    private static CompletableFuture<Boolean> has(Teleports t, Player player, String node) {
        try {
            return t.permissions().has(player, node).thenApply(Boolean.TRUE::equals).exceptionally(ex -> false);
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(false);
        }
    }

    private static <T> Page<T> page(List<T> rows) {
        return new Page<>(rows, rows.size(), 1, Math.max(1, rows.size()));
    }

    // ===== actions =====

    private void warp(MenuActionContext context, Map<String, String> params) {
        int domainId = domainId(params);
        Player player = context.player();
        player.closeInventory();
        Teleports t = teleports();
        if (t == null || t.warps() == null) {
            player.sendMessage(ChatColor.RED + UNAVAILABLE);
            return;
        }
        // The /warp <name> path: node, lock, warmup, engine guards, then the server-side charge.
        t.warps().warpTo(player, domainId);
    }

    private void spawn(MenuActionContext context, Map<String, String> params) {
        Player player = context.player();
        player.closeInventory();
        Teleports t = teleports();
        if (t == null || t.spawn() == null) {
            player.sendMessage(ChatColor.RED + UNAVAILABLE);
            return;
        }
        t.spawn().teleportSelf(player);
    }

    private void requests(MenuActionContext context, Map<String, String> params) {
        Player player = context.player();
        player.closeInventory();
        Teleports t = teleports();
        if (t == null || t.requests() == null) {
            player.sendMessage(ChatColor.RED + UNAVAILABLE);
            return;
        }
        t.requests().remind(player);
    }

    private static int domainId(Map<String, String> params) {
        String raw = params.get("domainId");
        try {
            int id = Integer.parseInt(raw == null ? "" : raw.trim());
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException e) {
            throw new MenuActionException(WARP_ACTION + " action needs a positive numeric 'domainId' param, got '" + raw + "'");
        }
    }
}
