package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.LootboxDtos;
import net.knightsandkings.knk.api.mapper.LootboxMapper;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import net.knightsandkings.knk.core.lootbox.KnkLootboxOdds;
import net.knightsandkings.knk.core.lootbox.KnkLootboxRuntimeConfig;
import net.knightsandkings.knk.core.lootbox.KnkLootboxSpawn;
import net.knightsandkings.knk.core.lootbox.KnkLootboxToken;
import net.knightsandkings.knk.core.ports.api.LootboxesQueryApi;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/** Reads of the lootbox runtime (docs/specs/lootboxes/DESIGN.md §3.3); every call carries the plugin's service key. */
public class LootboxesQueryApiImpl extends BaseApiImpl implements LootboxesQueryApi {

    public LootboxesQueryApiImpl(
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
    public CompletableFuture<KnkLootboxRuntimeConfig> getRuntimeConfig() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/LootboxSpawns/runtime-config";
            try {
                return LootboxMapper.toCore(parse(get(url), LootboxDtos.RuntimeConfigDto.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to read the lootbox runtime config", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<KnkLootboxSpawn>> getActive() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/LootboxSpawns/active";
            try {
                return LootboxMapper.toSpawns(parse(get(url), new TypeReference<List<LootboxDtos.SpawnDto>>() {}, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to read the active lootboxes", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<KnkLootboxClaimResult>> getPending(int userId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/LootboxClaims/pending?userId=" + userId;
            try {
                return LootboxMapper.toClaims(parse(get(url), new TypeReference<List<LootboxDtos.ClaimResultDto>>() {}, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to read pending lootbox claims for user " + userId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<KnkLootboxToken>> getUndeliveredTokens(int userId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/LootboxTokens/undelivered?userId=" + userId;
            try {
                return LootboxMapper.toTokens(parse(get(url), new TypeReference<List<LootboxDtos.TokenDto>>() {}, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to read undelivered lootbox tokens for user " + userId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<KnkLootboxOdds> getOdds(int lootboxTypeId, Integer boxStars) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/LootboxTypes/" + lootboxTypeId + "/odds" + (boxStars != null ? "?boxStars=" + boxStars : "");
            try {
                return LootboxMapper.toCore(parse(get(url), LootboxDtos.OddsDto.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to read the odds of lootbox type " + lootboxTypeId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Map<UUID, String>> getTokenStatuses(List<UUID> tokens) {
        return CompletableFuture.supplyAsync(() -> {
            if (tokens == null || tokens.isEmpty()) {
                return Map.<UUID, String>of();
            }
            String url = baseUrl + "/LootboxTokens/status";
            try {
                String json = objectMapper.writeValueAsString(new LootboxDtos.TokenStatusRequestDto(List.copyOf(tokens)));
                Request request = newRequest(url)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Accept", "application/json")
                        .post(RequestBody.create(json, MediaType.get("application/json")))
                        .build();
                return LootboxMapper.toStatuses(parse(execute(request, url), new TypeReference<List<LootboxDtos.TokenStatusDto>>() {}, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to read the status of " + tokens.size() + " lootbox token(s)", e);
            }
        }, executor);
    }
}
