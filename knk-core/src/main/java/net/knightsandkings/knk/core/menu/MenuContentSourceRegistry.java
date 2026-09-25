package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code contentSourceId} -&gt; {@link MenuContentSource} (IMPLEMENTATION_PLAN.md
 * Phase 8's "Code-side registries" addition, alongside {@link ActionRegistry}/
 * {@link ConditionRegistry}). Same String-keyed map / "unregistered id fails
 * loudly" shape as those two registries, generic over the same execution
 * context type {@code C} for the same reason (a real content source needs
 * live access to the backing data-access gateway, which only knk-paper
 * wires up).
 * <p>
 * <b>InventoryMenu Phase 9 (E3)</b> adds a second kind of source:
 * {@link #registerRows} - a {@link MenuRowSource} returning plain row objects
 * of a declared type, rendered through the section's row template. Java-mapped
 * item sources registered with {@link #register} (e.g.
 * {@code catalog.itemblueprints}) keep working unchanged. {@link #fetch} is the
 * one entry point the renderer uses for both kinds. Like every menu registry,
 * this one {@link #lock()}s when startup validation runs.
 */
public final class MenuContentSourceRegistry<C> {

    /**
     * One fetched page, whichever kind of source produced it: {@code rows} is
     * true when {@code page}'s elements are row objects for a row template,
     * false when they are ready-made {@link RuntimeMenuItem}s.
     */
    public record MenuContentPage(Page<?> page, boolean rows) {
    }

    private record RowRegistration<C>(Class<?> rowType, MenuRowSource<C, ?> source) {
    }

    private final Map<String, MenuContentSource<C>> sources = new ConcurrentHashMap<>();
    private final Map<String, RowRegistration<C>> rowSources = new ConcurrentHashMap<>();
    private volatile boolean locked;

    public void register(String contentSourceId, MenuContentSource<C> source) {
        checkUnlocked(contentSourceId);
        rowSources.remove(contentSourceId);
        sources.put(contentSourceId, source);
    }

    /**
     * InventoryMenu Phase 9 (E3): registers a source yielding rows of
     * {@code rowType}. The declared type is what {@code $row.*$} chains on the
     * section's row template are validated against at enable.
     */
    public <R> void registerRows(String contentSourceId, Class<R> rowType, MenuRowSource<C, R> source) {
        Objects.requireNonNull(rowType, "rowType must not be null");
        Objects.requireNonNull(source, "source must not be null");
        checkUnlocked(contentSourceId);
        sources.remove(contentSourceId);
        rowSources.put(contentSourceId, new RowRegistration<>(rowType, source));
    }

    public boolean isRegistered(String contentSourceId) {
        return sources.containsKey(contentSourceId) || rowSources.containsKey(contentSourceId);
    }

    public Set<String> registeredIds() {
        Set<String> ids = new java.util.HashSet<>(sources.keySet());
        ids.addAll(rowSources.keySet());
        return Set.copyOf(ids);
    }

    /** The declared row type of a row source, or null for an item source / unknown id. */
    public Class<?> rowType(String contentSourceId) {
        RowRegistration<C> registration = rowSources.get(contentSourceId);
        return registration != null ? registration.rowType() : null;
    }

    /** {@code contentSourceId → declared row type} for every row source - fed to {@link MenuDefinitionValidator}. */
    public Map<String, Class<?>> rowTypes() {
        Map<String, Class<?>> types = new LinkedHashMap<>();
        rowSources.forEach((id, registration) -> types.put(id, registration.rowType()));
        return Map.copyOf(types);
    }

    /** Called once by the validation runner; later registrations throw. */
    public void lock() {
        locked = true;
    }

    /**
     * Legacy (Phase 8) entry point for item sources only.
     *
     * @throws MenuActionException (as a failed future) if {@code contentSourceId}
     *                              isn't a registered item source - same "fail
     *                              loudly, don't silently no-op" policy as
     *                              {@link ActionRegistry#execute}.
     */
    public CompletableFuture<Page<RuntimeMenuItem>> fetchPage(String contentSourceId, C context, PagedQuery query) {
        MenuContentSource<C> source = sources.get(contentSourceId);
        if (source == null) {
            return CompletableFuture.failedFuture(
                    new MenuActionException("No MenuContentSource registered for contentSourceId '" + contentSourceId + "'"));
        }
        return source.fetchPage(context, query);
    }

    /**
     * InventoryMenu Phase 9: fetches one page from either kind of source.
     * {@code params} (the section's interpolated {@code ContentSourceParamsJson})
     * are passed to row sources; item sources keep their Phase 8 signature.
     * A row source that ignores paging and returns more rows than
     * {@code query.pageSize()} is sliced here to the requested page, so an
     * in-memory source can simply return everything.
     */
    public CompletableFuture<MenuContentPage> fetch(String contentSourceId, C context, Map<String, String> params,
                                                    PagedQuery query) {
        RowRegistration<C> rowRegistration = rowSources.get(contentSourceId);
        if (rowRegistration != null) {
            CompletableFuture<? extends Page<?>> rows;
            try {
                rows = rowRegistration.source().fetchRows(context, params != null ? params : Map.of(), query);
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
            return rows.thenApply(page -> new MenuContentPage(sliceIfUnpaged(page, query), true));
        }
        return fetchPage(contentSourceId, context, query).thenApply(page -> new MenuContentPage(page, false));
    }

    private static Page<?> sliceIfUnpaged(Page<?> page, PagedQuery query) {
        if (page == null) {
            return new Page<>(List.of(), 0, query.pageNumber(), query.pageSize());
        }
        List<?> items = page.items() != null ? page.items() : List.of();
        int pageSize = query.pageSize();
        if (pageSize <= 0 || items.size() <= pageSize) {
            return page;
        }
        int total = items.size();
        int from = Math.min(Math.max(0, (query.pageNumber() - 1) * pageSize), total);
        int to = Math.min(from + pageSize, total);
        return new Page<>(List.copyOf(items.subList(from, to)), total, query.pageNumber(), pageSize);
    }

    private void checkUnlocked(String contentSourceId) {
        if (locked) {
            throw new IllegalStateException("MenuContentSource '" + contentSourceId + "' registered after menu validation ran"
                    + " - register it in a MenuFeature before MenuDefinitionValidationRunner (IMPLEMENTATION_PLAN.md Phase 9, E2)");
        }
    }
}
