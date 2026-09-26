package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.UserIgnoreDto;
import net.knightsandkings.knk.api.mapper.UsersMapper;
import net.knightsandkings.knk.core.domain.users.UserIgnore;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.UserIgnoresApi;
import okhttp3.OkHttpClient;

/**
 * knk-web-api's UserIgnoresController ({@code api/users/{id}/ignores}; KNG-18 Phase 2). A refused
 * add comes back as a 400/404/409 with an {@code error} code, which maps to an
 * {@link UserIgnoresApi.AddResult} instead of an exception.
 */
public class UserIgnoresApiImpl extends BaseApiImpl implements UserIgnoresApi {
    // baseUrl is expected to already include /api
    private static final String ENDPOINT = "/users/%d/ignores";

    public UserIgnoresApiImpl(
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
    public CompletableFuture<List<UserIgnore>> list(int userId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT.formatted(userId);
            try {
                List<UserIgnoreDto> dtos = parse(get(url), new TypeReference<List<UserIgnoreDto>>() {}, url);
                return dtos.stream().map(UsersMapper::mapUserIgnore).toList();
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to list ignores of user " + userId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<AddResult> add(int userId, int ignoredUserId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT.formatted(userId) + "/" + ignoredUserId;
            try {
                putJson(url, "{}");
                return AddResult.IGNORED;
            } catch (ApiException e) {
                AddResult refused = refusalOf(e);
                if (refused != null) {
                    return refused;
                }
                throw new RuntimeException("Failed to ignore user " + ignoredUserId + " for user " + userId, e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to ignore user " + ignoredUserId + " for user " + userId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> remove(int userId, int ignoredUserId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT.formatted(userId) + "/" + ignoredUserId;
            try {
                delete(url);
                return null;
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to unignore user " + ignoredUserId + " for user " + userId, e);
            }
        }, executor);
    }

    /** The rule that refused an add, from the response's {@code error} code; null for any other failure. */
    private AddResult refusalOf(ApiException e) {
        int status = e.getStatusCode();
        if (status != 400 && status != 404 && status != 409) {
            return null;
        }
        String code = errorCode(e.getResponseBody());
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "SelfIgnore" -> AddResult.SELF_IGNORE;
            case "CannotIgnoreStaff" -> AddResult.CANNOT_IGNORE_STAFF;
            case "IgnoreLimitReached" -> AddResult.LIMIT_REACHED;
            case "UserNotFound" -> AddResult.USER_NOT_FOUND;
            default -> null;
        };
    }

    private String errorCode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body).get("error");
            return node != null && node.isTextual() ? node.asText() : null;
        } catch (IOException ex) {
            return null;
        }
    }
}
