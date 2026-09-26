package net.knightsandkings.knk.core.domain.currency;

import java.util.List;

/** A page of ledger lines, newest first. */
public record LedgerPage(List<LedgerLine> items, int totalCount, int page, int pageSize) {

    public LedgerPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public int totalPages() {
        return pageSize <= 0 ? 1 : Math.max(1, (totalCount + pageSize - 1) / pageSize);
    }
}
