package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.RoadEdgeDto;
import net.knightsandkings.knk.api.dto.RoadEdgeUpdateResultDto;
import net.knightsandkings.knk.api.dto.RoadNodeDto;
import net.knightsandkings.knk.api.dto.RoadNodeMergeDto;
import net.knightsandkings.knk.api.dto.RoadProfileDto;
import net.knightsandkings.knk.api.dto.RoadSeedDto;
import net.knightsandkings.knk.api.dto.RoadSurveyDto;
import net.knightsandkings.knk.api.dto.RoadTileDto;
import net.knightsandkings.knk.api.dto.RoadTileUpsertResultDto;
import net.knightsandkings.knk.api.mapper.RoadMapper;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
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
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.RoadNetworkCommandApi;
import net.knightsandkings.knk.core.roads.build.TileBuildResult;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

import static net.knightsandkings.knk.api.impl.RoadNetworkQueryApiImpl.EDGES_ENDPOINT;
import static net.knightsandkings.knk.api.impl.RoadNetworkQueryApiImpl.PROFILES_ENDPOINT;
import static net.knightsandkings.knk.api.impl.RoadNetworkQueryApiImpl.SEEDS_ENDPOINT;
import static net.knightsandkings.knk.api.impl.RoadNetworkQueryApiImpl.SURVEYS_ENDPOINT;
import static net.knightsandkings.knk.api.impl.RoadNetworkQueryApiImpl.TILES_ENDPOINT;
import static net.knightsandkings.knk.api.impl.RoadNetworkQueryApiImpl.encode;
import static net.knightsandkings.knk.api.impl.RoadNetworkQueryApiImpl.tileGraphUrl;

/**
 * knk-web-api's road controllers, write side (road navigation, KNG-27; plan Phase 1.5 route
 * table). Every call carries the plugin's API key through the {@link AuthProvider}. A refusal
 * completes exceptionally with the {@link ApiException} (400 ValidationFailed / 404 NotFound /
 * 409 Conflict, body readable through {@code RoadMapper.error}) as the cause; the deletes complete
 * with {@code false} on a 404 instead.
 */
public class RoadNetworkCommandApiImpl extends BaseApiImpl implements RoadNetworkCommandApi {
    static final String NODES_ENDPOINT = "/road-nodes";

    public RoadNetworkCommandApiImpl(
        String baseUrl,
        OkHttpClient httpClient,
        ObjectMapper objectMapper,
        AuthProvider authProvider,
        ExecutorService executor,
        boolean debugLogging
    ) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    /** Body-less POST (the dirty mark). */
    private String postEmpty(String url) throws ApiException, IOException {
        Request request = newRequest(url)
            .addHeader("Accept", "application/json")
            .post(RequestBody.create(new byte[0], null))
            .build();
        if (debugLogging) LOGGER.info("API Request: POST " + url);
        return execute(request, url);
    }

    /** POST with the acting-user header the survey route audits (Phase 1 decision 9). */
    private String postJsonAs(String url, String json, Integer actingUserId) throws ApiException, IOException {
        Request.Builder builder = newRequest(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(RequestBody.create(json, MediaType.get("application/json")));
        if (actingUserId != null) {
            builder.header(UsersCommandApiImpl.ACTING_USER_HEADER, String.valueOf(actingUserId));
        }
        if (debugLogging) {
            LOGGER.info("API Request: POST " + url);
            LOGGER.info("  Body: " + snippet(json));
        }
        return execute(builder.build(), url);
    }

    /** DELETE where 204 is true and 404 is false. */
    private CompletableFuture<Boolean> deleteOrNotFound(String url, Supplier<String> what) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                delete(url);
                return true;
            } catch (ApiException e) {
                if (e.getStatusCode() == 404) {
                    return false;
                }
                throw new RuntimeException("Failed to delete " + what.get(), e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to delete " + what.get(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<RoadTileUpsertResult> upsertTileGraph(String world, int tileX, int tileZ,
                                                                   TileBuildResult build) {
        return CompletableFuture.supplyAsync(() -> {
            String url = tileGraphUrl(baseUrl, world, tileX, tileZ);
            try {
                String json = objectMapper.writeValueAsString(RoadMapper.toUpsertDto(build));
                return RoadMapper.mapUpsertResult(parse(putJson(url, json), RoadTileUpsertResultDto.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to upload road tile (" + tileX + ", " + tileZ + ") of world "
                    + world, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<RoadTile> markDirty(String world, int tileX, int tileZ) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + TILES_ENDPOINT + "/" + encode(world) + "/" + tileX + "/" + tileZ + "/dirty";
            try {
                return RoadMapper.mapTile(parse(postEmpty(url), RoadTileDto.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to mark road tile (" + tileX + ", " + tileZ + ") of world "
                    + world + " dirty", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<RoadProfile> createProfile(RoadProfileUpsert profile) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + PROFILES_ENDPOINT;
            try {
                String json = objectMapper.writeValueAsString(RoadMapper.toProfileUpsertDto(profile));
                return RoadMapper.mapProfile(parse(postJson(url, json), RoadProfileDto.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to create road profile " + profile.name(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<RoadProfile> updateProfile(int id, RoadProfileUpsert profile) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + PROFILES_ENDPOINT + "/" + id;
            try {
                String json = objectMapper.writeValueAsString(RoadMapper.toProfileUpsertDto(profile));
                return RoadMapper.mapProfile(parse(putJson(url, json), RoadProfileDto.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to update road profile " + id, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> deleteProfile(int id) {
        return deleteOrNotFound(baseUrl + PROFILES_ENDPOINT + "/" + id, () -> "road profile " + id);
    }

    @Override
    public CompletableFuture<RoadSurvey> createSurvey(RoadSurveyCreate survey, Integer actingUserId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + SURVEYS_ENDPOINT;
            try {
                String json = objectMapper.writeValueAsString(RoadMapper.toSurveyCreateDto(survey));
                return RoadMapper.mapSurvey(parse(postJsonAs(url, json, actingUserId), RoadSurveyDto.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to store the road survey of world " + survey.world(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<RoadSeed> createSeed(RoadSeedCreate seed) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + SEEDS_ENDPOINT;
            try {
                String json = objectMapper.writeValueAsString(RoadMapper.toSeedCreateDto(seed));
                return RoadMapper.mapSeed(parse(postJson(url, json), RoadSeedDto.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to create a road seed in world " + seed.world(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> deleteSeed(int id) {
        return deleteOrNotFound(baseUrl + SEEDS_ENDPOINT + "/" + id, () -> "road seed " + id);
    }

    @Override
    public CompletableFuture<RoadNode> updateNode(int id, RoadNodeUpdate update) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + NODES_ENDPOINT + "/" + id;
            try {
                String json = objectMapper.writeValueAsString(RoadMapper.toNodeUpdateDto(update));
                return RoadMapper.mapNode(parse(putJson(url, json), RoadNodeDto.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to update road node " + id, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<RoadNode> createAnchor(RoadNodeAnchor anchor) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + NODES_ENDPOINT + "/anchor";
            try {
                String json = objectMapper.writeValueAsString(RoadMapper.toAnchorDto(anchor));
                return RoadMapper.mapNode(parse(postJson(url, json), RoadNodeDto.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to create a road anchor in world " + anchor.world(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<RoadNode> mergeNodes(int keepNodeId, int mergeNodeId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + NODES_ENDPOINT + "/merge";
            try {
                String json = objectMapper.writeValueAsString(new RoadNodeMergeDto(keepNodeId, mergeNodeId));
                return RoadMapper.mapNode(parse(postJson(url, json), RoadNodeDto.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to merge road node " + mergeNodeId + " into " + keepNodeId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<RoadNode> pruneNode(int id) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + NODES_ENDPOINT + "/" + id + "/prune";
            try {
                return RoadMapper.mapNode(parse(postJson(url, "{}"), RoadNodeDto.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to prune road node " + id, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> unpruneNode(int id) {
        return deleteOrNotFound(baseUrl + NODES_ENDPOINT + "/" + id + "/prune", () -> "pruned road node " + id);
    }

    @Override
    public CompletableFuture<RoadEdge> recordEdge(RoadEdgeRecord edge) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + EDGES_ENDPOINT;
            try {
                String json = objectMapper.writeValueAsString(RoadMapper.toEdgeRecordDto(edge));
                return RoadMapper.mapEdge(parse(postJson(url, json), RoadEdgeDto.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to record a road edge in world " + edge.world(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<RoadEdgeUpdateResult> updateEdge(int id, RoadEdgeUpdate update) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + EDGES_ENDPOINT + "/" + id;
            try {
                String json = objectMapper.writeValueAsString(RoadMapper.toEdgeUpdateDto(update));
                return RoadMapper.mapEdgeUpdateResult(parse(putJson(url, json), RoadEdgeUpdateResultDto.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to update road edge " + id, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> deleteEdge(int id) {
        return deleteOrNotFound(baseUrl + EDGES_ENDPOINT + "/" + id, () -> "road edge " + id);
    }
}
