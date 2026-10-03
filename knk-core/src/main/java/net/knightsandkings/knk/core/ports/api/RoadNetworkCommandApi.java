package net.knightsandkings.knk.core.ports.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgePruneResult;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeRecord;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeUpdate;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeUpdateResult;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeAnchor;
import net.knightsandkings.knk.core.domain.roads.RoadNodeUpdate;
import net.knightsandkings.knk.core.domain.roads.RoadProfile;
import net.knightsandkings.knk.core.domain.roads.RoadProfileUpsert;
import net.knightsandkings.knk.core.domain.roads.RoadSeed;
import net.knightsandkings.knk.core.domain.roads.RoadSeedCreate;
import net.knightsandkings.knk.core.domain.roads.RoadSurvey;
import net.knightsandkings.knk.core.domain.roads.RoadSurveyCreate;
import net.knightsandkings.knk.core.domain.roads.RoadTile;
import net.knightsandkings.knk.core.domain.roads.RoadTileUpsertResult;
import net.knightsandkings.knk.core.roads.build.TileBuildResult;

/**
 * Road navigation writes (knk-web-api's road controllers, docs/specs/navigation/IMPLEMENTATION_PLAN.md
 * Phase 1.5 route table). Every route needs the plugin's API key (or a staff JWT with
 * {@code knk.admin.road} where the table says so). Bukkit-free.
 *
 * <p>Every future completes exceptionally with the {@code ApiException} as the cause on a non-2xx
 * answer: 400 {@code ValidationFailed}, 404 {@code NotFound}, 409 {@code Conflict} with an
 * {@code {error, message}} body the api-client's {@code RoadMapper.error} reads. The
 * {@code delete…} calls complete with {@code false} instead on a 404.
 */
public interface RoadNetworkCommandApi {

    /**
     * {@code PUT api/road-tiles/{world}/{x}/{z}/graph} (Phase 1 contract): upload a build. Node
     * keys are free strings; {@code existingId} only for nodes of this tile; Boundary nodes on the
     * border cells; no cross-tile edges (the API stitches). Phase 3 fills each edge's
     * {@code domainIds}/{@code regionIds} before the call.
     */
    CompletableFuture<RoadTileUpsertResult> upsertTileGraph(String world, int tileX, int tileZ, TileBuildResult build);

    /** {@code POST api/road-tiles/{world}/{x}/{z}/dirty}: creates the tile row if unknown, bumps its version. */
    CompletableFuture<RoadTile> markDirty(String world, int tileX, int tileZ);

    /** {@code POST api/road-profiles} (201). */
    CompletableFuture<RoadProfile> createProfile(RoadProfileUpsert profile);

    /** {@code PUT api/road-profiles/{id}}; {@code statsJson == null} keeps the stored stats (plan D5). */
    CompletableFuture<RoadProfile> updateProfile(int id, RoadProfileUpsert profile);

    /** {@code DELETE api/road-profiles/{id}}: true on 204, false on 404. Its edges lose the profile. */
    CompletableFuture<Boolean> deleteProfile(int id);

    /**
     * {@code POST api/road-surveys} (201) with {@code X-Acting-User-Id} so the API records who
     * walked it ({@code actingUserId} null = not sent; Phase 1 decision 9).
     */
    CompletableFuture<RoadSurvey> createSurvey(RoadSurveyCreate survey, Integer actingUserId);

    /** {@code POST api/road-seeds} (201). */
    CompletableFuture<RoadSeed> createSeed(RoadSeedCreate seed);

    /** {@code DELETE api/road-seeds/{id}}: true on 204, false on 404. */
    CompletableFuture<Boolean> deleteSeed(int id);

    /** {@code PUT api/road-nodes/{id}}: name, kind, lock (Phase 1 decision 7: locks unless told not to). */
    CompletableFuture<RoadNode> updateNode(int id, RoadNodeUpdate update);

    /** {@code POST api/road-nodes/anchor} (201): a Manual + Locked Anchor node. */
    CompletableFuture<RoadNode> createAnchor(RoadNodeAnchor anchor);

    /** {@code POST api/road-nodes/merge}: moves {@code mergeNodeId}'s edges onto {@code keepNodeId} and deletes it. */
    CompletableFuture<RoadNode> mergeNodes(int keepNodeId, int mergeNodeId);

    /** {@code POST api/road-nodes/{id}/prune}: removes an endpoint's dead end and leaves a Pruned tombstone. */
    CompletableFuture<RoadNode> pruneNode(int id);

    /** {@code DELETE api/road-nodes/{id}/prune}: deletes a Pruned or PrunedEdge tombstone; true on 204, false on 404. */
    CompletableFuture<Boolean> unpruneNode(int id);

    /**
     * {@code POST api/road-edges/prune}: removes detected edges for good (one transaction) - each leaves a
     * PrunedEdge tombstone the builder respects; junctions and dead ends left without edges are deleted.
     */
    CompletableFuture<RoadEdgePruneResult> pruneEdges(List<Integer> edgeIds);

    /** {@code POST api/road-edges} (201): an admin-walked Recorded edge (Phase 1 decision 8). */
    CompletableFuture<RoadEdge> recordEdge(RoadEdgeRecord edge);

    /** {@code PUT api/road-edges/{id}}: street label (optionally propagated), profile, cost, flags. */
    CompletableFuture<RoadEdgeUpdateResult> updateEdge(int id, RoadEdgeUpdate update);

    /** {@code DELETE api/road-edges/{id}}: true on 204, false on 404. */
    CompletableFuture<Boolean> deleteEdge(int id);
}
