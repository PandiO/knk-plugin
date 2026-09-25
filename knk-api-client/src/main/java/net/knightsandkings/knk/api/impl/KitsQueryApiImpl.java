package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.KitAvailabilityDto;
import net.knightsandkings.knk.api.dto.KitDto;
import net.knightsandkings.knk.api.dto.PagedQueryDto;
import net.knightsandkings.knk.api.dto.PagedResultDto;
import net.knightsandkings.knk.api.mapper.KitsMapper;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.item.KnkKit;
import net.knightsandkings.knk.core.domain.item.KnkKitAvailability;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.KitsQueryApi;
import okhttp3.OkHttpClient;

/**
 * Read-only implementation for the Kit catalog (docs/specs/kits/IMPLEMENTATION_PLAN.md §4) -
 * {@code getById}/{@code search}/{@code getAvailableForUser} only, mirroring
 * {@code ItemBlueprintsQueryApiImpl}'s port shape but built on {@link BaseApiImpl} like the
 * newer {@code PermissionsApiImpl}.
 */
public class KitsQueryApiImpl extends BaseApiImpl implements KitsQueryApi {

    private static final String BASE_ENDPOINT = "/Kits";
    private static final String SEARCH_ENDPOINT = "/Kits/search";
    private static final String AVAILABLE_ENDPOINT = "/Kits/available";

    public KitsQueryApiImpl(
            String baseUrl,
            OkHttpClient httpClient,
            ObjectMapper objectMapper,
            AuthProvider authProvider,
            ExecutorService executor,
            boolean debugLogging
    ) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<Page<KnkKit>> search(PagedQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + SEARCH_ENDPOINT;
            try {
                PagedQueryDto dto = new PagedQueryDto(
                        query.pageNumber(),
                        query.pageSize(),
                        query.searchTerm(),
                        query.sortBy(),
                        query.sortDescending(),
                        query.filters()
                );
                String requestBody = objectMapper.writeValueAsString(dto);
                String responseJson = postJson(url, requestBody);
                PagedResultDto<KitDto> result = parse(responseJson, new TypeReference<PagedResultDto<KitDto>>() {}, url);
                return KitsMapper.mapPage(result);
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to search kits", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<KnkKit> getById(int id) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + BASE_ENDPOINT + "/" + id;
            try {
                String responseJson = get(url);
                KitDto dto = parse(responseJson, KitDto.class, url);
                return KitsMapper.toCore(dto);
            } catch (ApiException ex) {
                if (ex.getStatusCode() == 404) {
                    return null;
                }
                throw new RuntimeException("Failed to get kit by id " + id, ex);
            } catch (IOException e) {
                throw new RuntimeException("Failed to get kit by id " + id, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<KnkKitAvailability>> getAvailableForUser(int userId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + AVAILABLE_ENDPOINT + "?userId=" + userId;
            try {
                String responseJson = get(url);
                List<KitAvailabilityDto> dtos = parse(responseJson, new TypeReference<List<KitAvailabilityDto>>() {}, url);
                return KitsMapper.mapAvailabilityList(dtos);
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to get available kits for user " + userId, e);
            }
        }, executor);
    }
}
