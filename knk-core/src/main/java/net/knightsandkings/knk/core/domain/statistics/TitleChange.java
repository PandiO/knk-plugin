package net.knightsandkings.knk.core.domain.statistics;

import java.time.Instant;

/**
 * One entry of a player's title history ({@code GET api/statistics/users/{id}/title-history},
 * KNG-34 D9): a promotion or demotion caused by XP. {@code fromTitleName} is null for the first
 * title; {@code direction} is {@code Promotion} or {@code Demotion}.
 */
public record TitleChange(Instant changedAt, String fromTitleName, String toTitleName, String direction) {

    public boolean isPromotion() {
        return !"Demotion".equalsIgnoreCase(direction);
    }
}
