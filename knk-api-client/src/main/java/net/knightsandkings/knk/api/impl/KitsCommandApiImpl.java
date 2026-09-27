package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.GiveKitRequestDto;
import net.knightsandkings.knk.api.dto.KitClaimResultDto;
import net.knightsandkings.knk.api.dto.KitPurchaseResultDto;
import net.knightsandkings.knk.api.mapper.KitsMapper;
import net.knightsandkings.knk.core.domain.item.KnkKitClaimResult;
import net.knightsandkings.knk.core.domain.item.KnkKitPurchaseResult;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.KitsCommandApi;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * Write/action-side implementation for Kits (docs/specs/kits/IMPLEMENTATION_PLAN.md §4) -
 * claim/purchase/give/grant-first-join, mirroring {@code UsersCommandApiImpl}'s per-action
 * POST pattern.
 */
public class KitsCommandApiImpl extends BaseApiImpl implements KitsCommandApi {

    private static final String BASE_ENDPOINT = "/Kits";

    public KitsCommandApiImpl(
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
    public CompletableFuture<KnkKitClaimResult> claimAsync(int userId, int kitId) {
        // A kit with a cost is paid through the API's currency ledger keyed by this (KNG-21): a
        // resend of the same claim neither pays nor records it twice.
        String idempotencyKey = UUID.randomUUID().toString();
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + BASE_ENDPOINT + "/" + kitId + "/claim?userId=" + userId;
            try {
                Request request = newRequest(url)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    .post(RequestBody.create("{}", MediaType.get("application/json")))
                    .build();
                String responseJson = execute(request, url);
                KitClaimResultDto dto = parse(responseJson, KitClaimResultDto.class, url);
                return KitsMapper.toCore(dto);
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to claim kit " + kitId + " for user " + userId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<KnkKitPurchaseResult> purchaseAsync(int userId, int kitId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + BASE_ENDPOINT + "/" + kitId + "/purchase?userId=" + userId;
            try {
                String responseJson = postJson(url, "{}");
                KitPurchaseResultDto dto = parse(responseJson, KitPurchaseResultDto.class, url);
                return KitsMapper.toCore(dto);
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to purchase kit " + kitId + " for user " + userId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<KnkKitClaimResult> giveAsync(Integer actorUserId, int targetUserId, int kitId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + BASE_ENDPOINT + "/" + kitId + "/give";
            try {
                String bodyJson = objectMapper.writeValueAsString(new GiveKitRequestDto(targetUserId));
                // The API trusts this header only on a request carrying the plugin's API key
                // (KNG-22); it replaces the web user's JWT identity as the audited actor.
                Request.Builder builder = newRequest(url)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(RequestBody.create(bodyJson, MediaType.get("application/json")));
                if (actorUserId != null) {
                    builder.header(UsersCommandApiImpl.ACTING_USER_HEADER, String.valueOf(actorUserId));
                }
                String responseJson = execute(builder.build(), url);
                KitClaimResultDto dto = parse(responseJson, KitClaimResultDto.class, url);
                return KitsMapper.toCore(dto);
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to give kit " + kitId + " to user " + targetUserId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<KnkKitClaimResult>> grantFirstJoinKitsAsync(int userId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + BASE_ENDPOINT + "/grant-first-join?userId=" + userId;
            try {
                String responseJson = postJson(url, "{}");
                List<KitClaimResultDto> dtos = parse(responseJson, new TypeReference<List<KitClaimResultDto>>() {}, url);
                return KitsMapper.mapClaimResultList(dtos);
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to grant first-join kits for user " + userId, e);
            }
        }, executor);
    }
}
