package net.knightsandkings.knk.core.privacy;

import java.util.List;

/**
 * One page of {@code GET api/privacy/erasures?since={id}} (PandiO/knk-workspace#184).
 *
 * @param items    erased accounts after {@code since}, oldest first
 * @param lastId   the highest erasure id in {@code items}, or {@code since} when empty
 * @param hasMore  more rows follow from {@code lastId}
 * @param latestId the newest erasure id the API has (0 when none); below the caller's {@code since} means the position
 *                 came from another database (a reset)
 */
public record ErasureFeedPage(List<ErasedAccount> items, long lastId, boolean hasMore, long latestId) {
    public ErasureFeedPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
