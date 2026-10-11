package net.knightsandkings.knk.core.gates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.gates.GateFireAttribution.Igniter;

/** Fire attribution (DESIGN.md §F.8): one igniter per burning block, a tick's loss split evenly per block. */
class GateFireAttributionTest {

    private static final int GATE = 7;

    private final GateFireAttribution attribution = new GateFireAttribution();
    private final Igniter alice = new Igniter(UUID.randomUUID(), 11);
    private final Igniter bob = new Igniter(UUID.randomUUID(), 12);
    private final long b1 = GateFireAttribution.blockKey(10, 64, 10);
    private final long b2 = GateFireAttribution.blockKey(11, 64, 10);
    private final long b3 = GateFireAttribution.blockKey(12, 64, 10);

    @Test
    void blockKeysAreDistinctPerPosition() {
        assertNotEquals(GateFireAttribution.blockKey(1, 2, 3), GateFireAttribution.blockKey(3, 2, 1));
        assertNotEquals(GateFireAttribution.blockKey(-1, 64, 0), GateFireAttribution.blockKey(1, 64, 0));
        assertNotEquals(GateFireAttribution.blockKey(0, -60, 0), GateFireAttribution.blockKey(0, 60, 0));
        assertEquals(GateFireAttribution.blockKey(-30_000, -64, 29_999), GateFireAttribution.blockKey(-30_000, -64, 29_999));
    }

    @Test
    void aTicksLossIsSplitEvenlyOverTheBurningBlocksPerIgniter() {
        attribution.ignite(GATE, b1, alice);
        attribution.ignite(GATE, b2, alice);
        attribution.ignite(GATE, b3, bob);

        Map<Igniter, Double> credit = attribution.split(GATE, List.of(b1, b2, b3), 6.0);

        assertEquals(4.0, credit.get(alice), 1e-9);
        assertEquals(2.0, credit.get(bob), 1e-9);
    }

    @Test
    void anUnattributedBlocksShareGoesToNobody() {
        attribution.ignite(GATE, b1, alice);

        Map<Igniter, Double> credit = attribution.split(GATE, List.of(b1, b2), 4.0);

        assertEquals(Map.of(alice, 2.0), credit);
    }

    @Test
    void reIgnitingHandsTheBlockToTheNewestIgniter() {
        attribution.ignite(GATE, b1, alice);
        attribution.ignite(GATE, b1, bob);
        assertEquals(bob, attribution.igniterOf(GATE, b1));

        attribution.ignite(GATE, b1, null);
        assertNull(attribution.igniterOf(GATE, b1), "an unattributed re-ignite takes the credit away");
        assertEquals(Map.of(), attribution.split(GATE, List.of(b1), 2.0));
    }

    @Test
    void blocksThatStoppedBurningAreForgotten() {
        attribution.ignite(GATE, b1, alice);
        attribution.ignite(GATE, b2, bob);

        Map<Igniter, Double> credit = attribution.split(GATE, List.of(b2), 2.0);

        assertEquals(Map.of(bob, 2.0), credit);
        assertNull(attribution.igniterOf(GATE, b1));
        assertEquals(1, attribution.attributedBlocks(GATE));
        attribution.split(GATE, List.of(), 0.0);
        assertEquals(0, attribution.attributedBlocks(GATE));
    }

    @Test
    void noLossCreditsNobodyButKeepsTheIgniters() {
        attribution.ignite(GATE, b1, alice);

        assertTrue(attribution.split(GATE, List.of(b1), 0.0).isEmpty(), "an invincible or destroyed gate lost nothing");
        assertTrue(attribution.split(GATE, List.of(b1), Double.NaN).isEmpty());
        assertEquals(alice, attribution.igniterOf(GATE, b1));
    }

    @Test
    void gatesAreIndependentAndClearForgetsOne() {
        attribution.ignite(GATE, b1, alice);
        attribution.ignite(8, b1, bob);

        attribution.clear(GATE);

        assertEquals(0, attribution.attributedBlocks(GATE));
        assertEquals(Map.of(bob, 3.0), attribution.split(8, List.of(b1), 3.0));
        assertEquals(Map.of(), attribution.split(99, List.of(b1), 3.0), "an unknown gate credits nobody");
    }

    @Test
    void theOfflineIgnitersUserIdIsKept() {
        Igniter offline = new Igniter(UUID.randomUUID(), 42);
        attribution.ignite(GATE, b1, offline);

        Igniter credited = attribution.split(GATE, List.of(b1), 1.0).keySet().iterator().next();

        assertEquals(42, credited.userId());
    }
}
