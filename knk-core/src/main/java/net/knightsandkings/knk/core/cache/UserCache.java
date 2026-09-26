package net.knightsandkings.knk.core.cache;

import java.time.Duration;
import java.util.UUID;
import net.knightsandkings.knk.core.domain.users.UserSummary;

/**
 * Type-safe cache for User entities keyed by Minecraft UUID.
 * <p>
 * Unlike region-based caches, this cache uses UUID as the primary key
 * to support player-centric lookups without secondary indices.
 */
public class UserCache extends BaseCache<UUID, UserSummary> {

    public UserCache(Duration ttl) {
        super(ttl);
    }

    /**
     * Retrieves a user by their Minecraft UUID.
     *
     * @param uuid The player's UUID
     * @return Optional containing the user if cached and not expired
     */
    public java.util.Optional<UserSummary> getByUuid(UUID uuid) {
        return get(uuid);
    }

    /**
     * Stores a user in the cache indexed by UUID.
     *
     * @param user The user summary to cache
     */
    public void put(UserSummary user) {
        if (user == null || user.uuid() == null) {
            return;
        }
        put(user.uuid(), user);
    }

    /**
     * Replaces the cached player's coins and gems with the values the API just returned
     * (currency ledger, KNG-21 Phase 3). Updates the stale entry too, since the numbers are fresh;
     * does nothing when the player isn't cached at all (the next read fetches them anyway).
     * Values beyond int range (never produced by the API's caps) are ignored.
     */
    public void updateBalances(UUID uuid, long coins, long gems) {
        if (uuid == null || coins < 0 || gems < 0 || coins > Integer.MAX_VALUE || gems > Integer.MAX_VALUE) {
            return;
        }
        getStale(uuid).ifPresent(user -> put(uuid, user.withBalances((int) coins, (int) gems)));
    }
}
