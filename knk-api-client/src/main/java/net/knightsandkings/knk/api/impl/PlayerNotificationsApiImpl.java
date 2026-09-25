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
import net.knightsandkings.knk.api.dto.AcknowledgePlayerNotificationsDto;
import net.knightsandkings.knk.api.dto.PlayerNotificationDto;
import net.knightsandkings.knk.api.mapper.UsersMapper;
import net.knightsandkings.knk.core.domain.users.PlayerNotification;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.PlayerNotificationsApi;
import okhttp3.OkHttpClient;

public class PlayerNotificationsApiImpl extends BaseApiImpl implements PlayerNotificationsApi {
    // baseUrl is expected to already include /api
    private static final String ENDPOINT = "/PlayerNotifications";

    public PlayerNotificationsApiImpl(
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
    public CompletableFuture<List<PlayerNotification>> listPending() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/pending";
            try {
                List<PlayerNotificationDto> dtos = parse(get(url), new TypeReference<List<PlayerNotificationDto>>() {}, url);
                return dtos.stream().map(UsersMapper::mapPlayerNotification).toList();
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to list pending player notifications", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> acknowledge(Collection<Long> ids) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/acknowledge";
            try {
                postJson(url, objectMapper.writeValueAsString(new AcknowledgePlayerNotificationsDto(new ArrayList<>(ids))));
                return null;
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to acknowledge player notifications", e);
            }
        }, executor);
    }
}
