package net.knightsandkings.knk.core.dataaccess;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import net.knightsandkings.knk.core.domain.teleport.KnkTeleportPolicy;
import net.knightsandkings.knk.core.ports.api.TeleportDestinationsQueryApi;

/**
 * A per-player cache of the teleport fees and cooldowns their permission groups set (Linear KNG-41,
 * {@code GET api/teleport-destinations/policy}), kept like the warp list
 * ({@link TeleportDestinationsDataAccess}): fetched once, fresh for {@code ttl}, one shared in-flight
 * request, the stale copy when a refresh fails. Keyed by Minecraft UUID because the teleport engine
 * works with players; the knk user id is looked up on each fetch.
 * <p>
 * Never decides what a teleport costs - the charge routes price it again server-side. The plugin
 * reads it for the cooldown after a teleport and to know whether a {@code /tpa} or {@code /spawn}
 * needs a charge at all; when it can't be loaded, the defaults apply
 * ({@link KnkTeleportPolicy#DEFAULT}).
 */
public class TeleportPolicyDataAccess {

    /** How old a policy a command the player just typed accepts (like the warp list). */
    public static final Duration PLAYER_READ_MAX_AGE = TeleportDestinationsDataAccess.PLAYER_READ_MAX_AGE;

    private final TeleportDestinationsQueryApi queryApi;
    private final Function<UUID, CompletableFuture<Integer>> userIds;
    private final Duration ttl;
    private final Clock clock;
    private final Map<UUID, CachedList<KnkTeleportPolicy>> policies = new ConcurrentHashMap<>();

    /**
     * @param userIds the player's knk user id; completes with null (or exceptionally) when they have none
     */
    public TeleportPolicyDataAccess(TeleportDestinationsQueryApi queryApi, Function<UUID, CompletableFuture<Integer>> userIds,
                                    Duration ttl) {
        this(queryApi, userIds, ttl, Clock.systemUTC());
    }

    public TeleportPolicyDataAccess(TeleportDestinationsQueryApi queryApi, Function<UUID, CompletableFuture<Integer>> userIds,
                                    Duration ttl, Clock clock) {
        this.queryApi = Objects.requireNonNull(queryApi, "queryApi must not be null");
        this.userIds = Objects.requireNonNull(userIds, "userIds must not be null");
        this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * The player's policy, fetched again when the cached one is older than {@code maxAge}. Never
     * completes exceptionally: no answer (and nothing cached) is {@link KnkTeleportPolicy#DEFAULT}.
     */
    public CompletableFuture<KnkTeleportPolicy> getAsync(UUID player, Duration maxAge) {
        CompletableFuture<List<KnkTeleportPolicy>> fetch;
        try {
            fetch = cached(player).getAsync(maxAge);
        } catch (RuntimeException ex) {
            return CompletableFuture.completedFuture(KnkTeleportPolicy.DEFAULT);
        }
        return fetch.handle((list, ex) -> ex == null && list != null && !list.isEmpty() ? list.get(0) : KnkTeleportPolicy.DEFAULT);
    }

    /** The cached policy (possibly stale), else the defaults. Never does I/O. */
    public KnkTeleportPolicy cachedOrDefault(UUID player) {
        CachedList<KnkTeleportPolicy> list = policies.get(player);
        List<KnkTeleportPolicy> cached = list != null ? list.cachedOrEmpty() : List.of();
        return cached.isEmpty() ? KnkTeleportPolicy.DEFAULT : cached.get(0);
    }

    /** Fetch the player's policy in the background if the cached one is older than {@code maxAge}. */
    public void refresh(UUID player, Duration maxAge) {
        getAsync(player, maxAge);
    }

    /** Forget a player (they left). */
    public void forget(UUID player) {
        policies.remove(player);
    }

    /** Drop every cached policy ({@code /knk cache refresh}: a group's settings were edited). */
    public void invalidateAll() {
        policies.clear();
    }

    private CachedList<KnkTeleportPolicy> cached(UUID player) {
        return policies.computeIfAbsent(player, id -> new CachedList<>(() -> load(id), ttl, clock));
    }

    private CompletableFuture<List<KnkTeleportPolicy>> load(UUID player) {
        CompletableFuture<Integer> lookup = userIds.apply(player);
        if (lookup == null) {
            return CompletableFuture.completedFuture(List.of(KnkTeleportPolicy.DEFAULT));
        }
        return lookup.thenCompose(userId -> userId == null
            ? CompletableFuture.completedFuture(KnkTeleportPolicy.DEFAULT)
            : queryApi.policyForUser(userId))
            .thenApply(policy -> List.of(policy != null ? policy : KnkTeleportPolicy.DEFAULT));
    }
}
