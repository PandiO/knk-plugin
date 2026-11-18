package net.knightsandkings.knk.core.effects;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#169: overlapping flashes make one blackout that ends with the last of them. */
class ViewerBlackoutLedgerTest {

    private final ViewerBlackoutLedger ledger = new ViewerBlackoutLedger();
    private final UUID viewer = UUID.randomUUID();

    @Test
    void onlyTheFirstFlashStartsABlackout_ALaterOneExtendsIt_AnEarlierEndNeverShortensIt() {
        assertTrue(ledger.start(viewer, 10_000L, 0L));
        assertFalse(ledger.start(viewer, 10_000L, 5_000L));
        assertEquals(10_000L, ledger.remainingMs(viewer, 5_000L));
        assertFalse(ledger.start(viewer, 1_000L, 6_000L));
        assertEquals(9_000L, ledger.remainingMs(viewer, 6_000L));
    }

    @Test
    void expireEndsOnlyRunOutBlackouts() {
        UUID other = UUID.randomUUID();
        ledger.start(viewer, 1_000L, 0L);
        ledger.start(other, 5_000L, 0L);

        assertEquals(List.of(viewer), ledger.expire(1_000L));
        assertFalse(ledger.isActive(viewer));
        assertTrue(ledger.isActive(other));
        assertTrue(ledger.start(viewer, 1_000L, 2_000L));
    }

    @Test
    void endReportsWhetherThereWasOne() {
        assertFalse(ledger.end(viewer));
        assertFalse(ledger.start(viewer, 0L, 0L));
        ledger.start(viewer, 1_000L, 0L);
        assertTrue(ledger.end(viewer));
        assertEquals(0L, ledger.remainingMs(viewer, 0L));
    }
}
