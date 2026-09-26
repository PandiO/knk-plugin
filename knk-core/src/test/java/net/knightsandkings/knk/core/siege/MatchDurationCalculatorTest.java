package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchLength;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Siege Phase 4: match length = clamp(members × perPlayer, min, max) (DESIGN §6.5). */
class MatchDurationCalculatorTest {

    @ParameterizedTest(name = "{0} members -> {1} s")
    @CsvSource({
            "0, 300",
            "1, 300",
            "4, 300",
            "5, 375",
            "8, 600",
            "24, 1800",
            "30, 1800"
    })
    void scenarioDefaults(int members, int expectedSeconds) {
        assertEquals(expectedSeconds, MatchDurationCalculator.seconds(KnkSiegeMatchLength.DEFAULT, members));
    }

    @Test
    void usesTheScenarioRule() {
        assertEquals(120, MatchDurationCalculator.seconds(new KnkSiegeMatchLength(60, 30, 600), 4));
        assertEquals(600, MatchDurationCalculator.seconds(new KnkSiegeMatchLength(60, 30, 600), 100));
    }

    @Test
    void theMaximumWinsOverAnInvertedMinimum() {
        assertEquals(200, MatchDurationCalculator.seconds(new KnkSiegeMatchLength(300, 75, 200), 1));
    }

    @Test
    void documentsTheDifferenceFromV2() {
        // v2 Siege.calculateProgressExpire: ceil(members × 1.25) minutes, at least 5 minutes, no cap.
        int members = 5;
        int v2Seconds = Math.max(5, (int) Math.ceil(members * 1.25)) * 60;
        assertEquals(420, v2Seconds);
        assertEquals(375, MatchDurationCalculator.seconds(KnkSiegeMatchLength.DEFAULT, members));
    }
}
