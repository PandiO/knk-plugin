package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.siege.SiegeDefenderPassThrough.OpenGateClick;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Alpha test run 1 (PandiO/knk-workspace#154): defenders pass through siege gates. */
class SiegeDefenderPassThroughTest {

    @Test
    void theConfiguredHoldTimeWinsOverTheDoors() {
        assertEquals(6, SiegeDefenderPassThrough.holdSeconds(6, IntStream.of(2, 10)));
    }

    @Test
    void withoutASettingTheLongestDoorPassThroughTimeIsUsedAtLeastOneSecond() {
        assertEquals(10, SiegeDefenderPassThrough.holdSeconds(0, IntStream.of(2, 10)));
        assertEquals(1, SiegeDefenderPassThrough.holdSeconds(-1, IntStream.of(0)));
        assertEquals(SiegeDefenderPassThrough.FALLBACK_HOLD_SECONDS, SiegeDefenderPassThrough.holdSeconds(0, IntStream.empty()));
    }

    @Test
    void aClickOnAnOpenPassThroughExtendsItAnyOtherOpenGateCloses() {
        assertEquals(OpenGateClick.EXTEND, SiegeDefenderPassThrough.onOpenGateClick(true, true));
        assertEquals(OpenGateClick.CLOSE, SiegeDefenderPassThrough.onOpenGateClick(true, false));
        assertEquals(OpenGateClick.CLOSE, SiegeDefenderPassThrough.onOpenGateClick(false, true));
        assertEquals(OpenGateClick.CLOSE, SiegeDefenderPassThrough.onOpenGateClick(false, false));
    }
}
