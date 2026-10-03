package net.knightsandkings.knk.paper.menu.content;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.statistics.PlayerStatistics;
import net.knightsandkings.knk.core.domain.statistics.TitleChange;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.ports.api.StatisticsApi;
import net.knightsandkings.knk.core.statistics.StatisticsLines;
import net.knightsandkings.knk.paper.menu.MenuActionContext;
import net.knightsandkings.knk.paper.menu.MenuContentSourceContext;
import net.knightsandkings.knk.paper.menu.MenuFeature;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;

/**
 * Player statistics (KNG-34, IMPLEMENTATION_PLAN.md §5.3): {@code statistics.main}, the viewer's own
 * statistics or another player's ({@code ctx.target} = user id, {@code ctx.name} = username; opened
 * from the profile's "Statistics" tile and from leaderboard entries). Registers
 * <ul>
 *   <li>row source {@code statistics.main.title-history} → {@link TitleHistoryRow}: the target's title
 *   history. Each fetch also reads {@code GET api/statistics/users/{id}?period=} acting as the viewer
 *   - it runs before bindings resolve, so the root below shows the same read;</li>
 *   <li>root {@code stats} → {@link StatisticsMenuView}: that read grouped like the API catalogue
 *   (never I/O; "Loading" until the read arrives);</li>
 *   <li>action {@code statistics.main.period}: lifetime → day → week → month.</li>
 * </ul>
 * The API decides what the viewer may see (the target's visibility settings; self and staff see
 * everything) - the menu renders what it gets. Reads are reused for 5 s per viewer.
 */
public final class StatisticsMenuFeature implements MenuFeature, Listener {

    public static final String MENU_KEY = "statistics.main";
    public static final String ROOT = "stats";
    public static final String HISTORY_SOURCE = "statistics.main.title-history";
    public static final String PERIOD_ACTION = "statistics.main.period";
    public static final String CTX_TARGET = "target";
    public static final String CTX_NAME = "name";

    static final long FRESH_MILLIS = 5_000L;
    static final int HISTORY_SIZE = 27;

    private static final Logger LOGGER = Logger.getLogger(StatisticsMenuFeature.class.getName());

    /** One read for (target, period): statistics (null = failed) and the title history (hidden on 403). */
    record Loaded(int targetId, String period, long fetchedAtMillis, PlayerStatistics statistics, List<TitleChange> history,
                  boolean historyHidden) {
    }

    private final StatisticsApi api;
    private final UserCache userCache;
    private final Clock clock;
    private final Map<UUID, String> periods = new ConcurrentHashMap<>();
    private final Map<UUID, Loaded> loaded = new ConcurrentHashMap<>();

    public StatisticsMenuFeature(StatisticsApi api, UserCache userCache, Clock clock) {
        this.api = api;
        this.userCache = userCache;
        this.clock = clock;
    }

    @Override
    public void registerMenuHandlers(MenuFeatureRegistries registries) {
        registries.variables().register(ROOT, StatisticsMenuView.class, this::viewFor);
        registries.contentSources().registerRows(HISTORY_SOURCE, TitleHistoryRow.class,
                (context, params, query) -> fetchHistory(context, query));
        registries.actions().register(PERIOD_ACTION, this::cyclePeriod);
    }

    // ===== root =====

    StatisticsMenuView viewFor(Player player, MenuContextParams ctx) {
        if (player == null) {
            return StatisticsMenuView.loading(null, false, "lifetime");
        }
        UUID uuid = player.getUniqueId();
        Integer viewerId = userId(uuid);
        Integer targetId = target(ctx, viewerId);
        boolean own = targetId != null && targetId.equals(viewerId);
        String name = own ? player.getName() : ctx == null ? null : ctx.get(CTX_NAME);
        String period = period(uuid);
        Loaded read = loaded.get(uuid);
        if (targetId == null || read == null || read.targetId() != targetId || !read.period().equals(period)) {
            return StatisticsMenuView.loading(name, own, period);
        }
        return new StatisticsMenuView(name, own, period, read.statistics(), read.historyHidden(), true);
    }

    // ===== rows =====

    CompletableFuture<Page<TitleHistoryRow>> fetchHistory(MenuContentSourceContext context, PagedQuery query) {
        PagedQuery page = query == null ? new PagedQuery(1, HISTORY_SIZE, null, null, false, Map.of()) : query;
        Player player = context.player();
        Integer viewerId = player == null ? null : userId(player.getUniqueId());
        Integer targetId = target(context.menuContext(), viewerId);
        if (viewerId == null || targetId == null) {
            return CompletableFuture.completedFuture(empty(page));
        }
        return load(player.getUniqueId(), viewerId, targetId, period(player.getUniqueId()))
                .thenApply(read -> page(read.history(), page));
    }

    /** The remembered read while fresh, else statistics + title history (failures become an empty read). */
    CompletableFuture<Loaded> load(UUID viewer, int viewerId, int targetId, String period) {
        Loaded cached = loaded.get(viewer);
        if (cached != null && cached.targetId() == targetId && cached.period().equals(period)
                && clock.millis() - cached.fetchedAtMillis() < FRESH_MILLIS) {
            return CompletableFuture.completedFuture(cached);
        }
        CompletableFuture<PlayerStatistics> statistics = call(() -> api.getUserStatistics(targetId, viewerId, period, null))
                .exceptionally(ex -> {
                    LOGGER.log(Level.WARNING, MENU_KEY + ": failed to load the statistics of user " + targetId, ex);
                    return null;
                });
        CompletableFuture<Page<TitleChange>> history = call(() -> api.getTitleHistory(targetId, viewerId, 1, HISTORY_SIZE))
                .exceptionally(ex -> {
                    if (status(ex) != 403) {
                        LOGGER.log(Level.FINE, MENU_KEY + ": failed to load the title history of user " + targetId, ex);
                    }
                    return null;
                });
        return statistics.thenCombine(history, (stats, changes) -> {
            Loaded read = new Loaded(targetId, period, clock.millis(), stats,
                    changes == null ? List.of() : changes.items(), changes == null);
            loaded.put(viewer, read);
            return read;
        });
    }

    private static <T> CompletableFuture<T> call(java.util.function.Supplier<CompletableFuture<T>> call) {
        try {
            CompletableFuture<T> future = call.get();
            return future != null ? future : CompletableFuture.failedFuture(new IllegalStateException("no result"));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static Page<TitleHistoryRow> page(List<TitleChange> changes, PagedQuery query) {
        List<TitleHistoryRow> rows = changes.stream().map(TitleHistoryRow::new).toList();
        int size = Math.max(1, query.pageSize());
        int number = Math.max(1, query.pageNumber());
        int from = Math.min(rows.size(), (number - 1) * size);
        int to = Math.min(rows.size(), from + size);
        return new Page<>(List.copyOf(rows.subList(from, to)), rows.size(), number, size);
    }

    private static Page<TitleHistoryRow> empty(PagedQuery query) {
        return new Page<>(List.of(), 0, query.pageNumber(), query.pageSize());
    }

    // ===== actions =====

    private void cyclePeriod(MenuActionContext context, Map<String, String> params) {
        UUID uuid = context.player().getUniqueId();
        String requested = params == null ? null : params.get("period");
        String next = requested != null && StatisticsLines.PERIODS.contains(requested.toLowerCase(Locale.ROOT))
                ? requested.toLowerCase(Locale.ROOT)
                : StatisticsLines.nextPeriod(period(uuid));
        periods.put(uuid, next);
        if (context.menuService() != null) {
            context.menuService().refreshOpenMenu(context.player());
        }
    }

    // ===== state =====

    String period(UUID uuid) {
        return periods.getOrDefault(uuid, "lifetime");
    }

    /** {@code ctx.target} when it's a user id, else the viewer. */
    static Integer target(MenuContextParams ctx, Integer viewerId) {
        if (ctx != null) {
            String raw = ctx.get(CTX_TARGET);
            if (raw != null && !raw.isBlank()) {
                try {
                    int id = Integer.parseInt(raw.trim());
                    if (id > 0) {
                        return id;
                    }
                } catch (NumberFormatException ignored) {
                    // fall back to the viewer
                }
            }
        }
        return viewerId;
    }

    /** The HTTP status of the {@link ApiException} in {@code error}'s cause chain, or -1. */
    public static int status(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ApiException api) {
                return api.getStatusCode();
            }
        }
        return -1;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    public void forget(UUID uuid) {
        periods.remove(uuid);
        loaded.remove(uuid);
    }

    private Integer userId(UUID uuid) {
        return userCache.getStale(uuid).map(UserSummary::id).orElse(null);
    }
}
