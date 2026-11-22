package net.knightsandkings.knk.paper.siege.weapons;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.logging.Logger;

import net.knightsandkings.knk.api.realtime.RegionQueryEvents;
import net.knightsandkings.knk.core.regions.query.RegionQueryAnswers;
import net.knightsandkings.knk.core.regions.query.RegionShapeLookup;
import net.knightsandkings.knk.core.siege.weapons.SpotSiteCheck;
import net.knightsandkings.knk.paper.realtime.RealtimeDispatcher;
import net.knightsandkings.knk.paper.realtime.RegionQueryRealtimeSync;

/**
 * Wires the siege weapons feature (PandiO/knk-workspace#354). Since #988 a weapon spot is a WorldGuard region drawn with
 * the normal region world task in the web app's spot form (the {@code WgRegionIdTaskHandler}); this module answers the
 * API's {@code SpotSiteCheckQuery} over the game hub: knk-core {@link SpotSiteCheck} (floor solid, space clear) on the
 * region's bounding box. With {@code siege.weapons.enabled} off (the default) nothing is registered, so the API gets no
 * answer and its rule says it couldn't check.
 */
public final class SiegeWeaponsModule {

    private SiegeWeaponsModule() {
    }

    /**
     * @param probes the block probe of a loaded world by name (null when the world isn't loaded)
     * @return the site check handler, registered on {@code dispatcher}, or null when the feature is off
     */
    public static RegionQueryRealtimeSync registerSiteCheck(SiegeWeaponsSettings settings, RealtimeDispatcher dispatcher,
                                                            Executor mainThread, RegionShapeLookup lookup,
                                                            Function<String, SpotSiteCheck.BlockProbe> probes,
                                                            BiPredicate<String, Object> hubSend, Logger logger) {
        if (settings == null || !settings.enabled() || dispatcher == null) {
            return null;
        }
        Objects.requireNonNull(probes, "probes");
        RegionQueryRealtimeSync sync = RegionQueryRealtimeSync.of(RegionQueryEvents.SPOT_SITE_CHECK_QUERY,
                RegionQueryEvents.SPOT_SITE_CHECK_RESULT, mainThread, lookup, (queryId, shape) -> {
                    SpotSiteCheck.BlockProbe probe = probes.apply(shape.world());
                    if (probe == null) {
                        return RegionQueryAnswers.error(queryId, "World " + shape.world() + " isn't loaded.");
                    }
                    return RegionQueryAnswers.siteCheck(queryId, SpotSiteCheck.check(shape.bounds(), probe));
                }, hubSend, logger);
        sync.register(dispatcher);
        logger.info("Siege weapons are on (" + SiegeWeaponsSettings.ENABLED_PATH + "): the weapon spot site check ("
                + RegionQueryEvents.SPOT_SITE_CHECK_QUERY + ") is answered.");
        return sync;
    }
}
