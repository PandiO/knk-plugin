package net.knightsandkings.knk.core.lootbox;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The in-flight lock for opening token items (docs/specs/lootboxes/IMPLEMENTATION_PLAN.md Phase 5), like
 * {@link ClaimGuard} for world boxes: while a redeem of a token is on its way to the API, further clicks on any copy
 * of that token and further opens by the same player are ignored. The API stays the real guard (conditional status
 * update, unique claim per token); this only stops a double click from sending two requests.
 */
public final class TokenOpenGuard {

    private final Set<UUID> tokensInFlight = new HashSet<>();
    private final Set<UUID> busyPlayers = new HashSet<>();

    /** True when the open may go ahead; the caller must {@link #release} it when the call completes. */
    public synchronized boolean tryAcquire(UUID token, UUID player) {
        if (token == null || player == null || tokensInFlight.contains(token) || busyPlayers.contains(player)) {
            return false;
        }
        tokensInFlight.add(token);
        busyPlayers.add(player);
        return true;
    }

    public synchronized void release(UUID token, UUID player) {
        tokensInFlight.remove(token);
        busyPlayers.remove(player);
    }

    public synchronized boolean isInFlight(UUID token) {
        return tokensInFlight.contains(token);
    }

    /**
     * A fresh idempotency key per open attempt (click): a retry of that request replays the stored result, while a
     * click on a second copy of a duplicated item carries a new key and is refused {@code AlreadyRedeemed} by the API.
     * Never {@code "{token}:{userId}"}: that would replay (and could re-deliver) for every copy the same player holds.
     */
    public static String idempotencyKey(UUID token) {
        return "token-open:" + token + ":" + UUID.randomUUID().toString().replace("-", "");
    }
}
