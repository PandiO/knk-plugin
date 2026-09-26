package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.users.KnkTitleBracket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Phase 5: title-bracket names for join denials and ranks for the snake draft (Phase 4 decision 13). */
class SiegeTitleRanksTest {

    private final SiegeTitleRanks ranks = new SiegeTitleRanks(List.of(
            new KnkTitleBracket(3, "Knight", 500),
            new KnkTitleBracket(1, "Peasant", 0),
            new KnkTitleBracket(2, "Squire", 100)));

    @Test
    void rankIsTheBracketMinExperienceSoOneBracketRanksEqual() {
        assertEquals(100, ranks.rank(2, 150));
        assertEquals(100, ranks.rank(null, 499), "no bracket id: resolved from experience");
        assertEquals(500, ranks.rank(99, 700), "unknown bracket id: resolved from experience");
        assertEquals(0, ranks.rank(null, 0));
    }

    @Test
    void withoutBracketsTheRankIsRawExperience() {
        assertEquals(42, SiegeTitleRanks.empty().rank(2, 42));
        assertTrue(SiegeTitleRanks.empty().isEmpty());
    }

    @Test
    void requirementLabelNamesTheBracketOrFallsBackToTheExperienceNumber() {
        assertEquals("Squire", ranks.requirementLabel(100));
        assertEquals("250 XP", ranks.requirementLabel(250));
        assertEquals("100 XP", SiegeTitleRanks.empty().requirementLabel(100));
    }

    @Test
    void bracketForPicksTheHighestReachedBracket() {
        assertEquals("Knight", ranks.bracketFor(10_000).orElseThrow().name());
        assertEquals("Peasant", ranks.bracketFor(99).orElseThrow().name());
    }
}
