package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.LootboxDtos;
import net.knightsandkings.knk.api.mapper.LootboxMapper;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.lootbox.KnkLootboxArea;
import net.knightsandkings.knk.core.lootbox.KnkLootboxAreaDeleteResult;
import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import net.knightsandkings.knk.core.lootbox.KnkLootboxSpawn;
import net.knightsandkings.knk.core.lootbox.LootboxDeliveryMethod;
import net.knightsandkings.knk.core.lootbox.LootboxRejectedException;
import net.knightsandkings.knk.core.ports.api.LootboxesCommandApi;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Writes of the lootbox runtime (docs/specs/lootboxes/DESIGN.md §3.3). Every call carries the plugin's service key;
 * admin actions also name the staff member in {@code X-Acting-User-Id} (KNG-22). A 409 or 429 becomes a
 * {@link LootboxRejectedException} carrying the API's code (and for the daily limit its scope, limit and reset time),
 * so callers can tell "someone else got there first" from "the API is down".
 */
public class LootboxesCommandApiImpl extends BaseApiImpl implements LootboxesCommandApi {

    public LootboxesCommandApiImpl(
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
    public CompletableFuture<KnkLootboxSpawn> spawn(int areaId, String world, int x, int y, int z, String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/LootboxSpawns";
            LootboxDtos.SpawnRequestDto body = new LootboxDtos.SpawnRequestDto(areaId, world, x, y, z, serverId);
            return LootboxMapper.toCore(post(url, body, null, LootboxDtos.SpawnDto.class, "spawn a lootbox in area " + areaId));
        }, executor);
    }

    @Override
    public CompletableFuture<KnkLootboxSpawn> adminSpawn(Integer actorUserId, int typeId, Integer boxStars, String world, int x, int y, int z, String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/LootboxSpawns/admin";
            LootboxDtos.AdminSpawnRequestDto body = new LootboxDtos.AdminSpawnRequestDto(typeId, boxStars, world, x, y, z, serverId);
            return LootboxMapper.toCore(post(url, body, actorUserId, LootboxDtos.SpawnDto.class, "spawn a lootbox of type " + typeId));
        }, executor);
    }

    @Override
    public CompletableFuture<KnkLootboxSpawn> despawn(Integer actorUserId, int spawnId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/LootboxSpawns/" + spawnId + "/despawn";
            return LootboxMapper.toCore(post(url, null, actorUserId, LootboxDtos.SpawnDto.class, "despawn lootbox " + spawnId));
        }, executor);
    }

    @Override
    public CompletableFuture<KnkLootboxClaimResult> claim(int spawnId, UUID token, int userId, String idempotencyKey) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/LootboxSpawns/" + spawnId + "/claim";
            LootboxDtos.ClaimRequestDto body = new LootboxDtos.ClaimRequestDto(token, userId, idempotencyKey);
            return LootboxMapper.toCore(post(url, body, null, LootboxDtos.ClaimResultDto.class, "claim lootbox " + spawnId));
        }, executor);
    }

    @Override
    public CompletableFuture<Void> markDelivered(int claimId, LootboxDeliveryMethod method, String note, Integer userId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/LootboxClaims/" + claimId + "/delivered";
            LootboxDtos.DeliveredRequestDto body = new LootboxDtos.DeliveredRequestDto(method.wireValue(), note, userId);
            post(url, body, null, null, "confirm delivery of lootbox claim " + claimId);
            return null;
        }, executor);
    }

    @Override
    public CompletableFuture<KnkLootboxClaimResult> adminGive(Integer actorUserId, int userId, int typeId, Integer boxStars, String idempotencyKey) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/LootboxClaims/admin-give";
            LootboxDtos.AdminGiveRequestDto body = new LootboxDtos.AdminGiveRequestDto(userId, typeId, boxStars, idempotencyKey);
            return LootboxMapper.toCore(post(url, body, actorUserId, LootboxDtos.ClaimResultDto.class, "give a lootbox of type " + typeId));
        }, executor);
    }

    @Override
    public CompletableFuture<KnkLootboxArea> createAreaInGame(Integer actorUserId, String name, String world, String wgRegionId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/LootboxSpawnAreas/in-game";
            LootboxDtos.InGameAreaCreateDto body = new LootboxDtos.InGameAreaCreateDto(name, world, wgRegionId);
            return LootboxMapper.toCore(post(url, body, actorUserId, LootboxDtos.SpawnAreaDto.class, "create lootbox area " + name));
        }, executor);
    }

    @Override
    public CompletableFuture<KnkLootboxAreaDeleteResult> deleteAreaInGame(Integer actorUserId, int areaId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/LootboxSpawnAreas/" + areaId + "/in-game-delete";
            return LootboxMapper.toCore(post(url, null, actorUserId, LootboxDtos.InGameAreaDeleteResultDto.class, "delete lootbox area " + areaId));
        }, executor);
    }

    /** POSTs {@code body} (or {@code {}}) and parses the answer as {@code responseType} (null = ignore it). */
    private <T> T post(String url, Object body, Integer actorUserId, Class<T> responseType, String what) {
        try {
            String json = body == null ? "{}" : objectMapper.writeValueAsString(body);
            Request.Builder builder = newRequest(url)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(RequestBody.create(json, MediaType.get("application/json")));
            if (actorUserId != null) {
                builder.header(UsersCommandApiImpl.ACTING_USER_HEADER, String.valueOf(actorUserId));
            }
            if (debugLogging) {
                LOGGER.info("API Request: POST " + url);
                LOGGER.info("  Body: " + snippet(json));
            }
            String response = execute(builder.build(), url);
            return responseType == null || response.isEmpty() ? null : parse(response, responseType, url);
        } catch (ApiException e) {
            LootboxRejectedException rejected = toRejection(e);
            if (rejected != null) {
                throw rejected;
            }
            throw new RuntimeException("Failed to " + what, e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to " + what, e);
        }
    }

    /** A 409/429 as a {@link LootboxRejectedException}; null for any other failure. */
    LootboxRejectedException toRejection(ApiException e) {
        int status = e.getStatusCode();
        if (status != 409 && status != 429) {
            return null;
        }
        LootboxDtos.RejectionDto body = null;
        String raw = e.getResponseBody();
        if (raw != null && !raw.isBlank()) {
            try {
                body = objectMapper.readValue(raw, LootboxDtos.RejectionDto.class);
            } catch (Exception ignored) {
                // Not the expected shape (or truncated): keep the status alone.
            }
        }
        return new LootboxRejectedException(
                status,
                body != null ? body.code() : (status == 429 ? LootboxRejectedException.DAILY_LIMIT : null),
                body != null ? body.message() : null,
                body != null ? body.scope() : null,
                body != null ? body.limit() : null,
                body != null && body.resetsAt() != null ? body.resetsAt().toInstant() : null);
    }
}
