package net.knightsandkings.knk.core.menu;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IMPLEMENTATION_PLAN.md Phase 5 / DESIGN_REVIEW.md §2.1 §2.3: the "one
 * shared active content predicate" state a searchable content section
 * carries in {@link MenuSession} - free-text search plus structured filter
 * facet values, composed together rather than as two independent mechanisms
 * each content section has to know how to combine (search narrows by
 * {@code searchText} alone; a FilterBar narrows by one or more
 * {@code filterValues} entries; both apply together, AND'd, when both are
 * set).
 * <p>
 * Holds only raw query state - not a compiled {@code Predicate} - because
 * actually matching an item requires its <em>resolved</em> display text
 * ({@code VariableResolver}, which needs a live {@code MenuSession}/
 * {@code Player} context that only exists in knk-paper). This record stays
 * Bukkit-free and lives on the session; knk-paper's {@code MenuRenderer} is
 * where the real predicate gets built and applied to
 * {@link RuntimeMenuSection#resolveSlots(int, int, java.util.function.Predicate)}.
 * <p>
 * A filter facet's key is a {@code VariableBinding.targetProperty} value
 * (e.g. {@code "Category"}) rather than a fixed enum - DESIGN_REVIEW.md §2.3
 * describes filtering on real structured item fields (Category/Grade/Tag)
 * that don't exist as columns on {@code MenuItemTemplate} today and aren't
 * this phase's to add (see ACTIVE_SESSIONS.md's Phase 5 entry, open question
 * 4); reusing the already-generic, already-persisted {@code targetProperty}
 * binding mechanism gives real faceted filtering now without inventing
 * schema, and is ready to re-point at real catalog fields once Phase 8 lands
 * them.
 */
public record MenuContentQuery(String searchText, Map<String, String> filterValues) {

    public static final MenuContentQuery EMPTY = new MenuContentQuery(null, Map.of());

    public MenuContentQuery {
        filterValues = filterValues == null ? Map.of() : Map.copyOf(filterValues);
    }

    public boolean isEmpty() {
        return (searchText == null || searchText.isBlank()) && filterValues.isEmpty();
    }

    /** Returns a copy with the search text replaced; blank/null clears it. */
    public MenuContentQuery withSearchText(String newSearchText) {
        return new MenuContentQuery(newSearchText, filterValues);
    }

    /** Returns a copy with one filter facet set; a blank/null value clears that facet. */
    public MenuContentQuery withFilterValue(String facetKey, String value) {
        Map<String, String> updated = new LinkedHashMap<>(filterValues);
        if (value == null || value.isBlank()) {
            updated.remove(facetKey);
        } else {
            updated.put(facetKey, value);
        }
        return new MenuContentQuery(searchText, updated);
    }
}
