package net.knightsandkings.knk.paper.menu.content;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.menu.ConditionOutcome;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.menu.MenuSession;
import net.knightsandkings.knk.core.ports.api.StructureMenusApi;
import net.knightsandkings.knk.core.production.menu.StructureFacets;
import net.knightsandkings.knk.core.production.menu.StructureMenu;
import net.knightsandkings.knk.core.production.menu.StructureRow;
import net.knightsandkings.knk.core.production.menu.TransportDryRun;
import net.knightsandkings.knk.core.production.menu.TransportOrderRow;
import net.knightsandkings.knk.paper.menu.MenuActionContext;
import net.knightsandkings.knk.paper.menu.MenuContentSourceContext;
import net.knightsandkings.knk.paper.menu.MenuFeature;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;
import net.knightsandkings.knk.paper.menu.MenuObserver;
import net.knightsandkings.knk.paper.menu.MenuService;

/**
 * The in-game structure menus (PandiO/knk-workspace#74, production DESIGN § 11.2, decision record § 15.1 M1-M5), seeded
 * as menu content by knk-web-api (#683, {@code MenuTemplateSeed.Production.cs}). Registers:
 * <ol>
 *   <li>roots {@code structures} ({@link StructuresView}: facet values and filter lines), {@code structure}
 *       ({@link StructureView}: the last menu read for the viewer and {@code ctx.structureId}) and {@code structureOrders}
 *       ({@link StructureOrdersView});</li>
 *   <li>row sources {@code structures.overview} ({@code POST api/StructureMenus/search}; facets Town and Kind by name,
 *       Status as it is), {@code structures.storages} ({@code GET api/StructureMenus/{id}}'s storages; it also fills the
 *       {@code structure} root) and {@code structures.orders} ({@code GET api/StructureMenus/{id}/orders}; 403 for a
 *       visitor is an empty list);</li>
 *   <li>staff actions (M4, {@value #STAFF_NODE}): {@code structures.survey}, {@code structures.dry-run},
 *       {@code structures.outline} ({@code structureId}), {@code structures.order-cancel} and
 *       {@code structures.order-retry} ({@code orderId}, behind {@code menu.confirm.request}); and the condition
 *       {@code structures.pending}.</li>
 * </ol>
 * Every read acts for the viewer ({@code X-Acting-User-Id}), so the API filters the detail (M3) and the plugin never holds
 * stock it may not show. As a {@link MenuObserver} it sets the overview's Town filter to the town whose land the player
 * stands in when the overview is opened from another menu or a command (DESIGN § 11.2.1). Providers run on the main
 * thread and never do I/O: they read what the last fetch left and start a fetch when nothing is there.
 */
public final class StructuresMenuFeature implements MenuFeature, MenuObserver {

    public static final String OVERVIEW_MENU = "structures.overview";
    public static final String STRUCTURE_MENU = "structures.structure";
    public static final String ORDERS_MENU = "structures.orders";
    public static final List<String> MENU_KEYS = List.of(OVERVIEW_MENU, STRUCTURE_MENU, ORDERS_MENU);
    /** The overview's content section, whose Town filter is preset. */
    public static final String OVERVIEW_SECTION = "Structures";
    /** Staff buttons and actions (M4); the API checks the same node on its staff routes. */
    public static final String STAFF_NODE = "knk.admin.world";
    static final long FACETS_TTL_MILLIS = 5 * 60_000L;
    static final long MENU_TTL_MILLIS = 10_000L;
    static final int FACET_PAGE_SIZE = 200;
    static final int MAX_REMEMBERED = 512;

    private static final Logger LOGGER = Logger.getLogger(StructuresMenuFeature.class.getName());

    /** The staff buttons that reuse {@code /knk production}: survey now and show the area outline. */
    public interface StaffTools {
        void survey(Player player, int structureId);

        void outline(Player player, int structureId);
    }

    private record Cached<T>(long at, T value) {
    }

    private final StructureMenusApi api;
    private final UserCache userCache;
    private final Clock clock;
    private final Executor mainThread;
    private final Predicate<Player> staff;
    private final Function<Player, Integer> townAt;
    private final StaffTools staffTools;
    private final Supplier<MenuService> menuService;
    private volatile Cached<StructureFacets> facets;
    private final Set<String> fetching = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Map<String, String>> lastFilters = new ConcurrentHashMap<>();
    private final Map<String, Cached<StructureMenu>> menus = remembered();
    private final Map<String, StructureOrdersView> orderCounts = remembered();

    /**
     * @param staff  whether a player holds {@value #STAFF_NODE} (cached check; the actions run on a click)
     * @param townAt the town whose land a player stands in (territory lookup, #689), or null
     */
    public StructuresMenuFeature(StructureMenusApi api, UserCache userCache, Clock clock, Executor mainThread,
                                 Predicate<Player> staff, Function<Player, Integer> townAt, StaffTools staffTools,
                                 Supplier<MenuService> menuService) {
        this.api = api;
        this.userCache = userCache;
        this.clock = clock;
        this.mainThread = mainThread;
        this.staff = staff;
        this.townAt = townAt;
        this.staffTools = staffTools;
        this.menuService = menuService;
    }

    @Override
    public void registerMenuHandlers(MenuFeatureRegistries registries) {
        registries.variables().register("structures", StructuresView.class, (player, ctx) -> overviewView(player));
        registries.variables().register("structure", StructureView.class, (player, ctx) -> structureView(player, ctx));
        registries.variables().register("structureOrders", StructureOrdersView.class, (player, ctx) -> ordersView(player, ctx));

        registries.contentSources().registerRows("structures.overview", StructureOverviewRow.class,
                (context, params, query) -> fetchOverview(context, query));
        registries.contentSources().registerRows("structures.storages", StructureStorageRow.class,
                (context, params, query) -> fetchStorages(context, params));
        registries.contentSources().registerRows("structures.orders", TransportOrderMenuRow.class,
                (context, params, query) -> fetchOrders(context, params, query));

        registries.actions().register("structures.survey", (context, params) -> staffAction(context, params, "survey"));
        registries.actions().register("structures.outline", (context, params) -> staffAction(context, params, "outline"));
        registries.actions().register("structures.dry-run", (context, params) -> staffAction(context, params, "dry-run"));
        registries.actions().register("structures.order-cancel", (context, params) -> orderAction(context, params, true));
        registries.actions().register("structures.order-retry", (context, params) -> orderAction(context, params, false));
        registries.conditions().register("structures.pending", (context, params) -> pending(context));
    }

    // ===== town preset (MenuObserver) =====

    @Override
    public void menuOpened(Player player, String menuKey, String parentMenuKey) {
        if (!OVERVIEW_MENU.equals(menuKey) || player == null
                || (parentMenuKey != null && parentMenuKey.startsWith("structures."))) {
            return;
        }
        Integer townId;
        try {
            townId = townAt.apply(player);
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "structures: territory lookup failed", e);
            townId = null;
        }
        MenuService service = menuService.get();
        if (townId == null || service == null) {
            return;
        }
        service.filter(player, OVERVIEW_SECTION, StructureFacets.TOWN, facets().townValue(townId));
    }

    @Override
    public void menuClosed(UUID playerId, String menuKey) {
        if (OVERVIEW_MENU.equals(menuKey)) {
            lastFilters.remove(playerId);
        }
    }

    // ===== roots (main thread, no I/O) =====

    StructuresView overviewView(Player player) {
        Map<String, String> filters = player == null ? Map.of() : lastFilters.getOrDefault(player.getUniqueId(), Map.of());
        return new StructuresView(facets(), filters);
    }

    StructureView structureView(Player player, MenuContextParams ctx) {
        Integer structureId = parseId(ctx == null ? null : ctx.get("structureId"));
        String name = ctx == null ? null : ctx.get("name");
        if (player == null || structureId == null) {
            return new StructureView(null, name);
        }
        Cached<StructureMenu> cached = menus.get(key(player.getUniqueId(), structureId));
        if (cached == null) {
            readMenu(player, structureId);
        }
        return new StructureView(cached == null ? null : cached.value(), name);
    }

    StructureOrdersView ordersView(Player player, MenuContextParams ctx) {
        Integer structureId = parseId(ctx == null ? null : ctx.get("structureId"));
        if (player == null || structureId == null) {
            return new StructureOrdersView(null, false);
        }
        return orderCounts.getOrDefault(key(player.getUniqueId(), structureId), new StructureOrdersView(null, false));
    }

    /** The facet lists; an empty or stale list starts a background read of every structure (one page of 200). */
    StructureFacets facets() {
        Cached<StructureFacets> current = facets;
        if ((current == null || clock.millis() - current.at() >= FACETS_TTL_MILLIS) && fetching.add("facets")) {
            try {
                api.search(new PagedQuery(1, FACET_PAGE_SIZE, null, null, false, Map.of()), null)
                        .whenComplete((page, error) -> {
                            fetching.remove("facets");
                            if (error != null) {
                                LOGGER.log(Level.FINE, "structures: facet read failed", error);
                                return;
                            }
                            facets = new Cached<>(clock.millis(), StructureFacets.of(page.items()));
                        });
            } catch (RuntimeException e) {
                fetching.remove("facets");
            }
        }
        return current == null ? StructureFacets.EMPTY : current.value();
    }

    // ===== sources =====

    CompletableFuture<Page<StructureOverviewRow>> fetchOverview(MenuContentSourceContext context, PagedQuery query) {
        Player player = context.player();
        PagedQuery engine = query == null ? new PagedQuery(1, 36, null, null, false, Map.of()) : query;
        Map<String, String> facetValues = engine.filters() == null ? Map.of() : Map.copyOf(engine.filters());
        if (player != null) {
            Map<String, String> previous = lastFilters.put(player.getUniqueId(), facetValues);
            if (previous != null && !previous.equals(facetValues)) {
                refresh(player); // the header's filter lines read these: repaint once with the new ones
            }
        }
        PagedQuery apiQuery = new PagedQuery(Math.max(1, engine.pageNumber()), Math.max(1, engine.pageSize()),
                engine.searchTerm(), null, false, facets().toApiFilters(facetValues));
        CompletableFuture<Page<StructureRow>> page;
        try {
            page = api.search(apiQuery, player == null ? null : userId(player.getUniqueId()));
        } catch (RuntimeException e) {
            page = CompletableFuture.failedFuture(e);
        }
        return page.thenApply(result -> {
            List<StructureOverviewRow> rows = new ArrayList<>();
            for (StructureRow row : result.items()) {
                rows.add(new StructureOverviewRow(row));
            }
            return new Page<>(rows, result.totalCount(), apiQuery.pageNumber(), apiQuery.pageSize());
        }).exceptionally(ex -> {
            LOGGER.log(Level.WARNING, "structures.overview: search failed", ex);
            return new Page<>(List.of(), 0, apiQuery.pageNumber(), apiQuery.pageSize());
        });
    }

    CompletableFuture<Page<StructureStorageRow>> fetchStorages(MenuContentSourceContext context, Map<String, String> params) {
        Player player = context.player();
        Integer structureId = parseId(params.get("structureId"));
        if (player == null || structureId == null) {
            return CompletableFuture.completedFuture(new Page<>(List.of(), 0, 1, 1));
        }
        Cached<StructureMenu> cached = menus.get(key(player.getUniqueId(), structureId));
        CompletableFuture<StructureMenu> menu = cached != null && clock.millis() - cached.at() < MENU_TTL_MILLIS
                ? CompletableFuture.completedFuture(cached.value())
                : readMenu(player, structureId);
        return menu.thenApply(m -> {
            List<StructureStorageRow> rows = new ArrayList<>();
            if (m != null && m.showsDetail()) {
                for (StructureMenu.StorageView storage : m.detail().storages()) {
                    rows.add(new StructureStorageRow(storage));
                }
            }
            return new Page<>(rows, rows.size(), 1, Math.max(1, rows.size()));
        });
    }

    CompletableFuture<Page<TransportOrderMenuRow>> fetchOrders(MenuContentSourceContext context, Map<String, String> params,
                                                               PagedQuery query) {
        Player player = context.player();
        Integer structureId = parseId(params.get("structureId"));
        int number = query == null ? 1 : Math.max(1, query.pageNumber());
        int size = query == null ? 28 : Math.max(1, query.pageSize());
        if (player == null || structureId == null) {
            return CompletableFuture.completedFuture(new Page<>(List.of(), 0, number, size));
        }
        UUID uuid = player.getUniqueId();
        boolean isStaff = staff.test(player);
        Instant now = clock.instant();
        CompletableFuture<Page<TransportOrderRow>> page;
        try {
            page = api.orders(structureId, number, size, userId(uuid));
        } catch (RuntimeException e) {
            page = CompletableFuture.failedFuture(e);
        }
        return page.thenApply(result -> {
            remember(player, structureId, new StructureOrdersView(result.totalCount(), false));
            List<TransportOrderMenuRow> rows = new ArrayList<>();
            for (TransportOrderRow order : result.items()) {
                rows.add(new TransportOrderMenuRow(order, isStaff, now));
            }
            return new Page<>(rows, result.totalCount(), number, size);
        }).exceptionally(ex -> {
            boolean forbidden = status(ex) == 403;
            remember(player, structureId, new StructureOrdersView(null, forbidden));
            if (!forbidden) {
                LOGGER.log(Level.WARNING, "structures.orders: failed to read the orders of structure " + structureId, ex);
            }
            return new Page<>(List.of(), 0, number, size);
        });
    }

    private void remember(Player player, int structureId, StructureOrdersView view) {
        StructureOrdersView previous = orderCounts.put(key(player.getUniqueId(), structureId), view);
        if (!view.equals(previous)) {
            refresh(player);
        }
    }

    /** Reads and remembers the viewer's menu; repaints when it changed. Completes with null on failure. */
    private CompletableFuture<StructureMenu> readMenu(Player player, int structureId) {
        UUID uuid = player.getUniqueId();
        String key = key(uuid, structureId);
        CompletableFuture<Optional<StructureMenu>> read;
        try {
            read = api.get(structureId, userId(uuid));
        } catch (RuntimeException e) {
            read = CompletableFuture.failedFuture(e);
        }
        return read.thenApply(found -> {
            StructureMenu menu = found.orElse(null);
            Cached<StructureMenu> previous = menus.put(key, new Cached<>(clock.millis(), menu));
            if (previous == null || !Objects.equals(previous.value(), menu)) {
                refresh(player);
            }
            return menu;
        }).exceptionally(ex -> {
            LOGGER.log(Level.WARNING, "structures: failed to read the menu of structure " + structureId, ex);
            return null;
        });
    }

    /** Any thread: marks the player's open menu dirty and repaints it on the next tick. */
    private void refresh(Player player) {
        MenuService service = menuService.get();
        if (service != null && player != null) {
            service.refreshOpenMenu(player);
        }
    }

    // ===== actions =====

    private void staffAction(MenuActionContext context, Map<String, String> params, String what) {
        Player player = context.player();
        if (!staff.test(player)) {
            player.sendMessage(ChatColor.RED + "Only staff can do that.");
            return;
        }
        Integer structureId = parseId(params.get("structureId"));
        if (structureId == null) {
            player.sendMessage(ChatColor.RED + "This menu has no structure.");
            return;
        }
        switch (what) {
            case "survey" -> staffTools.survey(player, structureId);
            case "outline" -> staffTools.outline(player, structureId);
            default -> dryRun(player, structureId);
        }
    }

    void dryRun(Player player, int structureId) {
        player.sendMessage(ChatColor.GRAY + "Transport dry run of structure #" + structureId + "...");
        api.transportDryRun(structureId, userId(player.getUniqueId())).whenCompleteAsync((found, error) -> {
            if (error != null) {
                player.sendMessage(ChatColor.RED + "Dry run failed: " + describe(error));
                return;
            }
            if (found.isEmpty()) {
                player.sendMessage(ChatColor.RED + "Structure #" + structureId + " is not a production structure.");
                return;
            }
            for (String line : dryRunLines(found.get())) {
                player.sendMessage(line);
            }
        }, mainThread);
    }

    static List<String> dryRunLines(TransportDryRun run) {
        List<String> lines = new ArrayList<>();
        lines.add(ChatColor.GOLD + "Transport dry run" + ChatColor.GRAY + " (nothing changes)");
        if (!run.featureEnabled()) {
            lines.add(ChatColor.YELLOW + "Production is switched off in the API; orders don't move.");
        }
        lines.add(ChatColor.GRAY + "Automatic transport: " + ChatColor.WHITE + (run.automaticTransport() ? "on" : "off")
                + ChatColor.GRAY + ", road: " + ChatColor.WHITE + run.roadConnection());
        lines.add(ChatColor.GRAY + "Output: " + ChatColor.WHITE + Math.round(run.outputUnits())
                + (run.outputMaxUnits() != null ? " of " + run.outputMaxUnits() : "") + ChatColor.GRAY + " units, threshold "
                + ChatColor.WHITE + Math.round(run.threshold() * 100) + "%" + ChatColor.GRAY + ", carriers "
                + ChatColor.WHITE + run.carrierCapacity() + ChatColor.GRAY + ", workers " + ChatColor.WHITE
                + run.workersPresent() + "/" + run.workersTotal());
        for (String reason : run.triggerReasons()) {
            lines.add(ChatColor.GREEN + "+ " + reason);
        }
        for (String reason : run.heldReasons()) {
            lines.add(ChatColor.YELLOW + "- " + reason);
        }
        for (String warning : run.warnings()) {
            lines.add(ChatColor.RED + "! " + warning);
        }
        lines.add(run.wouldCreate() == null
                ? ChatColor.GRAY + "Would create no order now."
                : ChatColor.GREEN + "Would create an order: " + ChatColor.WHITE + run.wouldCreate());
        lines.add(ChatColor.GRAY + "Open orders: " + ChatColor.WHITE + run.openOrders());
        return lines;
    }

    private void orderAction(MenuActionContext context, Map<String, String> params, boolean cancel) {
        Player player = context.player();
        if (!staff.test(player)) {
            player.sendMessage(ChatColor.RED + "Only staff can do that.");
            return;
        }
        Integer orderId = parseId(params.get("orderId"));
        Integer userId = userId(player.getUniqueId());
        if (orderId == null) {
            player.sendMessage(ChatColor.RED + "No order picked.");
            return;
        }
        if (userId == null) {
            player.sendMessage(ChatColor.RED + "Your account isn't loaded yet; try again in a moment.");
            return;
        }
        CompletableFuture<Void> call = cancel ? api.cancelOrder(orderId, userId) : api.retryOrder(orderId, userId);
        call.whenCompleteAsync((ignored, error) -> {
            if (error != null) {
                player.sendMessage(ChatColor.RED + (cancel ? "Cancel" : "Retry") + " of transport #" + orderId + " failed: "
                        + describe(error));
            } else {
                player.sendMessage(ChatColor.GREEN + (cancel ? "Transport #" + orderId + " cancelled."
                        : "Transport #" + orderId + " retried."));
            }
            menus.keySet().removeIf(k -> k.startsWith(player.getUniqueId() + ":"));
            MenuService service = menuService.get();
            if (service != null) {
                service.refreshOpenMenu(player);
            }
        }, mainThread);
    }

    private static ConditionOutcome pending(MenuActionContext context) {
        MenuSession session = context.session();
        boolean pending = session != null && session.getPendingConfirmation()
                .map(p -> p.actionTypeId() != null && p.actionTypeId().startsWith("structures."))
                .orElse(false);
        return pending ? ConditionOutcome.allow() : ConditionOutcome.deny("Nothing to confirm.");
    }

    // ===== helpers =====

    /** The API's message for a failed call: its status and body, or the cause. */
    static String describe(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof ApiException api && api.getStatusCode() > 0) {
            String body = api.getResponseBody();
            if (api.getStatusCode() == 403) {
                return "not allowed (403)";
            }
            return api.getStatusCode() + (body == null || body.isBlank() ? "" : ": " + shorten(body));
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
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

    private static String shorten(String body) {
        int at = body.indexOf("\"message\":\"");
        if (at < 0) {
            at = body.indexOf("\"error\":\"");
        }
        if (at >= 0) {
            int start = body.indexOf(":\"", at) + 2;
            int end = body.indexOf('"', start);
            if (end > start) {
                return body.substring(start, end);
            }
        }
        return body.length() > 160 ? body.substring(0, 160) + "..." : body;
    }

    private Integer userId(UUID uuid) {
        return userCache.getStale(uuid).map(UserSummary::id).orElse(null);
    }

    static Integer parseId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int id = Integer.parseInt(value.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String key(UUID uuid, int structureId) {
        return uuid + ":" + structureId;
    }

    private static <V> Map<String, V> remembered() {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > MAX_REMEMBERED;
            }
        });
    }
}
