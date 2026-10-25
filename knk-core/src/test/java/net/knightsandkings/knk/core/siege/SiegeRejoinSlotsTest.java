package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.siege.SiegeRejoinSlots.Slot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static net.knightsandkings.knk.core.siege.SiegeTestData.player;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Alpha test run 1 (PandiO/knk-workspace#150): a disconnected member's place in the round. */
class SiegeRejoinSlotsTest {

    private static final Instant T0 = Instant.parse("2026-10-25T15:00:00Z");

    private static Slot slot(int player, int lobby, String token, Instant leftAt) {
        return new Slot(player(player), lobby, token, 201, 1000 + player, leftAt, true);
    }

    @Test
    void aMemberWhoComesBackWithinTheWindowGetsTheirSlotOnce() {
        SiegeRejoinSlots slots = new SiegeRejoinSlots(Duration.ofSeconds(300));
        assertTrue(slots.hold(slot(1, 7, "round-a", T0)));

        Optional<Slot> claimed = slots.claim(player(1), 7, "round-a", T0.plusSeconds(299));

        assertTrue(claimed.isPresent());
        assertEquals(201, claimed.get().teamId());
        assertEquals(1001, claimed.get().userId());
        assertTrue(slots.claim(player(1), 7, "round-a", T0.plusSeconds(299)).isEmpty());
        assertEquals(0, slots.size());
    }

    @Test
    void anotherRoundOrLobbyDoesNotGiveTheSlot() {
        SiegeRejoinSlots slots = new SiegeRejoinSlots(Duration.ofSeconds(300));
        slots.hold(slot(1, 7, "round-a", T0));

        assertTrue(slots.claim(player(1), 7, "round-b", T0.plusSeconds(10)).isEmpty());
        assertTrue(slots.claim(player(1), 8, "round-a", T0.plusSeconds(10)).isEmpty());
        assertEquals(1, slots.size());
        assertEquals(List.of(slot(1, 7, "round-a", T0)), slots.dropLobby(7));
        assertEquals(0, slots.size());
    }

    @Test
    void aSlotExpiresAtTheEndOfTheWindowAndIsReportedOnce() {
        SiegeRejoinSlots slots = new SiegeRejoinSlots(Duration.ofSeconds(60));
        slots.hold(slot(1, 7, "round-a", T0));
        slots.hold(slot(2, 7, "round-a", T0.plusSeconds(30)));

        assertTrue(slots.expire(T0.plusSeconds(59)).isEmpty());
        assertTrue(slots.claim(player(1), 7, "round-a", T0.plusSeconds(60)).isEmpty());
        List<Slot> expired = slots.expire(T0.plusSeconds(60));

        assertEquals(List.of(slot(1, 7, "round-a", T0)), expired);
        assertTrue(slots.expire(T0.plusSeconds(60)).isEmpty());
        assertTrue(slots.slotOf(player(2)).isPresent());
    }

    @Test
    void aLaterDisconnectReplacesTheEarlierSlot() {
        SiegeRejoinSlots slots = new SiegeRejoinSlots(Duration.ofSeconds(60));
        slots.hold(slot(1, 7, "round-a", T0));
        slots.hold(slot(1, 7, "round-a", T0.plusSeconds(100)));

        assertEquals(1, slots.size());
        assertTrue(slots.claim(player(1), 7, "round-a", T0.plusSeconds(150)).isPresent());
    }

    @Test
    void aZeroOrNegativeWindowTurnsRejoiningOff() {
        for (Duration window : new Duration[] {Duration.ZERO, Duration.ofSeconds(-5), null}) {
            SiegeRejoinSlots slots = new SiegeRejoinSlots(window);
            assertFalse(slots.isEnabled());
            assertFalse(slots.hold(slot(1, 7, "round-a", T0)));
            assertEquals(0, slots.size());
        }
    }

    @Test
    void forgetRemovesTheSlot() {
        SiegeRejoinSlots slots = new SiegeRejoinSlots(Duration.ofSeconds(60));
        slots.hold(slot(1, 7, "round-a", T0));
        assertTrue(slots.forget(player(1)).isPresent());
        assertTrue(slots.forget(player(1)).isEmpty());
    }
}
