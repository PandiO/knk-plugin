package net.knightsandkings.knk.core.production.menu;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The overview's Town and Kind filter values (M2): the facet buttons cycle through names, the API filters by id. Built from
 * the rows of an unfiltered search. A value that is a number is taken as an id as it is, so a town that is not in the
 * list yet (the preset from the territory lookup) still filters.
 */
public final class StructureFacets {

    public static final String TOWN = "Town";
    public static final String KIND = "Kind";
    public static final String STATUS = "Status";

    public static final StructureFacets EMPTY = new StructureFacets(Map.of(), Map.of());

    private final Map<String, Integer> towns;
    private final Map<String, Integer> kinds;

    private StructureFacets(Map<String, Integer> towns, Map<String, Integer> kinds) {
        this.towns = towns;
        this.kinds = kinds;
    }

    /** Town and kind names (commas removed, they separate the cycle values) to ids, sorted by name; warehouses have no kind. */
    public static StructureFacets of(Collection<StructureRow> rows) {
        Map<String, Integer> towns = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Integer> kinds = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (StructureRow row : rows) {
            if (row.townId() != null && row.townName() != null && !clean(row.townName()).isEmpty()) {
                towns.putIfAbsent(clean(row.townName()), row.townId());
            }
            if (row.productionKindId() != null && row.kindName() != null && !clean(row.kindName()).isEmpty()) {
                kinds.putIfAbsent(clean(row.kindName()), row.productionKindId());
            }
        }
        return new StructureFacets(new LinkedHashMap<>(towns), new LinkedHashMap<>(kinds));
    }

    /** "Phoenix,Rivertown" for the Town facet button. */
    public String townValues() {
        return String.join(",", towns.keySet());
    }

    public String kindValues() {
        return String.join(",", kinds.keySet());
    }

    /** The town's name in the list, else its id as text (the preset still filters by it). */
    public String townValue(int townId) {
        for (Map.Entry<String, Integer> e : towns.entrySet()) {
            if (e.getValue() == townId) {
                return e.getKey();
            }
        }
        return String.valueOf(townId);
    }

    /**
     * The engine's facet values as the API's filters: Town → {@code townId}, Kind → {@code productionKindId}, Status →
     * {@code status}; anything else unchanged. An unknown name filters to nothing ({@code -1}).
     */
    public Map<String, String> toApiFilters(Map<String, String> facets) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (facets == null) {
            return filters;
        }
        facets.forEach((facet, value) -> {
            if (value == null || value.isBlank()) {
                return;
            }
            if (TOWN.equalsIgnoreCase(facet)) {
                filters.put("townId", String.valueOf(id(towns, value)));
            } else if (KIND.equalsIgnoreCase(facet)) {
                filters.put("productionKindId", String.valueOf(id(kinds, value)));
            } else if (STATUS.equalsIgnoreCase(facet)) {
                filters.put("status", value.trim());
            } else {
                filters.put(facet, value);
            }
        });
        return filters;
    }

    /** "&7Town: &fPhoenix", "&7Town: &fall". */
    public static String filterLine(String facet, String value) {
        return "&7" + facet + ": &f" + (value == null || value.isBlank() ? "all" : value);
    }

    public List<String> townNames() {
        return new ArrayList<>(towns.keySet());
    }

    private static int id(Map<String, Integer> byName, String value) {
        String trimmed = value.trim();
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            Integer id = byName.get(clean(trimmed));
            if (id == null) {
                for (Map.Entry<String, Integer> entry : byName.entrySet()) {
                    if (entry.getKey().toLowerCase(Locale.ROOT).equals(trimmed.toLowerCase(Locale.ROOT))) {
                        return entry.getValue();
                    }
                }
            }
            return id == null ? -1 : id;
        }
    }

    static String clean(String name) {
        return name.replace(",", "").trim();
    }
}
