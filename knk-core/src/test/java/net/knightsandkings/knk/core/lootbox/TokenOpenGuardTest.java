package net.knightsandkings.knk.core.lootbox;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lootboxes Phase 5: one open in flight per token and per player; a fresh idempotency key per click. */
class TokenOpenGuardTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID token = UUID.randomUUID();
    private final TokenOpenGuard guard = new TokenOpenGuard();

    @Test
    void aSecondCopyOfTheSameToken_isIgnoredWhileTheFirstOpenIsInFlight() {
        assertTrue(guard.tryAcquire(token, alice));
        assertFalse(guard.tryAcquire(token, alice), "double click");
        assertFalse(guard.tryAcquire(token, bob), "a duplicated copy in someone else's hands");
        assertFalse(guard.tryAcquire(UUID.randomUUID(), alice), "the same player opening another token at once");
        assertTrue(guard.isInFlight(token));

        guard.release(token, alice);

        assertFalse(guard.isInFlight(token));
        assertTrue(guard.tryAcquire(token, bob));
    }

    @Test
    void nulls_areRefused() {
        assertFalse(guard.tryAcquire(null, alice));
        assertFalse(guard.tryAcquire(token, null));
    }

    @Test
    void idempotencyKey_isNewPerClick_andFitsTheApiLimit() {
        String first = TokenOpenGuard.idempotencyKey(token);
        String second = TokenOpenGuard.idempotencyKey(token);

        assertNotEquals(first, second);
        assertTrue(first.startsWith("token-open:" + token + ":"));
        assertTrue(first.length() <= 128);
    }
}
