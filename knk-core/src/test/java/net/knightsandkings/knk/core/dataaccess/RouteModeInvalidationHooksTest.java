package net.knightsandkings.knk.core.dataaccess;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.ports.api.PermissionGroupsQueryApi;
import net.knightsandkings.knk.core.ports.api.PermissionsApi;

/**
 * Off-road L7b (PandiO/knk-workspace#852): the group route mode defaults drop with the permission cache, so the
 * permission and group caches tell their listeners about every invalidation.
 */
class RouteModeInvalidationHooksTest {

    /** An API the test never calls. */
    private static <T> T unused(Class<T> type) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException(method.getName());
            }));
    }

    @Test
    void permissionInvalidationsReachTheListeners() {
        PermissionsDataAccess permissions = new PermissionsDataAccess(Duration.ofMinutes(1), unused(PermissionsApi.class));
        List<String> heard = new ArrayList<>();
        permissions.addInvalidationListener(new PermissionsDataAccess.InvalidationListener() {
            @Override
            public void userInvalidated(int userId) {
                heard.add("user " + userId);
            }

            @Override
            public void allInvalidated() {
                heard.add("all");
            }
        });
        permissions.invalidateEffective(42);
        permissions.invalidateAll();
        permissions.forget(42); // at quit: the user's derived entries drop too
        assertEquals(List.of("user 42", "all", "user 42"), heard);
    }

    @Test
    void aGroupListInvalidationReachesTheListeners() {
        PermissionGroupsDataAccess groups = new PermissionGroupsDataAccess(unused(PermissionGroupsQueryApi.class),
            Duration.ofMinutes(2), Clock.systemUTC());
        List<String> heard = new ArrayList<>();
        groups.addInvalidationListener(() -> heard.add("groups"));
        groups.invalidate();
        assertEquals(List.of("groups"), heard);
    }
}
