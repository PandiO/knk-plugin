package net.knightsandkings.knk.paper.siege.survival.economy;

import net.knightsandkings.knk.core.survival.economy.KillBlow;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KillBlowsTest {

    private final KillBlows blows = new KillBlows();
    private final UUID mob = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void theFreshHitOfTheKillerIsTheKillingBlow() {
        blows.record(mob, new KillBlow(alice, true, false), 1_000);
        KillBlow blow = blows.consume(mob, alice, 2_000);
        assertTrue(blow.critical());
        assertEquals(alice, blow.attacker());
        assertEquals(0, blows.size(), "consumed");
    }

    @Test
    void anotherPlayersHitOrAStaleHitGivesAPlainBlow() {
        blows.record(mob, new KillBlow(bob, true, true), 1_000);
        KillBlow blow = blows.consume(mob, alice, 1_500);
        assertEquals(alice, blow.attacker());
        assertFalse(blow.critical());
        assertFalse(blow.headshot());

        blows.record(mob, new KillBlow(alice, false, true), 1_000);
        assertFalse(blows.consume(mob, alice, 1_000 + KillBlows.TTL_MILLIS + 1).headshot());
    }

    @Test
    void pruneDropsStaleEntries() {
        blows.record(mob, new KillBlow(alice, false, true), 1_000);
        blows.prune(1_000 + KillBlows.TTL_MILLIS);
        assertEquals(1, blows.size());
        blows.prune(1_000 + KillBlows.TTL_MILLIS + 1);
        assertEquals(0, blows.size());
        assertTrue(blows.peek(mob).isEmpty());
    }
}
