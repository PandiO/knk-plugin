package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.SiegeWeaponUnlocksApi;
import net.knightsandkings.knk.core.siege.unlocks.SiegeUnlockOffer;
import net.knightsandkings.knk.core.siege.unlocks.SiegeUnlockPurchaseResult;
import net.knightsandkings.knk.core.siege.unlocks.SiegeUnlocksOverview;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Siege weapon unlocks with coins (siege shop DESIGN § 6.2.3-6.2.4; PandiO/knk-workspace#869) over knk-web-api's
 * {@code GET /api/SiegeWeaponUnlocks/by-user/{userId}} and {@code POST /api/SiegeWeaponUnlocks/purchase}, with the
 * plugin key. A 409 refusal ({@code { code, message }}) is a {@link SiegeUnlockPurchaseResult.Refused}.
 */
public class SiegeWeaponUnlocksApiImpl extends BaseApiImpl implements SiegeWeaponUnlocksApi {

    private static final String BY_USER = "/SiegeWeaponUnlocks/by-user/";
    private static final String PURCHASE = "/SiegeWeaponUnlocks/purchase";
    private static final MediaType JSON = MediaType.get("application/json");

    public SiegeWeaponUnlocksApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper, AuthProvider authProvider,
                                     ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    /** The wire shape of {@code SiegeWeaponUnlocksOverviewDto}. */
    record OverviewDto(
            @JsonProperty("userId") Integer userId,
            @JsonProperty("coins") Long coins,
            @JsonProperty("titleName") String titleName,
            @JsonProperty("offers") List<OfferDto> offers) {
    }

    record OfferDto(
            @JsonProperty("templateId") Integer templateId,
            @JsonProperty("name") String name,
            @JsonProperty("kind") String kind,
            @JsonProperty("unlockPrice") Integer unlockPrice,
            @JsonProperty("minTitleLevel") Integer minTitleLevel,
            @JsonProperty("minTitleName") String minTitleName,
            @JsonProperty("state") String state) {
    }

    record ResultDto(
            @JsonProperty("templateName") String templateName,
            @JsonProperty("coinsPaid") Long coinsPaid,
            @JsonProperty("alreadyUnlocked") Boolean alreadyUnlocked,
            @JsonProperty("coins") Long coins) {
    }

    @Override
    public CompletableFuture<SiegeUnlocksOverview> overview(int userId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + BY_USER + userId;
            try {
                if (debugLogging) LOGGER.info("API Request: GET " + url);
                OverviewDto dto = parse(execute(newRequest(url).get().build(), url), new TypeReference<OverviewDto>() { }, url);
                return toCore(dto, userId);
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to load the siege weapon unlocks of user " + userId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<SiegeUnlockPurchaseResult> purchase(int userId, int templateId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + PURCHASE;
            try {
                String body = objectMapper.writeValueAsString(Map.of("templateId", templateId, "userId", userId));
                if (debugLogging) LOGGER.info("API Request: POST " + url);
                ResultDto dto = parse(execute(newRequest(url).post(RequestBody.create(body, JSON)).build(), url),
                        new TypeReference<ResultDto>() { }, url);
                return new SiegeUnlockPurchaseResult.Unlocked(dto.templateName(),
                        dto.coinsPaid() == null ? 0 : dto.coinsPaid(),
                        Boolean.TRUE.equals(dto.alreadyUnlocked()),
                        dto.coins() == null ? 0 : dto.coins());
            } catch (ApiException e) {
                if (e.getStatusCode() == 409) return refusal(e.getResponseBody());
                if (e.getStatusCode() == 404) return new SiegeUnlockPurchaseResult.Refused("NotFound", "That siege weapon type no longer exists.");
                throw new RuntimeException("Failed to unlock siege weapon " + templateId + " for user " + userId, e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to unlock siege weapon " + templateId + " for user " + userId, e);
            }
        }, executor);
    }

    SiegeUnlockPurchaseResult.Refused refusal(String body) {
        try {
            JsonNode node = objectMapper.readTree(body == null ? "{}" : body);
            return new SiegeUnlockPurchaseResult.Refused(text(node, "code"), text(node, "message"));
        } catch (IOException e) {
            return new SiegeUnlockPurchaseResult.Refused(null, null);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    static SiegeUnlocksOverview toCore(OverviewDto dto, int userId) {
        if (dto == null) return new SiegeUnlocksOverview(userId, 0, null, List.of());
        List<SiegeUnlockOffer> offers = new ArrayList<>();
        if (dto.offers() != null) {
            for (OfferDto o : dto.offers()) {
                if (o == null || o.templateId() == null) continue;
                offers.add(new SiegeUnlockOffer(o.templateId(), o.name() == null ? "Siege weapon #" + o.templateId() : o.name(),
                        o.kind(), o.unlockPrice() == null ? 0 : o.unlockPrice(), o.minTitleLevel(), o.minTitleName(),
                        SiegeUnlockOffer.State.fromApi(o.state())));
            }
        }
        return new SiegeUnlocksOverview(dto.userId() == null ? userId : dto.userId(), dto.coins() == null ? 0 : dto.coins(),
                dto.titleName(), offers);
    }
}
