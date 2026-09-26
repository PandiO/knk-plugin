package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.PermissionGroupListItemDto;
import net.knightsandkings.knk.core.domain.permissions.PermissionGroupSummary;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.PermissionGroupsQueryApi;
import okhttp3.OkHttpClient;

public class PermissionGroupsQueryApiImpl extends BaseApiImpl implements PermissionGroupsQueryApi {

    public PermissionGroupsQueryApiImpl(
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
    public CompletableFuture<List<PermissionGroupSummary>> list() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/PermissionGroups";
            try {
                String json = get(url);
                List<PermissionGroupListItemDto> dtos = parse(json, new TypeReference<List<PermissionGroupListItemDto>>() {}, url);
                return dtos.stream()
                    .filter(d -> d.id() != null)
                    .map(PermissionGroupsQueryApiImpl::toSummary)
                    .collect(Collectors.toList());
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to list permission groups", e);
            }
        }, executor);
    }

    static PermissionGroupSummary toSummary(PermissionGroupListItemDto dto) {
        double multiplier = dto.salaryMultiplier() != null ? dto.salaryMultiplier() : 1.0;
        return new PermissionGroupSummary(dto.id(), dto.name(), dto.weight(), dto.isPremiumTier(), multiplier,
            dto.chatPrimaryColor(), dto.chatSecondaryColor(), dto.nameColor());
    }
}
