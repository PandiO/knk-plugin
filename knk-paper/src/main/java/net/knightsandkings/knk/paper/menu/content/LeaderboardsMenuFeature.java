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
import net.knightsandkings.knk.core.domain.leaderboards.LeaderboardBoard;
import net.knightsandkings.knk.core.domain.leaderboards.LeaderboardView;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.ports.api.LeaderboardsApi;
import net.knightsandkings.knk.paper.menu.MenuActionContext;
import net.knightsandkings.knk.paper.menu.MenuContentSourceContext;
import net.knightsandkings.knk.paper.menu.MenuFeature;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;

/**
 * Leaderboards (KNG-34, IMPLEMENTATION_PLAN.md §5.3, DESIGN.md §F.11): {@code statistics.leaderboards}
 * (every board; a click opens it) and {@code statistics.leaderboard} (one board, {@code ctx.board}:
 * top 10 plus the viewer's own position, period cycle). Registers
 * <ul>
 *   <li>row source {@code statistics.leaderboards.boards} → {@link LeaderboardBoardRow} ({@code GET
 *   api/leaderboards}, kept once loaded);</li>
 *   <li>row source {@code statistics.leaderboard.entries} → {@link LeaderboardEntryRow} ({@code GET
 *   api/leaderboards/{board}?period=&top=10} acting as the viewer; a click opens that player's
 *   {@code statistics.main});</li>
 *   <li>root {@code lb} → {@link LeaderboardMenuView} (board, period, the viewer's rank; never I/O);</li>
 *   <li>action {@code statistics.leaderboard.period}: weekly → monthly → lifetime.</li>
 * </ul>
 * Reads come from the API's snapshots (refreshed every few minutes); a board read is reused for 5 s.
 */
public final class LeaderboardsMenuFeature implements MenuFeature, Listener {

    public static final String LIST_MENU_KEY = "statistics.leaderboards";
    public static final String BOARD_MENU_KEY = "statistics.leaderboard";
    public static final String ROOT = "lb";
    public static final String BOARDS_SOURCE = "statistics.leaderboards.boards";
    public static final String ENTRIES_SOURCE = "statistics.leaderboard.entries";
    public static final String PERIOD_ACTION = "statistics.leaderboard.period";
    public static final String CTX_BOARD = "board";
    public static final List<String> PERIODS = List.of("weekly", "monthly", "lifetime");
    public static final int TOP = 10;

    static final long FRESH_MILLIS = 5_000L;

    private static final Logger LOGGER = Logger.getLogger(LeaderboardsMenuFeature.class.getName());

    record Loaded(String boardKey, String period, long fetchedAtMillis, LeaderboardView view) {
    }

    private final LeaderboardsApi api;
    private final UserCache userCache;
    private final Clock clock;
    private volatile List<LeaderboardBoard> boards = List.of();
    private final Map<UUID, String> periods = new ConcurrentHashMap<>();
    private final Map<UUID, Loaded> loaded = new ConcurrentHashMap<>();

    public LeaderboardsMenuFeature(LeaderboardsApi api, UserCache userCache, Clock clock) {
        this.api = api;
        this.userCache = userCache;
        this.clock = clock;
    }

    @Override
    public void registerMenuHandlers(MenuFeatureRegistries registries) {
        registries.variables().register(ROOT, LeaderboardMenuView.class, this::viewFor);
        registries.contentSources().registerRows(BOARDS_SOURCE, LeaderboardBoardRow.class,
                (context, params, query) -> fetchBoards(query));
        registries.contentSources().registerRows(ENTRIES_SOURCE, LeaderboardEntryRow.class,
                (context, params, query) -> fetchEntries(context, query));
        registries.actions().register(PERIOD_ACTION, this::cyclePeriod);
    }

    // ===== boards =====

    /** The board list, loaded once (again after a failure). */
    public CompletableFuture<List<LeaderboardBoard>> boards() {
        List<LeaderboardBoard> known = boards;
        if (!known.isEmpty()) {
            return CompletableFuture.completedFuture(known);
        }
        CompletableFuture<List<LeaderboardBoard>> read;
        try {
            read = api.listBoards();
        } catch (RuntimeException e) {
            read = CompletableFuture.failedFuture(e);
        }
        return read.thenApply(list -> {
            boards = list == null ? List.of() : List.copyOf(list);
            return boards;
        });
    }

    /** The cached board list (possibly empty) - for tab completion. */
    public List<LeaderboardBoard> cachedBoards() {
        return boards;
    }

    CompletableFuture<Page<LeaderboardBoardRow>> fetchBoards(PagedQuery query) {
        PagedQuery page = query == null ? new PagedQuery(1, 27, null, null, false, Map.of()) : query;
        return boards()
                .thenApply(list -> page(list.stream().map(LeaderboardBoardRow::new).toList(), page))
                .exceptionally(ex -> {
                    LOGGER.log(Level.WARNING, BOARDS_SOURCE + ": failed to load the leaderboards", ex);
                    return page(List.of(), page);
                });
    }

    // ===== one board =====

    CompletableFuture<Page<LeaderboardEntryRow>> fetchEntries(MenuContentSourceContext context, PagedQuery query) {
        PagedQuery page = query == null ? new PagedQuery(1, TOP, null, null, false, Map.of()) : query;
        Player player = context.player();
        String boardKey = boardKey(context.menuContext());
        Integer viewerId = player == null ? null : userId(player.getUniqueId());
        if (player == null || boardKey == null || viewerId == null) {
            return CompletableFuture.completedFuture(page(List.of(), page));
        }
        return load(player.getUniqueId(), viewerId, boardKey, period(player.getUniqueId()))
                .thenApply(read -> page(read.view() == null ? List.of()
                        : read.view().entries().stream().map(e -> new LeaderboardEntryRow(e, read.view().unit(),
                                boardMetric(boardKey), e.userId() == viewerId)).toList(), page));
    }

    /** The remembered read while fresh, else a new one (a failure is remembered as a null view). */
    CompletableFuture<Loaded> load(UUID viewer, int viewerId, String boardKey, String period) {
        Loaded cached = loaded.get(viewer);
        if (cached != null && cached.boardKey().equals(boardKey) && cached.period().equals(period)
                && clock.millis() - cached.fetchedAtMillis() < FRESH_MILLIS) {
            return CompletableFuture.completedFuture(cached);
        }
        CompletableFuture<LeaderboardView> read;
        try {
            read = api.getBoard(boardKey, period, TOP, viewerId);
        } catch (RuntimeException e) {
            read = CompletableFuture.failedFuture(e);
        }
        return read.exceptionally(ex -> {
            LOGGER.log(Level.WARNING, ENTRIES_SOURCE + ": failed to load leaderboard " + boardKey, ex);
            return null;
        }).thenApply(view -> {
            Loaded result = new Loaded(boardKey, period, clock.millis(), view);
            loaded.put(viewer, result);
            return result;
        });
    }

    LeaderboardMenuView viewFor(Player player, MenuContextParams ctx) {
        String boardKey = boardKey(ctx);
        String label = boards.stream().filter(b -> b.boardKey().equals(boardKey)).map(LeaderboardBoard::label).findFirst()
                .orElse(boardKey == null ? "Leaderboard" : boardKey);
        if (player == null) {
            return new LeaderboardMenuView(label, "weekly", null, false, clock.instant());
        }
        UUID uuid = player.getUniqueId();
        String period = period(uuid);
        Loaded read = loaded.get(uuid);
        boolean current = read != null && read.boardKey().equals(boardKey) && read.period().equals(period);
        return new LeaderboardMenuView(label, period, current ? read.view() : null, current, clock.instant());
    }

    private void cyclePeriod(MenuActionContext context, Map<String, String> params) {
        UUID uuid = context.player().getUniqueId();
        String requested = params == null ? null : params.get("period");
        String next = requested != null && PERIODS.contains(requested.toLowerCase(Locale.ROOT))
                ? requested.toLowerCase(Locale.ROOT)
                : nextPeriod(period(uuid));
        periods.put(uuid, next);
        if (context.menuService() != null) {
            context.menuService().refreshOpenMenu(context.player());
        }
    }

    // ===== helpers =====

    static String nextPeriod(String period) {
        return PERIODS.get((PERIODS.indexOf(period) + 1) % PERIODS.size());
    }

    String period(UUID uuid) {
        return periods.getOrDefault(uuid, "weekly");
    }

    private String boardMetric(String boardKey) {
        int at = boardKey.indexOf('@');
        return at < 0 ? boardKey : boardKey.substring(0, at);
    }

    static String boardKey(MenuContextParams ctx) {
        String raw = ctx == null ? null : ctx.get(CTX_BOARD);
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private static <T> Page<T> page(List<T> rows, PagedQuery query) {
        int size = Math.max(1, query.pageSize());
        int number = Math.max(1, query.pageNumber());
        int from = Math.min(rows.size(), (number - 1) * size);
        int to = Math.min(rows.size(), from + size);
        return new Page<>(List.copyOf(rows.subList(from, to)), rows.size(), number, size);
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
