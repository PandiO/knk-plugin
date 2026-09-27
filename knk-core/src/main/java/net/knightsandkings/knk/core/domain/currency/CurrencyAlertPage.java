package net.knightsandkings.knk.core.domain.currency;

import java.util.List;

/** A page of currency alerts, newest first, and how many are still open in total. */
public record CurrencyAlertPage(List<CurrencyAlert> items, int totalCount, int page, int pageSize, int openCount) {

    public CurrencyAlertPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public int totalPages() {
        return pageSize <= 0 ? 1 : Math.max(1, (totalCount + pageSize - 1) / pageSize);
    }
}
