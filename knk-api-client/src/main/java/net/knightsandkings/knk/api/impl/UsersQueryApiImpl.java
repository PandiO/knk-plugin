package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.GroupMembershipDto;
import net.knightsandkings.knk.api.dto.PagedQueryDto;
import net.knightsandkings.knk.api.dto.PagedResultDto;
import net.knightsandkings.knk.api.dto.UserDto;
import net.knightsandkings.knk.api.dto.UserListDto;
import net.knightsandkings.knk.api.dto.UserSummaryDto;
import net.knightsandkings.knk.api.mapper.UsersMapper;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.users.GroupMembershipSummary;
import net.knightsandkings.knk.core.domain.users.UserDetail;
import net.knightsandkings.knk.core.domain.users.UserListItem;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.UsersQueryApi;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class UsersQueryApiImpl extends BaseApiImpl implements UsersQueryApi{
    private static final Logger LOGGER = Logger.getLogger(UsersQueryApiImpl.class.getName());

    private static final String USERS_ENDPOINT = "/users";

    public UsersQueryApiImpl(
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
    public CompletableFuture<UserDetail> getById(int id) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + USERS_ENDPOINT + "/" + id;
            long startTime = System.currentTimeMillis();

            try {
                
                // Build request
                Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .get();
                
                // Add auth header if provider is configured
                if (authProvider != null && authProvider.getAuthHeader() != null) {
                    requestBuilder.addHeader(
                        authProvider.getAuthHeaderName(),
                        authProvider.getAuthHeader()
                    );
                }

                Request request = requestBuilder.build();

                // Execute request
                try (Response response = httpClient.newCall(request).execute()) {
                    String responseBody = response.body().string();

                    if (debugLogging) {
                        long duration = System.currentTimeMillis() - startTime;
                        String snippet = responseBody.length() > 200
                            ? responseBody.substring(0, 200) + "..."
                            : responseBody;
                        LOGGER.info(String.format(
                            "GET %s completed in %d ms. Response: %s",
                            url,
                            duration,
                            snippet
                        ));
                    }

                    if (response.code() == 404) {
                        return null; // User not found
                    }

                    if (!response.isSuccessful()) {
                        throw new ApiException("API request failed with status code: " + response.code());
                    }

                    // Deserialize response
                    UserDto userDto = objectMapper.readValue(responseBody, UserDto.class);
                    return UsersMapper.mapUserDetail(userDto);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to fetch user by ID", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<UserSummary> getByUuid(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + USERS_ENDPOINT + "/uuid/" + uuid;
            long startTime = System.currentTimeMillis();

            try {
                
                // Build request
                Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .get();
                
                // Add auth header if provider is configured
                if (authProvider != null && authProvider.getAuthHeader() != null) {
                    requestBuilder.addHeader(
                        authProvider.getAuthHeaderName(),
                        authProvider.getAuthHeader()
                    );
                }

                Request request = requestBuilder.build();

                // Execute request
                try (Response response = httpClient.newCall(request).execute()) {
                    String responseBody = response.body().string();

                    if (debugLogging) {
                        long duration = System.currentTimeMillis() - startTime;
                        String snippet = responseBody.length() > 200
                            ? responseBody.substring(0, 200) + "..."
                            : responseBody;
                        LOGGER.info(String.format(
                            "GET %s completed in %d ms. Response: %s",
                            url,
                            duration,
                            snippet
                        ));
                    }

                    if (response.code() == 404) {
                        return null; // User not found
                    }

                    if (!response.isSuccessful()) {
                        throw new ApiException("API request failed with status code: " + response.code());
                    }

                    // Deserialize response
                    UserSummaryDto userDto = objectMapper.readValue(responseBody, UserSummaryDto.class);
                    return UsersMapper.mapUserSummary(userDto);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to fetch user by UUID", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<UserSummary> getByUsername(String username) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + USERS_ENDPOINT + "/username/" + username;
            long startTime = System.currentTimeMillis();

            try {
                
                // Build request
                Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .get();
                
                // Add auth header if provider is configured
                if (authProvider != null && authProvider.getAuthHeader() != null) {
                    requestBuilder.addHeader(
                        authProvider.getAuthHeaderName(),
                        authProvider.getAuthHeader()
                    );
                }

                Request request = requestBuilder.build();

                // Execute request
                try (Response response = httpClient.newCall(request).execute()) {
                    String responseBody = response.body().string();

                    if (debugLogging) {
                        long duration = System.currentTimeMillis() - startTime;
                        String snippet = responseBody.length() > 200
                            ? responseBody.substring(0, 200) + "..."
                            : responseBody;
                        LOGGER.info(String.format(
                            "GET %s completed in %d ms. Response: %s",
                            url,
                            duration,
                            snippet
                        ));
                    }

                    if (response.code() == 404) {
                        return null; // User not found
                    }

                    if (!response.isSuccessful()) {
                        throw new ApiException("API request failed with status code: " + response.code());
                    }

                    // Deserialize response
                    UserSummaryDto userDto = objectMapper.readValue(responseBody, UserSummaryDto.class);
                    return UsersMapper.mapUserSummary(userDto);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to fetch user by username", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Page<UserListItem>> search(PagedQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + USERS_ENDPOINT + "/search";
            long startTime = System.currentTimeMillis();
            
            try {
                // Convert domain PagedQuery to DTO
                PagedQueryDto dto = new PagedQueryDto(
                    query.pageNumber(),
                    query.pageSize(),
                    query.searchTerm(),
                    query.sortBy(),
                    query.sortDescending(),
                    query.filters()
                );
                
                String requestBody = objectMapper.writeValueAsString(dto);
                
                Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(RequestBody.create(requestBody, MediaType.get("application/json")));
                
                // Add auth header if provider is configured
                if (authProvider != null && authProvider.getAuthHeader() != null) {
                    requestBuilder.addHeader(
                        authProvider.getAuthHeaderName(),
                        authProvider.getAuthHeader()
                    );
                }
                
                Request request = requestBuilder.build();
                
                if (debugLogging) {
                    LOGGER.info("API Request: POST " + url);
                    LOGGER.info("  Body: " + requestBody);
                }
                
                try (Response response = httpClient.newCall(request).execute()) {
                    long latency = System.currentTimeMillis() - startTime;
                    
                    if (debugLogging) {
                        LOGGER.info(String.format("API Response: POST %s [%d] in %dms",
                            url, response.code(), latency));
                    }
                    
                    String responseBody = response.body() != null ? response.body().string() : "";
                    
                    if (debugLogging) {
                        LOGGER.info("  Response body: " + responseBody.substring(0, Math.min(responseBody.length(), 500)));
                    }
                    
                    if (!response.isSuccessful()) {
                        String snippet = responseBody.substring(0, Math.min(responseBody.length(), MAX_RESPONSE_SNIPPET_LENGTH));
                        if (responseBody.length() > MAX_RESPONSE_SNIPPET_LENGTH) {
                            snippet += "...";
                        }
                        
                        throw new ApiException(
                            url,
                            response.code(),
                            "Search users failed",
                            snippet
                        );
                    }
                    
                    if (responseBody.isEmpty()) {
                        throw new ApiException(url, response.code(), "Empty response body", "");
                    }
                    
                    try {
                        PagedResultDto<UserListDto> result = objectMapper.readValue(
                            responseBody,
                            new TypeReference<PagedResultDto<UserListDto>>() {}
                        );
                        return UsersMapper.mapUserListItemPage(result);
                    } catch (Exception parseEx) {
                        LOGGER.warning("Failed to parse response: " + responseBody);
                        throw new ApiException(
                            url,
                            response.code(),
                            "Failed to parse response: " + parseEx.getMessage(),
                            responseBody.substring(0, Math.min(responseBody.length(), MAX_RESPONSE_SNIPPET_LENGTH))
                        );
                    }
                }
            } catch (ApiException e) {
                throw e;
            } catch (IOException e) {
                ApiException apiEx = new ApiException(
                    url,
                    0,
                    "IO error during search users",
                    e.getClass().getSimpleName() + ": " + e.getMessage()
                );
                apiEx.initCause(e);
                throw apiEx;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<GroupMembershipSummary>> getGroupMemberships(int userId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/UserPermissionGroups?userId=" + userId;
            try {
                String json = get(url);
                List<GroupMembershipDto> dtos = parse(json, new TypeReference<List<GroupMembershipDto>>() {}, url);
                return dtos.stream()
                    .map(d -> new GroupMembershipSummary(d.permissionGroupId(), d.permissionGroupName(), d.weight(), d.isPremiumTier(), d.expiresAt(), d.isActive()))
                    .collect(Collectors.toList());
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to fetch group memberships for user " + userId, e);
            }
        }, executor);
    }
}
