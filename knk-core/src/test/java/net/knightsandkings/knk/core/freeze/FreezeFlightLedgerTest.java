package net.knightsandkings.knk.core.freeze;

import net.knightsandkings.knk.core.freeze.FreezeFlightLedger.Source;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#155: frozen players get flight allowed for the freeze only. */
class FreezeFlightLedgerTest {
    private final FreezeFlightLedger ledger = new FreezeFlightLedger();
    private final UUID player = UUID.randomUUID();

    @Test
    void aFreezeAllowsFlight_AndItsEndHandsTheOwnSettingBack() {
        assertTrue(ledger.grant(player, Source.ENCHANTMENT, false));
        assertTrue(ledger.isGranted(player));

        assertEquals(Optional.of(false), ledger.release(player, Source.ENCHANTMENT));
        assertFalse(ledger.isGranted(player));
    }

    @Test
    void aPlayerWhoCanFlyAlreadyNeedsNoChange_AndKeepsFlying() {
        assertFalse(ledger.grant(player, Source.ADMIN, true));
        assertEquals(Optional.of(true), ledger.release(player, Source.ADMIN));
    }

    @Test
    void overlappingFreezesHoldTheGrantUntilBothEnded() {
        assertTrue(ledger.grant(player, Source.ADMIN, false));
        // The enchantment freeze sees flight on (granted), but the own setting stays the one from before.
        assertFalse(ledger.grant(player, Source.ENCHANTMENT, true));

        assertEquals(Optional.empty(), ledger.release(player, Source.ENCHANTMENT));
        assertTrue(ledger.isGranted(player));
        assertEquals(Optional.of(false), ledger.release(player, Source.ADMIN));
    }

    @Test
    void theSameFreezeTwiceIsOneGrant() {
        assertTrue(ledger.grant(player, Source.ENCHANTMENT, false));
        assertFalse(ledger.grant(player, Source.ENCHANTMENT, true));
        assertEquals(Optional.of(false), ledger.release(player, Source.ENCHANTMENT));
        assertEquals(Optional.empty(), ledger.release(player, Source.ENCHANTMENT));
    }

    @Test
    void releasingAFreezeThatNeverGrantedDoesNothing() {
        assertEquals(Optional.empty(), ledger.release(player, Source.ADMIN));
        ledger.grant(player, Source.ENCHANTMENT, false);
        assertEquals(Optional.empty(), ledger.release(player, Source.ADMIN));
        assertTrue(ledger.isGranted(player));
    }

    @Test
    void releaseAllEndsEveryFreeze() {
        ledger.grant(player, Source.ENCHANTMENT, false);
        ledger.grant(player, Source.ADMIN, true);
        assertEquals(Optional.of(false), ledger.releaseAll(player));
        assertFalse(ledger.isGranted(player));
        assertEquals(Optional.empty(), ledger.releaseAll(player));
        assertTrue(ledger.players().isEmpty());
    }

    @Test
    void flightIsOnlySwitchedOffForSomeoneWhoCouldNotFlyAndStillCanInASurvivalMode() {
        assertTrue(FreezeFlightLedger.shouldDisallowOnRelease(false, true, false));
        assertFalse(FreezeFlightLedger.shouldDisallowOnRelease(true, true, false), "could fly before");
        assertFalse(FreezeFlightLedger.shouldDisallowOnRelease(false, false, false), "someone already switched it off");
        assertFalse(FreezeFlightLedger.shouldDisallowOnRelease(false, true, true), "creative or spectator now");
    }

    @Test
    void nullsAreIgnored() {
        assertFalse(ledger.grant(null, Source.ADMIN, false));
        assertFalse(ledger.grant(player, null, false));
        assertEquals(Optional.empty(), ledger.release(null, Source.ADMIN));
        assertFalse(ledger.isGranted(null));
    }
}
