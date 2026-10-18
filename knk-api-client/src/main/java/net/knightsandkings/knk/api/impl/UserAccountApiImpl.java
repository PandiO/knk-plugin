package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.ChangePasswordRequestDto;
import net.knightsandkings.knk.api.dto.CreateUserRequestDto;
import net.knightsandkings.knk.api.dto.CreateUserResponseDto;
import net.knightsandkings.knk.api.dto.DuplicateCheckResponseDto;
import net.knightsandkings.knk.api.dto.LinkCodeResponseDto;
import net.knightsandkings.knk.api.dto.MergeAccountsRequestDto;
import net.knightsandkings.knk.api.dto.UserResponseDto;
import net.knightsandkings.knk.api.dto.ValidateLinkCodeResponseDto;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.UserAccountApi;
import okhttp3.OkHttpClient;

/**
 * Implementation of UserAccountApi.
 * Provides HTTP client integration for user account management endpoints.
 */
public class UserAccountApiImpl extends BaseApiImpl implements UserAccountApi {

    public UserAccountApiImpl(
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
    public CompletableFuture<Object> createUser(Object request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = objectMapper.writeValueAsString(request);
                String url = baseUrl + "/Users";
                String response = postJson(url, json);
                CreateUserResponseDto wrapped = parse(response, CreateUserResponseDto.class, url);
                if (wrapped != null && wrapped.user() != null) {
                    return wrapped.user();
                }
                return parse(response, UserResponseDto.class, url);
            } catch (IOException | ApiException ex) {
                throw new RuntimeException("Failed to create user", ex);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Object> checkDuplicate(String uuid, String username) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Build query parameters
                String url = baseUrl + "/Users/check-duplicate?uuid=" + encodeParam(uuid) + "&username=" + encodeParam(username);
                String response = postJson(url, "{}"); // Empty body for POST
                return parse(response, DuplicateCheckResponseDto.class, url);
            } catch (IOException | ApiException ex) {
                throw new RuntimeException("Failed to check duplicate", ex);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Object> generateLinkCode(Integer userId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Request body with userId
                String requestBody = objectMapper.writeValueAsString(java.util.Map.of("userId", userId));
                String url = baseUrl + "/Users/generate-link-code";
                String response = postJson(url, requestBody);
                return parse(response, LinkCodeResponseDto.class, url);
            } catch (IOException | ApiException ex) {
                throw new RuntimeException("Failed to generate link code", ex);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Object> validateLinkCode(String code) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = baseUrl + "/Users/validate-link-code/" + encodeParam(code);
                String response = postJson(url, "{}"); // Empty body for POST
                return parse(response, ValidateLinkCodeResponseDto.class, url);
            } catch (IOException | ApiException ex) {
                throw new RuntimeException("Failed to validate link code", ex);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Object> mergeAccounts(Object request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = objectMapper.writeValueAsString(request);
                String url = baseUrl + "/Users/merge";
                String response = postJson(url, json);
                return parse(response, UserResponseDto.class, url);
            } catch (IOException | ApiException ex) {
                throw new RuntimeException("Failed to merge accounts", ex);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> changePassword(Integer userId, Object request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = objectMapper.writeValueAsString(request);
                String url = baseUrl + "/Users/" + userId + "/change-password";
                putJson(url, json);
                return null;
            } catch (IOException | ApiException ex) {
                throw new RuntimeException("Failed to change password", ex);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> updateEmail(Integer userId, String newEmail) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = objectMapper.writeValueAsString(java.util.Map.of("newEmail", newEmail));
                String url = baseUrl + "/Users/" + userId + "/update-email";
                putJson(url, json);
                return null;
            } catch (IOException | ApiException ex) {
                throw new RuntimeException("Failed to update email", ex);
            }
        }, executor);
    }

    /**
     * Helper to URL-encode parameters for safe query string building.
     */
    private String encodeParam(String param) {
        try {
            return java.net.URLEncoder.encode(param, "UTF-8");
        } catch (Exception ex) {
            return param;
        }
    }
}
