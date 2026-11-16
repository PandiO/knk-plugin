package net.knightsandkings.knk.core.npc.nav.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Repeated pushes by one player (implementation plan 4c.4 item 7, link M7): counted in a window, exempted, expired. */
class PushLedgerTest {

    @Test
    void theThirdPushInTheWindowExempts() {
        PushLedger ledger = new PushLedger();
        assertFalse(ledger.record(1, "steve", 0, 3, 200));
        assertFalse(ledger.record(1, "steve", 50, 3, 200));
        assertFalse(ledger.record(1, "alex", 60, 3, 200), "another player counts on its own");
        assertFalse(ledger.record(2, "steve", 70, 3, 200), "another NPC counts on its own");
        assertTrue(ledger.record(1, "steve", 100, 3, 200));
        assertEquals(3, ledger.count(1, "steve", 100, 200));
    }

    @Test
    void pushesOutsideTheWindowDoNotCount() {
        PushLedger ledger = new PushLedger();
        ledger.record(1, "steve", 0, 3, 200);
        ledger.record(1, "steve", 100, 3, 200);
        assertFalse(ledger.record(1, "steve", 250, 3, 200), "the first push is older than 200 ticks");
        assertTrue(ledger.record(1, "steve", 260, 3, 200));
    }

    @Test
    void anExemptionLastsItsTimeAndThenCountingStartsAgain() {
        PushLedger ledger = new PushLedger();
        ledger.exempted(1, "steve", 1200);
        assertTrue(ledger.isExempt(1, "steve"));
        assertFalse(ledger.record(1, "steve", 10, 1, 200), "an exempt player is not counted");
        assertEquals(1, ledger.active());
        assertTrue(ledger.expire(1199).isEmpty());
        List<PushLedger.Exemption> up = ledger.expire(1200);
        assertEquals(List.of(new PushLedger.Exemption(1, "steve", 1200)), up);
        assertFalse(ledger.isExempt(1, "steve"));
        assertEquals(0, ledger.active());
        assertEquals(1, ledger.given());
    }

    @Test
    void forgettingAnNpcHandsBackItsExemptions() {
        PushLedger ledger = new PushLedger();
        ledger.exempted(1, "steve", 1200);
        ledger.exempted(2, "steve", 1200);
        ledger.record(1, "alex", 0, 3, 200);
        assertEquals(List.of(new PushLedger.Exemption(1, "steve", 1200)), ledger.forget(1));
        assertEquals(0, ledger.count(1, "alex", 0, 200));
        assertTrue(ledger.isExempt(2, "steve"));
    }
}
