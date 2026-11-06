package net.knightsandkings.knk.paper.modes;

import java.util.function.Function;

import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import net.knightsandkings.knk.paper.visibility.PlayerVisibilityArbiter;

/** Test access to {@link ModeService}'s package-private constructor (an injected visibility arbiter, link S3). */
public final class ModeServiceTestAccess {

    private ModeServiceTestAccess() {
    }

    public static ModeService create(Plugin plugin, KnkPermissible permissible, UserCache userCache, UsersCommandApi api,
                                     Function<ModeService, PlayerVisibilityArbiter> arbiter) {
        return new ModeService(plugin, permissible, userCache, api, arbiter);
    }
}
