package net.knightsandkings.knk.core.lootbox;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The in-flight claim lock (docs/specs/lootboxes/DESIGN.md §3.7): while a claim for a box is on its way to the API,
 * further clicks on that box (by anyone) and further claims by the same player are ignored. The API stays the real
 * guard (conditional status update, unique index, idempotency key); this only stops a double click from sending two
 * requests and a player from racing their own daily cap.
 */
public final class ClaimGuard {

    private final Map<Integer, UUID> claimantBySpawn = new HashMap<>();
    private final Set<UUID> busyPlayers = new HashSet<>();

    /** True when the claim may go ahead; the caller must {@link #release} it when the call completes. */
    public synchronized boolean tryAcquire(int spawnId, UUID player) {
        if (player == null || claimantBySpawn.containsKey(spawnId) || busyPlayers.contains(player)) {
            return false;
        }
        claimantBySpawn.put(spawnId, player);
        busyPlayers.add(player);
        return true;
    }

    public synchronized void release(int spawnId, UUID player) {
        if (player != null && player.equals(claimantBySpawn.get(spawnId))) {
            claimantBySpawn.remove(spawnId);
        }
        busyPlayers.remove(player);
    }

    public synchronized boolean isInFlight(int spawnId) {
        return claimantBySpawn.containsKey(spawnId);
    }

    /**
     * The claim's idempotency key, {@code "{token}:{userId}"} (DESIGN.md §3.7): a retry by the same player replays the
     * stored result instead of rolling again.
     */
    public static String idempotencyKey(UUID token, int userId) {
        return token + ":" + userId;
    }
}
