package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.api.GateStructuresApi;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tracks which Districts have already had their gates loaded this server session, and triggers
 * an on-demand load (via GateLoaderAdapter.loadForDistrict) the first time a player is resolved
 * into a district that hasn't been loaded yet - see the region-transition callback wired in
 * KnKPlugin. This is what lets a gate created or edited after server start show up without an
 * admin needing to run /knk gate admin reload.
 *
 * No eviction: once a district is loaded it stays cached for the session. Total gate count for a
 * game world is realistic in the hundreds, and safely evicting a gate mid-animation/mid-siege is
 * a meaningfully harder problem than the "gates don't show up until a reload" pain point this
 * solves - see docs/features/gate-structure-animation for the fuller reasoning.
 */
public class DistrictGateLoader {
    private static final Logger LOGGER = Logger.getLogger(DistrictGateLoader.class.getName());

    private final GateLoaderAdapter gateLoaderAdapter;
    private final GateStructuresApi gateStructuresApi;
    private final Set<Integer> loadedDistrictIds = ConcurrentHashMap.newKeySet();

    public DistrictGateLoader(GateLoaderAdapter gateLoaderAdapter, GateStructuresApi gateStructuresApi) {
        this.gateLoaderAdapter = gateLoaderAdapter;
        this.gateStructuresApi = gateStructuresApi;
    }

    /**
     * Load the given district's gates if they haven't already been loaded this session. Safe to
     * call repeatedly (e.g. on every region-transition into the same district) - a no-op after
     * the first successful load. On failure, the district is unmarked so the next entry retries.
     */
    public void loadIfNotAlreadyLoaded(int districtId) {
        if (!loadedDistrictIds.add(districtId)) {
            LOGGER.fine("District " + districtId + " already loaded this session; skipping fetch");
            return;
        }

        LOGGER.info("District " + districtId + " entered for the first time this session; loading its gates");
        gateLoaderAdapter.loadForDistrict(gateStructuresApi, districtId)
            .exceptionally(error -> {
                LOGGER.log(Level.WARNING, "Failed to load gates for district " + districtId, error);
                loadedDistrictIds.remove(districtId);
                return null;
            });
    }

    /**
     * Force a re-load of a district's gates (e.g. an admin command after editing a gate's
     * geometry in the web app) without needing a full /knk gate admin reload.
     */
    public CompletableFuture<Void> forceReload(int districtId) {
        loadedDistrictIds.add(districtId);
        return gateLoaderAdapter.loadForDistrict(gateStructuresApi, districtId);
    }

    /**
     * Whether a district's gates have already been loaded this session.
     */
    public boolean isLoaded(int districtId) {
        return loadedDistrictIds.contains(districtId);
    }
}
