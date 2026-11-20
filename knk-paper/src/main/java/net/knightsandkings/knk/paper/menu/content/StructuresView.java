package net.knightsandkings.knk.paper.menu.content;

import java.util.Map;

import net.knightsandkings.knk.core.production.menu.StructureFacets;

/**
 * Root {@code structures} of the overview (M2): the facet values the Town and Kind buttons cycle through and the current
 * filter lines (the filters the viewer's last row fetch used).
 */
public record StructuresView(StructureFacets facets, Map<String, String> filters) {

    public String getTownValues() {
        return facets.townValues();
    }

    public String getKindValues() {
        return facets.kindValues();
    }

    public String getTownFilterLine() {
        return StructureFacets.filterLine(StructureFacets.TOWN, filters.get(StructureFacets.TOWN));
    }

    public String getKindFilterLine() {
        return StructureFacets.filterLine(StructureFacets.KIND, filters.get(StructureFacets.KIND));
    }

    public String getStatusFilterLine() {
        return StructureFacets.filterLine(StructureFacets.STATUS, filters.get(StructureFacets.STATUS));
    }
}
