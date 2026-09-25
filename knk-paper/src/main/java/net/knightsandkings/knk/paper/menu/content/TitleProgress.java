package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.domain.users.TitleBracket;

import java.util.List;

/**
 * Where a user stands on the title ladder (CONTENT_PORT_PLAN.md CP3/CP8): the bracket they hold,
 * the next one, and XP still needed. The server-resolved {@code titleBracketId} wins when it is
 * one of the brackets; otherwise the highest bracket whose {@code minExperience <= xp}.
 */
record TitleProgress(TitleBracket current, TitleBracket next, int experienceToNext) {

    static TitleProgress of(List<TitleBracket> brackets, Integer titleBracketId, int experience) {
        int currentIndex = -1;
        if (titleBracketId != null) {
            for (int i = 0; i < brackets.size(); i++) {
                if (brackets.get(i).id() == titleBracketId) {
                    currentIndex = i;
                    break;
                }
            }
        }
        if (currentIndex < 0) {
            for (int i = 0; i < brackets.size(); i++) {
                if (brackets.get(i).minExperience() <= experience) {
                    currentIndex = i;
                }
            }
        }
        TitleBracket current = currentIndex >= 0 ? brackets.get(currentIndex) : null;
        TitleBracket next = currentIndex + 1 < brackets.size() ? brackets.get(currentIndex + 1) : null;
        int toNext = next != null ? Math.max(0, next.minExperience() - experience) : 0;
        return new TitleProgress(current, next, toNext);
    }

    int currentIndex(List<TitleBracket> brackets) {
        return current == null ? -1 : brackets.indexOf(current);
    }

    boolean isHighest() {
        return current != null && next == null;
    }
}
