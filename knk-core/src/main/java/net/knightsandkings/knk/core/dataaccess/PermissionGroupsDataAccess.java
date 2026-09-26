package net.knightsandkings.knk.core.dataaccess;

import net.knightsandkings.knk.core.domain.permissions.PermissionGroupSummary;
import net.knightsandkings.knk.core.ports.api.PermissionGroupsQueryApi;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Cached permission-group list (InventoryMenu content port CP5 premium tiers, CP8 group picker),
 * ordered by weight ascending. Groups change only through the web-app, so a short TTL is enough
 * ({@link CachedList}).
 */
public class PermissionGroupsDataAccess {

    private final CachedList<PermissionGroupSummary> list;

    public PermissionGroupsDataAccess(PermissionGroupsQueryApi queryApi, Duration ttl, Clock clock) {
        this.list = new CachedList<>(() -> queryApi.list().thenApply(groups -> groups == null ? List.of()
                : groups.stream().sorted(Comparator.comparingInt(PermissionGroupSummary::weight)
                        .thenComparingInt(PermissionGroupSummary::id)).toList()), ttl, clock);
    }

    public CompletableFuture<List<PermissionGroupSummary>> listAsync() {
        return list.getAsync();
    }

    public List<PermissionGroupSummary> cachedOrEmpty() {
        return list.cachedOrEmpty();
    }

    public void invalidate() {
        list.invalidate();
    }
}
