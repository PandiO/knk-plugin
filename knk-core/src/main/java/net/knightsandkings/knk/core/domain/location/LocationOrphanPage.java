package net.knightsandkings.knk.core.domain.location;

import java.util.List;

/** A page of orphaned Locations plus how many are open (KNG-80). */
public record LocationOrphanPage(List<LocationOrphanEntry> items, int totalCount, int pageNumber, int pageSize, int openCount) {
    public LocationOrphanPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public int totalPages() {
        return Math.max(1, (int) Math.ceil(totalCount / (double) Math.max(1, pageSize)));
    }
}
