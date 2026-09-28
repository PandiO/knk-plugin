package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.TeleportChargeDtos;
import net.knightsandkings.knk.api.dto.TeleportDestinationDto;
import net.knightsandkings.knk.api.mapper.TeleportDestinationsMapper;
import net.knightsandkings.knk.core.domain.teleport.KnkTeleportDestination;
import net.knightsandkings.knk.core.domain.teleport.TeleportChargeResult;
import net.knightsandkings.knk.core.domain.teleport.TeleportRefundResult;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.TeleportDestinationsCommandApi;
import net.knightsandkings.knk.core.ports.api.TeleportDestinationsQueryApi;
import okhttp3.OkHttpClient;

/**
 * knk-web-api's TeleportDestinationsController (docs/specs/teleport/DESIGN.md §3.7.3, KNG-17
 * Phase 5): the per-player warp list and the charge/refund calls. A charge the server refuses
 * (4xx with {@code {error, message}}) completes normally as a refused {@link TeleportChargeResult};
 * no answer at all (network error, 5xx) completes exceptionally so the caller can retry with the
 * same idempotency key.
 */
public class TeleportDestinationsApiImpl extends BaseApiImpl implements TeleportDestinationsQueryApi, TeleportDestinationsCommandApi {
    // baseUrl is expected to already include /api
    static final String ENDPOINT = "/teleport-destinations";

    public TeleportDestinationsApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper,
                                       AuthProvider authProvider, ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<List<KnkTeleportDestination>> listForUser(int userId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "?userId=" + userId;
            try {
                List<TeleportDestinationDto> dtos = parse(get(url), new TypeReference<List<TeleportDestinationDto>>() {}, url);
                return dtos == null ? List.<KnkTeleportDestination>of()
                    : dtos.stream().map(TeleportDestinationsMapper::map).filter(Objects::nonNull).toList();
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to load the teleport destinations of user " + userId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<TeleportChargeResult> chargeWarp(int domainId, int userId, String idempotencyKey,
                                                             boolean bypassRequirements, boolean bypassCost) {
        return charge(baseUrl + ENDPOINT + "/" + domainId + "/charge",
            new TeleportChargeDtos.ChargeRequest(userId, idempotencyKey, bypassRequirements, bypassCost),
            "the warp to domain " + domainId + " for user " + userId);
    }

    @Override
    public CompletableFuture<TeleportChargeResult> chargeRequestFee(int userId, int amountCoins, String idempotencyKey,
                                                                   Integer otherUserId) {
        return charge(baseUrl + ENDPOINT + "/request-fee",
            new TeleportChargeDtos.RequestFee(userId, amountCoins, idempotencyKey, otherUserId),
            "the teleport request fee of user " + userId);
    }

    @Override
    public CompletableFuture<TeleportRefundResult> refund(int userId, String idempotencyKey, String reason) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/refund";
            try {
                String body = objectMapper.writeValueAsString(new TeleportChargeDtos.RefundRequest(userId, idempotencyKey, reason));
                TeleportChargeDtos.RefundResult dto = parse(postJson(url, body), TeleportChargeDtos.RefundResult.class, url);
                return new TeleportRefundResult(Boolean.TRUE.equals(dto.refunded()), dto.currency(),
                    dto.amount() != null ? dto.amount() : 0L, dto.newBalance(), Boolean.TRUE.equals(dto.replayed()));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to refund teleport charge " + idempotencyKey + " of user " + userId, e);
            }
        }, executor);
    }

    private CompletableFuture<TeleportChargeResult> charge(String url, Object request, String what) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = postJson(url, objectMapper.writeValueAsString(request));
                TeleportChargeDtos.ChargeResult dto = parse(json, TeleportChargeDtos.ChargeResult.class, url);
                return TeleportChargeResult.allowed(dto.currency(),
                    dto.charged() != null ? dto.charged() : 0L,
                    dto.newBalance() != null ? dto.newBalance() : 0L,
                    Boolean.TRUE.equals(dto.replayed()),
                    TeleportDestinationsMapper.map(dto.destination()));
            } catch (ApiException e) {
                if (e.getStatusCode() >= 400 && e.getStatusCode() < 500) {
                    return refusal(e);
                }
                throw new RuntimeException("Failed to charge " + what, e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to charge " + what, e);
            }
        }, executor);
    }

    /** A 4xx answer: the server's {@code {error, message}}, or a generic refusal when the body isn't one. */
    private TeleportChargeResult refusal(ApiException e) {
        String body = e.getResponseBody();
        if (body != null && !body.isBlank()) {
            try {
                TeleportChargeDtos.Error error = objectMapper.readValue(body, TeleportChargeDtos.Error.class);
                if (error != null && (error.error() != null || error.message() != null)) {
                    return TeleportChargeResult.refused(error.error(), error.message());
                }
            } catch (IOException ignored) {
                // Not JSON (e.g. a plain-text 400): fall through.
            }
        }
        LOGGER.warning("Teleport charge refused with HTTP " + e.getStatusCode() + " and no reason: " + e.getMessage());
        return TeleportChargeResult.refused("Http" + e.getStatusCode(), "You can't teleport there right now.");
    }
}
