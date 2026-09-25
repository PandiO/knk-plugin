package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.UsersQueryApi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The viewer's own user record for a menu's variable root, read fresh without blocking the main
 * thread (content port CP3/CP5): a row source's fetch (async) calls {@link #refresh}; the root's
 * provider (main thread, no I/O) calls {@link #current}, which prefers that fresh read and falls
 * back to the plugin's user cache (stale entry). Remembers the last {@value #MAX_REMEMBERED}
 * viewers.
 */
final class FreshViewers {

    static final int MAX_REMEMBERED = 256;

    private final UsersQueryApi usersQueryApi;
    private final UserCache userCache;
    private final Map<UUID, UserSummary> fresh = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, UserSummary> eldest) {
                    return size() > MAX_REMEMBERED;
                }
            });

    FreshViewers(UsersQueryApi usersQueryApi, UserCache userCache) {
        this.usersQueryApi = usersQueryApi;
        this.userCache = userCache;
    }

    /** Reads the viewer from the API; completes with the fresh record, or the cached one if the read fails. */
    CompletableFuture<UserSummary> refresh(UUID uuid) {
        return usersQueryApi.getByUuid(uuid)
                .exceptionally(ex -> null)
                .thenApply(user -> {
                    if (user != null) {
                        fresh.put(uuid, user);
                        return user;
                    }
                    return cached(uuid).orElse(null);
                });
    }

    /** No I/O. */
    Optional<UserSummary> current(UUID uuid) {
        UserSummary user = fresh.get(uuid);
        return user != null ? Optional.of(user) : cached(uuid);
    }

    private Optional<UserSummary> cached(UUID uuid) {
        return userCache.getStale(uuid);
    }
}
