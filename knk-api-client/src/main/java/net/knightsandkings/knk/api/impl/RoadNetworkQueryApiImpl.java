package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.PagedQueryDto;
import net.knightsandkings.knk.api.dto.PagedResultDto;
import net.knightsandkings.knk.api.dto.RoadEdgeDto;
import net.knightsandkings.knk.api.dto.RoadNetworkMetaDto;
import net.knightsandkings.knk.api.dto.RoadProfileDto;
import net.knightsandkings.knk.api.dto.RoadSeedDto;
import net.knightsandkings.knk.api.dto.RoadSeedLocationDto;
import net.knightsandkings.knk.api.dto.RoadSurveyDto;
import net.knightsandkings.knk.api.dto.RoadTileDto;
import net.knightsandkings.knk.api.dto.RoadTileGraphDto;
import net.knightsandkings.knk.api.dto.RoadTileProposalDto;
import net.knightsandkings.knk.api.mapper.RoadMapper;
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
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.RoadNetworkQueryApi;
import okhttp3.OkHttpClient;

/**
 * knk-web-api's road controllers, read side (road navigation, KNG-27; plan Phase 1.5 route table).
 * A failed call completes exceptionally with the {@link ApiException} as the cause; a 304 on the
 * tile graph download is a {@link Conditional#notModified()} result, not a failure.
 */
public class RoadNetworkQueryApiImpl extends BaseApiImpl implements RoadNetworkQueryApi {
    // baseUrl is expected to already include /api
    static final String TILES_ENDPOINT = "/road-tiles";
    static final String NETWORK_ENDPOINT = "/road-network";
    static final String PROFILES_ENDPOINT = "/road-profiles";
    static final String SURVEYS_ENDPOINT = "/road-surveys";
    static final String SEEDS_ENDPOINT = "/road-seeds";
    static final String EDGES_ENDPOINT = "/road-edges";

    public RoadNetworkQueryApiImpl(
        String baseUrl,
        OkHttpClient httpClient,
        ObjectMapper objectMapper,
        AuthProvider authProvider,
        ExecutorService executor,
        boolean debugLogging
    ) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    static String tileGraphUrl(String baseUrl, String world, int tileX, int tileZ) {
        return baseUrl + TILES_ENDPOINT + "/" + encode(world) + "/" + tileX + "/" + tileZ + "/graph";
    }

    @Override
    public CompletableFuture<List<RoadTile>> tiles(String world) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + TILES_ENDPOINT + "?world=" + encode(world);
            try {
                List<RoadTileDto> dtos = parse(get(url), new TypeReference<List<RoadTileDto>>() {}, url);
                return dtos == null ? List.<RoadTile>of() : dtos.stream().map(RoadMapper::mapTile).toList();
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to list the road tiles of world " + world, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Conditional<RoadTileGraph>> tileGraph(String world, int tileX, int tileZ, String etag) {
        return CompletableFuture.supplyAsync(() -> {
            String url = tileGraphUrl(baseUrl, world, tileX, tileZ);
            try {
                ConditionalResponse response = getConditional(url, etag);
                if (response.notModified()) {
                    return Conditional.<RoadTileGraph>notModified(response.etag());
                }
                RoadTileGraph graph = RoadMapper.mapTileGraph(parse(response.body(), RoadTileGraphDto.class, url));
                return Conditional.modified(graph, response.etag() != null ? response.etag() : graph.etag());
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to download road tile (" + tileX + ", " + tileZ + ") of world "
                    + world, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<TileProposal>> proposal(String world, int tileX, int tileZ) {
        return CompletableFuture.supplyAsync(() -> {
            String url = proposalUrl(baseUrl, world, tileX, tileZ);
            try {
                return Optional.ofNullable(RoadMapper.mapProposal(parse(get(url), RoadTileProposalDto.class, url)));
            } catch (ApiException e) {
                if (e.getStatusCode() == 404) {
                    return Optional.<TileProposal>empty();
                }
                throw new RuntimeException("Failed to load the proposal of road tile (" + tileX + ", " + tileZ + ")", e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load the proposal of road tile (" + tileX + ", " + tileZ + ")", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<RoadTileProposalSummary>> proposals(String world) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + TILES_ENDPOINT + "/proposals?world=" + encode(world);
            try {
                List<RoadTileProposalDto> dtos = parse(get(url), new TypeReference<List<RoadTileProposalDto>>() {}, url);
                return dtos == null ? List.<RoadTileProposalSummary>of() : dtos.stream().map(RoadMapper::mapProposalSummary).toList();
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to list the road proposals of world " + world, e);
            }
        }, executor);
    }

    /** {@code {base}/road-tiles/{world}/{x}/{z}/proposal}. */
    static String proposalUrl(String baseUrl, String world, int tileX, int tileZ) {
        return baseUrl + TILES_ENDPOINT + "/" + encode(world) + "/" + tileX + "/" + tileZ + "/proposal";
    }

    @Override
    public CompletableFuture<RoadNetworkMeta> meta(String world) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + NETWORK_ENDPOINT + "/meta?world=" + encode(world);
            try {
                return RoadMapper.mapMeta(parse(get(url), RoadNetworkMetaDto.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to load the road network meta of world " + world, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<RoadProfile>> profiles() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + PROFILES_ENDPOINT;
            try {
                return RoadMapper.mapProfiles(parse(get(url), new TypeReference<List<RoadProfileDto>>() {}, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to list the road profiles", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<RoadProfile> profile(int id) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + PROFILES_ENDPOINT + "/" + id;
            try {
                return RoadMapper.mapProfile(parse(get(url), RoadProfileDto.class, url));
            } catch (ApiException e) {
                if (e.getStatusCode() == 404) {
                    return null;
                }
                throw new RuntimeException("Failed to load road profile " + id, e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load road profile " + id, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<RoadSurvey>> surveys(String world) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + SURVEYS_ENDPOINT + "?world=" + encode(world);
            try {
                List<RoadSurveyDto> dtos = parse(get(url), new TypeReference<List<RoadSurveyDto>>() {}, url);
                return dtos == null ? List.<RoadSurvey>of() : dtos.stream().map(RoadMapper::mapSurvey).toList();
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to list the road surveys of world " + world, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<RoadSeed>> seeds(String world) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + SEEDS_ENDPOINT + "?world=" + encode(world);
            try {
                List<RoadSeedDto> dtos = parse(get(url), new TypeReference<List<RoadSeedDto>>() {}, url);
                return dtos == null ? List.<RoadSeed>of() : dtos.stream().map(RoadMapper::mapSeed).toList();
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to list the road seeds of world " + world, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<RoadSeedLocation>> seedLocations(String world, int minX, int minZ, int maxX, int maxZ) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + NETWORK_ENDPOINT + "/seed-locations?world=" + encode(world)
                + "&minX=" + minX + "&minZ=" + minZ + "&maxX=" + maxX + "&maxZ=" + maxZ;
            try {
                List<RoadSeedLocationDto> dtos = parse(get(url), new TypeReference<List<RoadSeedLocationDto>>() {}, url);
                return dtos == null ? List.<RoadSeedLocation>of()
                    : dtos.stream().map(RoadMapper::mapSeedLocation).toList();
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to list the seed locations of world " + world, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Page<RoadEdge>> searchEdges(PagedQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + EDGES_ENDPOINT + "/search";
            try {
                PagedQueryDto body = query == null
                    ? new PagedQueryDto(1, 50, null, null, false, null)
                    : new PagedQueryDto(query.pageNumber(), query.pageSize(), query.searchTerm(), query.sortBy(),
                        query.sortDescending(), query.filters());
                PagedResultDto<RoadEdgeDto> page = parse(postJson(url, objectMapper.writeValueAsString(body)),
                    new TypeReference<PagedResultDto<RoadEdgeDto>>() {}, url);
                List<RoadEdge> edges = RoadMapper.mapEdges(page.items());
                return new Page<>(edges,
                    page.totalCount() == null ? edges.size() : page.totalCount(),
                    page.pageNumber() == null ? body.pageNumber() : page.pageNumber(),
                    page.pageSize() == null ? body.pageSize() : page.pageSize());
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to search road edges", e);
            }
        }, executor);
    }
}
