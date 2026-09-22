package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;

import java.util.Map;
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
 */
public final class MenuContentSourceRegistry<C> {

    private final Map<String, MenuContentSource<C>> sources = new ConcurrentHashMap<>();

    public void register(String contentSourceId, MenuContentSource<C> source) {
        sources.put(contentSourceId, source);
    }

    public boolean isRegistered(String contentSourceId) {
        return sources.containsKey(contentSourceId);
    }

    public Set<String> registeredIds() {
        return Set.copyOf(sources.keySet());
    }

    /**
     * @throws MenuActionException if {@code contentSourceId} isn't registered -
     *                              same "fail loudly, don't silently no-op"
     *                              policy as {@link ActionRegistry#execute}.
     */
    public CompletableFuture<Page<RuntimeMenuItem>> fetchPage(String contentSourceId, C context, PagedQuery query) {
        MenuContentSource<C> source = sources.get(contentSourceId);
        if (source == null) {
            return CompletableFuture.failedFuture(
                    new MenuActionException("No MenuContentSource registered for contentSourceId '" + contentSourceId + "'"));
        }
        return source.fetchPage(context, query);
    }
}
