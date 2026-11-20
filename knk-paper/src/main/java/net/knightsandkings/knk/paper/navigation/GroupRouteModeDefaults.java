package net.knightsandkings.knk.paper.navigation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.domain.permissions.PermissionGroupSummary;
import net.knightsandkings.knk.core.domain.users.GroupMembershipSummary;
import net.knightsandkings.knk.core.roads.route.GroupRouteModeResolver;
import net.knightsandkings.knk.core.roads.route.RouteMode;

/**
 * Off-road L7b (PandiO/knk-workspace#852, decisions 41 and 43): the per-player cache of the route mode a player's
 * permission groups give {@code /navigate} when they type none ({@link GroupRouteModeResolver}).
 *
 * <p>{@link #cachedDefault(UUID)} never calls the API and never blocks: a warm entry answers at once; a miss (or an
 * expired entry) answers {@code null} - {@link RouteMode#DEFAULT}, today's behaviour - and fills the entry in the
 * background from the player's memberships ({@code GET api/UserPermissionGroups?userId=}) and the cached group list.
 * Entries drop with the permission cache: {@link #invalidateUser(int)} when a user's effective set is invalidated (a
 * membership change, in game or through the {@code UserPermissionsChanged} realtime event) and {@link #invalidateAll()}
 * when every set is or when the group list is ({@code PermissionGroupChanged}). A fill that started before an
 * invalidation is discarded, so a stale answer never lands after one. Thread-safe; Bukkit-free (keyed by UUID and user
 * id).
 */
public final class GroupRouteModeDefaults {

    /** How long an entry answers without a refill, as a backstop for changes no event reports (an expired membership). */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    /** A cached answer: {@code mode} null means "no group default". */
    private record Entry(RouteMode mode, Instant loadedAt) {
    }

    private final Function<UUID, Integer> userIdOf;
    private final IntFunction<CompletableFuture<List<GroupMembershipSummary>>> memberships;
    private final Supplier<CompletableFuture<List<PermissionGroupSummary>>> groups;
    private final Duration ttl;
    private final Clock clock;
    private final Logger logger;
    private final Map<Integer, Entry> entries = new ConcurrentHashMap<>();
    private final Set<Integer> loading = ConcurrentHashMap.newKeySet();
    /** Bumped by every invalidation; a fill stores its answer only when none happened while it ran. */
    private final AtomicLong generation = new AtomicLong();

    /**
     * @param userIdOf    the player's KnK user id from the user cache without an API call; null when not loaded
     * @param memberships the user's group memberships (async)
     * @param groups      every permission group (async, cached by the caller)
     */
    public GroupRouteModeDefaults(Function<UUID, Integer> userIdOf,
                                  IntFunction<CompletableFuture<List<GroupMembershipSummary>>> memberships,
                                  Supplier<CompletableFuture<List<PermissionGroupSummary>>> groups,
                                  Duration ttl, Clock clock, Logger logger) {
        this.userIdOf = Objects.requireNonNull(userIdOf, "userIdOf");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.ttl = ttl != null ? ttl : DEFAULT_TTL;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * The player's group default route mode from the cache; null when they have none or it isn't cached yet (then a
     * background fill starts). Never calls the API on the caller's thread.
     */
    public RouteMode cachedDefault(UUID player) {
        Integer userId = player == null ? null : userIdOf.apply(player);
        if (userId == null) {
            return null; // not loaded yet: nothing to look up by
        }
        Entry entry = entries.get(userId);
        if (entry == null || expired(entry)) {
            fill(userId);
        }
        return entry == null ? null : entry.mode();
    }

    /** Starts a background fill when the player's entry is missing or expired (e.g. as they type the command). */
    public void warm(UUID player) {
        cachedDefault(player);
    }

    /** A user's permissions or memberships changed. */
    public void invalidateUser(int userId) {
        synchronized (generation) {
            generation.incrementAndGet();
            entries.remove(userId);
        }
    }

    /** Every user's may have changed, or a group's default did. */
    public void invalidateAll() {
        synchronized (generation) {
            generation.incrementAndGet();
            entries.clear();
        }
    }

    private boolean expired(Entry entry) {
        return !entry.loadedAt().plus(ttl).isAfter(clock.instant());
    }

    private void fill(int userId) {
        if (!loading.add(userId)) {
            return; // one fill per user at a time
        }
        long startedAt = generation.get();
        CompletableFuture<List<GroupMembershipSummary>> held;
        CompletableFuture<List<PermissionGroupSummary>> all;
        try {
            held = memberships.apply(userId);
            all = groups.get();
        } catch (RuntimeException e) {
            loading.remove(userId);
            logger.log(Level.FINE, "[Navigation] Group route mode lookup for user " + userId + " failed to start", e);
            return;
        }
        held.thenCombine(all, GroupRouteModeResolver::resolve).whenComplete((mode, ex) -> {
            try {
                if (ex != null) {
                    // keep today's default; the next command tries again
                    logger.log(Level.FINE, "[Navigation] Group route mode lookup for user " + userId + " failed", ex);
                    return;
                }
                synchronized (generation) {
                    if (generation.get() == startedAt) {
                        entries.put(userId, new Entry(mode.orElse(null), clock.instant()));
                    }
                }
            } finally {
                loading.remove(userId);
            }
        });
    }
}
