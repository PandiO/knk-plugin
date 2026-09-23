package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.PermissionCheckResponseDto;
import net.knightsandkings.knk.api.dto.PermissionEffectiveResponseDto;
import net.knightsandkings.knk.api.mapper.PermissionsMapper;
import net.knightsandkings.knk.core.domain.permissions.EffectivePermissionSet;
import net.knightsandkings.knk.core.domain.permissions.PermissionCheckResult;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.PermissionsApi;
import okhttp3.OkHttpClient;

/**
 * Implementation of PermissionsApi. Provides HTTP client integration for
 * GET /api/users/{id}/permissions/check and .../permissions/effective.
 */
public class PermissionsApiImpl extends BaseApiImpl implements PermissionsApi {

    public PermissionsApiImpl(
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
    public CompletableFuture<PermissionCheckResult> check(int userId, String node) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/Users/" + userId + "/permissions/check?node=" + encode(node);
            try {
                String response = get(url);
                PermissionCheckResponseDto dto = parse(response, PermissionCheckResponseDto.class, url);
                return PermissionsMapper.mapCheckResult(dto);
            } catch (ApiException ex) {
                if (ex.getStatusCode() == 404) {
                    return null; // user not found
                }
                throw new RuntimeException("Failed to check permission " + node + " for user " + userId, ex);
            } catch (IOException ex) {
                throw new RuntimeException("Failed to check permission " + node + " for user " + userId, ex);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<EffectivePermissionSet> getEffective(int userId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/Users/" + userId + "/permissions/effective";
            try {
                String response = get(url);
                PermissionEffectiveResponseDto dto = parse(response, PermissionEffectiveResponseDto.class, url);
                return PermissionsMapper.mapEffectiveSet(dto);
            } catch (ApiException ex) {
                if (ex.getStatusCode() == 404) {
                    return null; // user not found
                }
                throw new RuntimeException("Failed to fetch effective permissions for user " + userId, ex);
            } catch (IOException ex) {
                throw new RuntimeException("Failed to fetch effective permissions for user " + userId, ex);
            }
        }, executor);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
