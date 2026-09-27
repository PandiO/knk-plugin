package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryProgressRow;
import net.knightsandkings.knk.core.domain.discovery.DiscoverySummary;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.DiscoveriesApi;
import net.knightsandkings.knk.paper.menu.MenuContentSourceContext;
import net.knightsandkings.knk.paper.menu.MenuFeature;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;
import org.bukkit.entity.Player;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Domain discovery (docs/specs/domain-discovery DESIGN.md §3.7, KNG-20): {@code discoveries.main}
 * and the hub's Discoveries tile. Registers
 * <ul>
 *   <li>row source {@code discoveries.rows} → {@link DiscoveryRow}: one page of
 *       {@code POST api/users/{id}/discoveries/progress} for the viewer, with the engine's page and
 *       the template's {@code DomainType}/{@code Status} filter facets forwarded as the API's
 *       {@code domainType}/{@code status} filters. The viewer's summary is read in the same async
 *       step, so the header shows the same numbers as the rows and the latest discovery is
 *       highlighted;</li>
 *   <li>root {@code discoveries} → {@link DiscoveriesView}: the last summary read for the viewer
 *       (never I/O - providers run on the main thread). When it is missing or older than
 *       {@link #SUMMARY_TTL_MILLIS} (the hub tile, which has no row fetch) a background read
 *       starts and the next render shows it.</li>
 * </ul>
 * The viewer's knk user id comes from the plugin's user cache; with none, the grid is empty.
 * Remembers the last {@value #MAX_REMEMBERED} viewers' summaries.
 */
public final class DiscoveriesMenuFeature implements MenuFeature {

    public static final String MENU_KEY = "discoveries.main";
    public static final String ROOT = "discoveries";
    public static final String ROWS_SOURCE = "discoveries.rows";
    /** The template's filter facets, and the API filters they become. */
    public static final String TYPE_FACET = "DomainType";
    public static final String STATUS_FACET = "Status";
    static final long SUMMARY_TTL_MILLIS = 30_000L;
    static final int MAX_REMEMBERED = 256;

    private static final Logger LOGGER = Logger.getLogger(DiscoveriesMenuFeature.class.getName());

    private record CachedSummary(long fetchedAtMillis, DiscoverySummary summary) {
    }

    private final DiscoveriesApi discoveriesApi;
    private final UserCache userCache;
    private final Clock clock;
    private final Set<UUID> refreshing = ConcurrentHashMap.newKeySet();
    private final Map<UUID, CachedSummary> summaries = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, CachedSummary> eldest) {
                    return size() > MAX_REMEMBERED;
                }
            });

    public DiscoveriesMenuFeature(DiscoveriesApi discoveriesApi, UserCache userCache, Clock clock) {
        this.discoveriesApi = discoveriesApi;
        this.userCache = userCache;
        this.clock = clock;
    }

    @Override
    public void registerMenuHandlers(MenuFeatureRegistries registries) {
        registries.variables().register(ROOT, DiscoveriesView.class, (player, ctx) -> viewFor(player));
        registries.contentSources().registerRows(ROWS_SOURCE, DiscoveryRow.class,
                (context, params, query) -> fetchRows(context, query));
    }

    // ===== root =====

    /** Main thread, no I/O: the last summary read, starting a background read when it is missing or stale. */
    DiscoveriesView viewFor(Player player) {
        if (player == null) {
            return DiscoveriesView.unavailable();
        }
        UUID uuid = player.getUniqueId();
        CachedSummary cached = summaries.get(uuid);
        if (cached == null || clock.millis() - cached.fetchedAtMillis() >= SUMMARY_TTL_MILLIS) {
            refreshSummary(uuid);
        }
        return cached != null ? new DiscoveriesView(cached.summary()) : DiscoveriesView.unavailable();
    }

    private void refreshSummary(UUID uuid) {
        Integer userId = userId(uuid);
        if (userId == null || !refreshing.add(uuid)) {
            return;
        }
        try {
            readSummary(uuid, userId).whenComplete((summary, ex) -> refreshing.remove(uuid));
        } catch (RuntimeException e) {
            refreshing.remove(uuid);
            LOGGER.log(Level.FINE, "discoveries: couldn't read the summary of user " + userId, e);
        }
    }

    /** Reads and remembers the viewer's summary; completes with null (keeping the previous one) on failure. */
    private CompletableFuture<DiscoverySummary> readSummary(UUID uuid, int userId) {
        return discoveriesApi.summary(userId)
                .thenApply(summary -> {
                    if (summary != null) {
                        summaries.put(uuid, new CachedSummary(clock.millis(), summary));
                    }
                    return summary;
                })
                .exceptionally(ex -> {
                    LOGGER.log(Level.FINE, "discoveries: couldn't read the summary of user " + userId, ex);
                    return null;
                });
    }

    /** Drops the viewer's remembered summary (e.g. after a staff reset) so the next render reads it again. */
    public void invalidate(UUID uuid) {
        summaries.remove(uuid);
    }

    // ===== rows =====

    CompletableFuture<Page<DiscoveryRow>> fetchRows(MenuContentSourceContext context, PagedQuery query) {
        Player player = context.player();
        PagedQuery apiQuery = toApiQuery(query);
        Integer userId = player != null ? userId(player.getUniqueId()) : null;
        if (userId == null) {
            return CompletableFuture.completedFuture(emptyPage(apiQuery));
        }
        UUID uuid = player.getUniqueId();
        CompletableFuture<Page<DiscoveryProgressRow>> progress;
        try {
            progress = discoveriesApi.progress(userId, apiQuery);
        } catch (RuntimeException e) {
            progress = CompletableFuture.failedFuture(e);
        }
        return progress
                .thenCombine(readSummary(uuid, userId), (page, fresh) -> {
                    CachedSummary remembered = summaries.get(uuid);
                    DiscoverySummary summary = fresh != null ? fresh : remembered != null ? remembered.summary() : null;
                    Integer latestId = summary != null && summary.latest() != null ? summary.latest().domainId() : null;
                    List<DiscoveryProgressRow> items = page != null && page.items() != null ? page.items() : List.of();
                    List<DiscoveryRow> rows = new ArrayList<>(items.size());
                    for (DiscoveryProgressRow item : items) {
                        rows.add(DiscoveryRow.of(item, latestId));
                    }
                    int total = page != null ? page.totalCount() : rows.size();
                    return new Page<>(rows, total, apiQuery.pageNumber(), apiQuery.pageSize());
                })
                .exceptionally(ex -> {
                    LOGGER.log(Level.WARNING, "discoveries.rows: failed to load the discoveries of user " + userId, ex);
                    return emptyPage(apiQuery);
                });
    }

    /**
     * The engine's query as the progress endpoint takes it: page and search as they are, facet
     * {@code DomainType} → filter {@code domainType}, {@code Status} → {@code status} (lower case;
     * the API matches both case-insensitively), anything else unchanged. Default sort
     * (Town, District, Structure, then name).
     */
    static PagedQuery toApiQuery(PagedQuery query) {
        if (query == null) {
            return new PagedQuery(1, 27, null, null, false, Map.of());
        }
        Map<String, String> filters = new LinkedHashMap<>();
        if (query.filters() != null) {
            query.filters().forEach((facet, value) -> {
                if (value == null || value.isBlank()) {
                    return;
                }
                if (TYPE_FACET.equalsIgnoreCase(facet)) {
                    filters.put("domainType", value);
                } else if (STATUS_FACET.equalsIgnoreCase(facet)) {
                    filters.put("status", value.toLowerCase(Locale.ROOT));
                } else {
                    filters.put(facet, value);
                }
            });
        }
        return new PagedQuery(Math.max(1, query.pageNumber()), Math.max(1, query.pageSize()), query.searchTerm(),
                null, false, filters);
    }

    private Integer userId(UUID uuid) {
        return userCache.getStale(uuid).map(UserSummary::id).orElse(null);
    }

    private static <T> Page<T> emptyPage(PagedQuery query) {
        return new Page<>(List.of(), 0, query.pageNumber(), query.pageSize());
    }
}
