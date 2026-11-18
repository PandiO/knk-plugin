package net.knightsandkings.knk.core.ports.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.regions.TempRegionRelease;

/**
 * PandiO/knk-workspace#269: temp regions knk-web-api released for this game server to remove (plugin service key only):
 * {@code GET api/WorldTasks/temp-region-releases?serverId=} and {@code POST api/WorldTasks/temp-region-releases/{id}/settle}.
 */
public interface TempRegionReleasesApi {
    /** The releases this server may act on now; fails when the API can't be asked. */
    CompletableFuture<List<TempRegionRelease>> listDue(String serverId);

    /** Settles a release (the API deletes it); false when it was settled already (404). */
    CompletableFuture<Boolean> settle(int releaseId, TempRegionRelease.Outcome outcome, String serverId, String detail);
}
