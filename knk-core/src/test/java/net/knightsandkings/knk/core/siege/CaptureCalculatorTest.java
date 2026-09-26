package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Siege Phase 4 legacy golden tests (DESIGN §7.2, §7.4). The reference is the v1
 * {@code Objective.calculateCapturePoints} loop (v1:src/Sieges/Objective.java, identical in v2
 * {@code SiegeObjective.calculateCapturePoints} apart from the "cinixians" team check), ported
 * verbatim below, with the per-second step {@code current − calculateCapturePoints()} from
 * {@code MGObjective.startCaptureTask}.
 */
class CaptureCalculatorTest {

    private final CaptureCalculator calculator = new CaptureCalculator(KnkSiegeConfiguration.legacyDefaults());

    /** v1 Objective.calculateCapturePoints, counts instead of a player scan; "main" = instant victory. */
    private static int legacyCalculateCapturePoints(int attackers, int defenders, boolean main) {
        int defendAmount = 0;
        int captureAmount = 0;
        int capturePoints = 0;
        for (int i = 0; i < defenders; i++) {
            defendAmount++;
            if (defendAmount == 1) {
                capturePoints -= 6;
            } else {
                int step = 3;
                if (main) step = 6;
                capturePoints -= step;
            }
        }
        for (int i = 0; i < attackers; i++) {
            captureAmount++;
            if (captureAmount == 1) {
                capturePoints += 5;
            } else {
                int step = 2;
                if (main) step = 5;
                capturePoints += step;
            }
        }
        return capturePoints;
    }

    @ParameterizedTest(name = "{0} attackers vs {1} defenders, IV={2} -> delta {3}")
    @CsvSource({
            // non-instant-victory: attack 5 + (a-1)*2, defend 6 + (d-1)*3
            "1, 0, false, 5",
            "1, 1, false, -1",
            "1, 3, false, -7",
            "2, 0, false, 7",
            "2, 1, false, 1",
            "2, 3, false, -5",
            "5, 0, false, 13",
            "5, 1, false, 7",
            "5, 3, false, 1",
            // instant-victory ("main"): attack 5 + (a-1)*5, defend 6 + (d-1)*6
            "1, 0, true, 5",
            "1, 1, true, -1",
            "1, 3, true, -13",
            "2, 0, true, 10",
            "2, 1, true, 4",
            "2, 3, true, -8",
            "5, 0, true, 25",
            "5, 1, true, 19",
            "5, 3, true, 7"
    })
    void goldenDeltas(int attackers, int defenders, boolean instantVictory, int expectedDelta) {
        assertEquals(expectedDelta, calculator.delta(attackers, defenders, instantVictory));
        assertEquals(legacyCalculateCapturePoints(attackers, defenders, instantVictory),
                calculator.delta(attackers, defenders, instantVictory));
    }

    @Test
    void matchesTheLegacyLoopOnAWholeGrid() {
        for (int a = 0; a <= 8; a++) {
            for (int d = 0; d <= 8; d++) {
                for (boolean iv : new boolean[]{false, true}) {
                    assertEquals(legacyCalculateCapturePoints(a, d, iv), calculator.delta(a, d, iv),
                            a + " attackers vs " + d + " defenders, IV=" + iv);
                }
            }
        }
    }

    @Test
    void nobodyPresentChangesNothing() {
        assertEquals(0, calculator.delta(0, 0, false));
        assertEquals(0, calculator.attackPressure(0, true));
        assertEquals(0, calculator.defensePressure(0, true));
    }

    @Test
    void stepClampsToZeroAndToTheObjectivesCapturePoints() {
        assertEquals(495, calculator.applyStep(500, 500, 5));
        assertEquals(0, calculator.applyStep(3, 500, 5));
        // Defenders restore points, but never above the objective's capture points.
        assertEquals(500, calculator.applyStep(498, 500, -7));
        assertEquals(500, calculator.applyStep(500, 500, -1));
    }

    @Test
    void goldenCaptureTimes() {
        // A lone attacker on an undefended 500-point objective: 5 points/s -> 100 s, as in v1/v2.
        assertEquals(100, secondsToCapture(1, 0, false));
        // Two attackers against one defender only gain 1 point/s.
        assertEquals(500, secondsToCapture(2, 1, false));
        // Five attackers on an undefended main objective: 25 points/s -> 20 s.
        assertEquals(20, secondsToCapture(5, 0, true));
    }

    private int secondsToCapture(int attackers, int defenders, boolean iv) {
        int points = 500;
        int seconds = 0;
        while (points > 0) {
            points = calculator.applyStep(points, 500, calculator.delta(attackers, defenders, iv));
            seconds++;
        }
        return seconds;
    }

    @ParameterizedTest(name = "IV {0} points, {1} side objectives -> {2}")
    @CsvSource({
            "500, 1, 200",
            "500, 2, 100",
            "500, 3, 66",
            "250, 2, 50",
            "1000, 3, 133",
            "500, 0, 0"
    })
    void goldenSideCaptureReduction(int ivCapturePoints, int sideObjectives, int expected) {
        assertEquals(expected, calculator.sideCaptureReduction(ivCapturePoints, sideObjectives));
        if (sideObjectives > 0) {
            // v2 SiegeScenario.setObjectiveCaptured: part = original/5*2; sidePart = part/nonIvCount
            int legacy = ivCapturePoints / 5 * 2 / sideObjectives;
            assertEquals(legacy, calculator.sideCaptureReduction(ivCapturePoints, sideObjectives));
        }
    }

    @Test
    void sideCaptureReductionDiffersFromLegacyOnlyWhenNotDivisibleByFive() {
        // Legacy truncated original/5 first: 503/5*2 = 200. DESIGN §7.4's floor(503 × 0.4) = 201.
        assertEquals(201, calculator.sideCaptureReduction(503, 1));
        assertEquals(200, 503 / 5 * 2);
    }
}
