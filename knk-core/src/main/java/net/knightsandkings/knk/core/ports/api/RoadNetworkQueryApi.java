package net.knightsandkings.knk.core.ports.api;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.common.Conditional;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadNetworkMeta;
import net.knightsandkings.knk.core.domain.roads.RoadProfile;
import net.knightsandkings.knk.core.domain.roads.RoadSeed;
import net.knightsandkings.knk.core.domain.roads.RoadSeedLocation;
import net.knightsandkings.knk.core.domain.roads.RoadSurvey;
import net.knightsandkings.knk.core.domain.roads.RoadTile;
import net.knightsandkings.knk.core.domain.roads.RoadTileGraph;
import net.knightsandkings.knk.core.domain.roads.RoadTileProposalSummary;
import net.knightsandkings.knk.core.roads.build.TileProposal;

/**
 * Road navigation reads (knk-web-api's road controllers, docs/specs/navigation/IMPLEMENTATION_PLAN.md
 * Phase 1.5 route table). All routes here are anonymous. Bukkit-free.
 *
 * <p>Every future completes exceptionally with the {@code ApiException} as the cause on a non-2xx
 * answer (a 304 on {@link #tileGraph} is not an error) so callers can tell a refusal from an
 * unreachable API; the api-client's {@code RoadMapper.error} reads the {@code {error, message}}
 * body.
 */
public interface RoadNetworkQueryApi {

    /** {@code GET api/road-tiles?world=}: every known tile of the world (built or only marked dirty). */
    CompletableFuture<List<RoadTile>> tiles(String world);

    /**
     * {@code GET api/road-tiles/{world}/{x}/{z}/graph} with {@code If-None-Match: etag} (plan R17,
     * D3): the tile's nodes and owned edges, or {@link Conditional#notModified()} when the caller's
     * copy is still current. A tile that was never built answers 404 (exceptional); Phase 3 lists
     * {@link #tiles} first.
     *
     * @param etag the ETag of the caller's cached copy ({@link RoadTile#etag()}), or {@code null} for
     *             an unconditional download
     */
    CompletableFuture<Conditional<RoadTileGraph>> tileGraph(String world, int tileX, int tileZ, String etag);

    /**
     * {@code GET api/road-tiles/{world}/{x}/{z}/proposal} (plan §5.7 D5): the tile's stored proposal (pending
     * items and rejected list), or empty when it has none (404).
     */
    CompletableFuture<Optional<TileProposal>> proposal(String world, int tileX, int tileZ);

    /** {@code GET api/road-tiles/proposals?world=}: every tile of the world with a proposal row. */
    CompletableFuture<List<RoadTileProposalSummary>> proposals(String world);

    /** {@code GET api/road-network/meta?world=}: profiles, labelled streets and components. */
    CompletableFuture<RoadNetworkMeta> meta(String world);

    /** {@code GET api/road-profiles}: every profile, enabled or not. */
    CompletableFuture<List<RoadProfile>> profiles();

    /** {@code GET api/road-profiles/{id}}; a 404 completes with {@code null}. */
    CompletableFuture<RoadProfile> profile(int id);

    /** {@code GET api/road-surveys?world=}: the world's stored survey walks. */
    CompletableFuture<List<RoadSurvey>> surveys(String world);

    /** {@code GET api/road-seeds?world=}: admin and survey seeds of the world. */
    CompletableFuture<List<RoadSeed>> seeds(String world);

    /**
     * {@code GET api/road-network/seed-locations?world=&minX=&minZ=&maxX=&maxZ=} (plan D12): the
     * domain Locations inside the box (inclusive), for the builder's tile seeds.
     */
    CompletableFuture<List<RoadSeedLocation>> seedLocations(String world, int minX, int minZ, int maxX, int maxZ);

    /**
     * {@code POST api/road-edges/search}: filters {@code world}, {@code tileId}, {@code streetId},
     * {@code unlabelled}, {@code stale} (values are strings, {@code "true"}), {@code sortBy}
     * {@code id | length | streetId | tileId}.
     */
    CompletableFuture<Page<RoadEdge>> searchEdges(PagedQuery query);
}
