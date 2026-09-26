package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.DiscoveryGrantRequestDto;
import net.knightsandkings.knk.api.dto.DiscoveryGrantResultDto;
import net.knightsandkings.knk.api.dto.DiscoveryProgressRowDto;
import net.knightsandkings.knk.api.dto.DiscoverySummaryDto;
import net.knightsandkings.knk.api.dto.KnownDiscoveryDto;
import net.knightsandkings.knk.api.dto.PagedQueryDto;
import net.knightsandkings.knk.api.dto.PagedResultDto;
import net.knightsandkings.knk.api.mapper.DiscoveriesMapper;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrantResult;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryProgressRow;
import net.knightsandkings.knk.core.domain.discovery.DiscoverySource;
import net.knightsandkings.knk.core.domain.discovery.DiscoverySummary;
import net.knightsandkings.knk.core.domain.discovery.KnownDiscovery;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.DiscoveriesApi;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * knk-web-api's DiscoveriesController (domain discovery, KNG-20). A failed call completes
 * exceptionally with the {@link ApiException} as the cause, so callers can tell a 4xx refusal from
 * an unreachable API.
 */
public class DiscoveriesApiImpl extends BaseApiImpl implements DiscoveriesApi {
    // baseUrl is expected to already include /api
    private static final String USERS_ENDPOINT = "/users";

    public DiscoveriesApiImpl(
        String baseUrl,
        OkHttpClient httpClient,
        ObjectMapper objectMapper,
        AuthProvider authProvider,
        ExecutorService executor,
        boolean debugLogging
    ) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    private String discoveriesUrl(int userId) {
        return baseUrl + USERS_ENDPOINT + "/" + userId + "/discoveries";
    }

    @Override
    public CompletableFuture<DiscoveryGrantResult> grant(int userId, Collection<String> wgRegionIds, DiscoverySource source) {
        return CompletableFuture.supplyAsync(() -> {
            String url = discoveriesUrl(userId);
            try {
                DiscoveryGrantRequestDto body = new DiscoveryGrantRequestDto(
                    new ArrayList<>(wgRegionIds), null, (source == null ? DiscoverySource.REGION_ENTER : source).apiName());
                String json = postJson(url, objectMapper.writeValueAsString(body));
                return DiscoveriesMapper.mapGrantResult(parse(json, DiscoveryGrantResultDto.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to grant discoveries for user " + userId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<KnownDiscovery>> known(int userId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = discoveriesUrl(userId) + "/known";
            try {
                List<KnownDiscoveryDto> dtos = parse(get(url), new TypeReference<List<KnownDiscoveryDto>>() {}, url);
                return dtos == null ? List.<KnownDiscovery>of() : dtos.stream().map(DiscoveriesMapper::mapKnown).toList();
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to load the known discoveries of user " + userId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Page<DiscoveryProgressRow>> progress(int userId, PagedQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            String url = discoveriesUrl(userId) + "/progress";
            try {
                PagedQueryDto body = query == null
                    ? new PagedQueryDto(1, 10, null, null, false, null)
                    : new PagedQueryDto(query.pageNumber(), query.pageSize(), query.searchTerm(), query.sortBy(),
                        query.sortDescending(), query.filters());
                PagedResultDto<DiscoveryProgressRowDto> page = parse(postJson(url, objectMapper.writeValueAsString(body)),
                    new TypeReference<PagedResultDto<DiscoveryProgressRowDto>>() {}, url);
                List<DiscoveryProgressRow> rows = page.items() == null ? List.of()
                    : page.items().stream().map(DiscoveriesMapper::mapProgressRow).toList();
                return new Page<>(rows,
                    page.totalCount() == null ? rows.size() : page.totalCount(),
                    page.pageNumber() == null ? body.pageNumber() : page.pageNumber(),
                    page.pageSize() == null ? body.pageSize() : page.pageSize());
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to load the discovery progress of user " + userId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<DiscoverySummary> summary(int userId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = discoveriesUrl(userId) + "/summary";
            try {
                return DiscoveriesMapper.mapSummary(parse(get(url), DiscoverySummaryDto.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to load the discovery summary of user " + userId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> reset(Integer actorUserId, int userId, int domainId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = discoveriesUrl(userId) + "/" + domainId;
            try {
                // The API trusts the actor header only on a request carrying the plugin's API key.
                Request.Builder builder = newRequest(url).delete();
                if (actorUserId != null) {
                    builder.header(UsersCommandApiImpl.ACTING_USER_HEADER, String.valueOf(actorUserId));
                }
                if (debugLogging) LOGGER.info("API Request: DELETE " + url);
                execute(builder.build(), url);
                return null;
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to reset discovery " + domainId + " of user " + userId, e);
            }
        }, executor);
    }
}
