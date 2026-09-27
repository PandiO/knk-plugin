package net.knightsandkings.knk.core.lootbox;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lootboxes Phase 3: one claim in flight per box and per player. */
class ClaimGuardTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final ClaimGuard guard = new ClaimGuard();

    @Test
    void secondClickOnTheSameBox_isIgnoredUntilReleased() {
        assertTrue(guard.tryAcquire(1, alice));
        assertFalse(guard.tryAcquire(1, alice), "double click");
        assertFalse(guard.tryAcquire(1, bob), "someone else while the first claim is in flight");
        assertTrue(guard.isInFlight(1));

        guard.release(1, alice);

        assertFalse(guard.isInFlight(1));
        assertTrue(guard.tryAcquire(1, bob));
    }

    @Test
    void onePlayer_claimsOneBoxAtATime() {
        assertTrue(guard.tryAcquire(1, alice));
        assertFalse(guard.tryAcquire(2, alice));
        assertTrue(guard.tryAcquire(2, bob));

        guard.release(1, alice);
        assertTrue(guard.tryAcquire(3, alice));
    }

    @Test
    void releaseByAnotherPlayer_keepsTheBoxLocked() {
        guard.tryAcquire(1, alice);
        guard.release(1, bob);
        assertTrue(guard.isInFlight(1));
    }

    @Test
    void nullPlayer_isRefused() {
        assertFalse(guard.tryAcquire(1, null));
    }

    @Test
    void idempotencyKey_isTokenColonUser() {
        UUID token = UUID.fromString("00000000-0000-0000-0000-000000000042");
        assertEquals("00000000-0000-0000-0000-000000000042:9", ClaimGuard.idempotencyKey(token, 9));
    }
}
