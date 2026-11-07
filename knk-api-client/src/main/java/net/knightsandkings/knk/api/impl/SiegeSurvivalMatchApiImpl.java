package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MysteryBoxClaimRequestDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MysteryBoxForfeitRequestDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MysteryBoxRollDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MysteryBoxRollRequestDto;
import net.knightsandkings.knk.core.exception.ApiException;
import okhttp3.OkHttpClient;

/**
 * Siege Survival phase 4 (PandiO/knk-workspace#41, link S7a; DESIGN §8a, S34, S39, S59): the match-scoped Mystery Box
 * calls of knk-web-api, made with the plugin's service key. The plugin charges the price in its {@code MatchLedger}
 * first (charge id = roll id) and then {@link #roll rolls}: the API checks the match is in progress, the point belongs
 * to the match's scenario and the user is a participant, rolls once and stores the roll; the same roll id answers the
 * stored roll again (idempotent, so a retry after a network error never rolls twice). {@link #claim} records the one
 * delivery, {@link #forfeit} the forfeit (S59); both are idempotent and exclude each other. No balance comes back:
 * match currency never leaves the plugin (DESIGN §8).
 *
 * <p>A 409 answers {@code { "code": ..., "message": ... }}; {@link #conflictCode(ApiException)} reads the code:
 * {@link #ROLL_ID_REUSED} (same roll id, different body), {@link #MATCH_NOT_IN_PROGRESS}, {@link #PARTICIPANT_LEFT},
 * {@link #POOL_INVALID}, {@link #ROLL_FORFEITED} (claim after forfeit), {@link #ROLL_CLAIMED} (forfeit after claim).
 * A 404 (unknown match, point of another scenario, user not a participant, unknown roll) completes with {@code null}.
 */
public class SiegeSurvivalMatchApiImpl extends BaseApiImpl {

    static final String MATCHES_ENDPOINT = "/siege-survival/matches";

    public static final String ROLL_ID_REUSED = "RollIdReused";
    public static final String MATCH_NOT_IN_PROGRESS = "MatchNotInProgress";
    public static final String PARTICIPANT_LEFT = "ParticipantLeft";
    public static final String POOL_INVALID = "PoolInvalid";
    public static final String ROLL_FORFEITED = "RollForfeited";
    public static final String ROLL_CLAIMED = "RollClaimed";

    /** {@code deliveryKind} values of {@link #claim}. */
    public static final String DELIVERY_INVENTORY = "Inventory";
    public static final String DELIVERY_DROP = "Drop";

    /** {@code reason} values of {@link #forfeit}. */
    public static final String FORFEIT_TIMEOUT = "Timeout";
    public static final String FORFEIT_ROLLER_DIED = "RollerDied";
    public static final String FORFEIT_ROLLER_LEFT = "RollerLeft";
    public static final String FORFEIT_MATCH_ENDED = "MatchEnded";

    public SiegeSurvivalMatchApiImpl(
        String baseUrl,
        OkHttpClient httpClient,
        ObjectMapper objectMapper,
        AuthProvider authProvider,
        ExecutorService executor,
        boolean debugLogging
    ) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    /**
     * Rolls the box point once for {@code rollId} (at most 64 characters, the plugin's idempotency id), or answers the
     * stored roll when the same id with the same body was rolled before.
     *
     * @param priceCharged the match currency the plugin charged for this roll (audit value)
     * @return the stored roll, or null on a 404
     */
    public CompletableFuture<MysteryBoxRollDto> roll(long matchId, int pointId, String rollId, int userId, int priceCharged) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + MATCHES_ENDPOINT + "/" + matchId + "/mystery-box/" + pointId + "/roll";
            return nullOn404(url, () -> parse(post(url, new MysteryBoxRollRequestDto(rollId, userId, priceCharged)), MysteryBoxRollDto.class, url));
        }, executor);
    }

    /** Records the roll's one delivery ({@link #DELIVERY_INVENTORY} or {@link #DELIVERY_DROP}); a repeat answers the stored state. */
    public CompletableFuture<MysteryBoxRollDto> claim(long matchId, String rollId, String deliveryKind) {
        return CompletableFuture.supplyAsync(() -> {
            String url = rollUrl(matchId, rollId) + "/claim";
            return nullOn404(url, () -> parse(post(url, new MysteryBoxClaimRequestDto(deliveryKind)), MysteryBoxRollDto.class, url));
        }, executor);
    }

    /** Forfeits the roll (idempotent) for one of the {@code FORFEIT_*} reasons. */
    public CompletableFuture<MysteryBoxRollDto> forfeit(long matchId, String rollId, String reason) {
        return CompletableFuture.supplyAsync(() -> {
            String url = rollUrl(matchId, rollId) + "/forfeit";
            return nullOn404(url, () -> parse(post(url, new MysteryBoxForfeitRequestDto(reason)), MysteryBoxRollDto.class, url));
        }, executor);
    }

    /** The match's rolls, oldest first; null on a 404 (unknown match). */
    public CompletableFuture<List<MysteryBoxRollDto>> listRolls(long matchId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + MATCHES_ENDPOINT + "/" + matchId + "/mystery-box/rolls";
            return nullOn404(url, () -> parse(get(url), new TypeReference<List<MysteryBoxRollDto>>() { }, url));
        }, executor);
    }

    /**
     * The {@code code} of a 409 answer ({@link #ROLL_ID_REUSED} …), or null when the exception is not a 409 or carries
     * no code.
     */
    public String conflictCode(ApiException e) {
        if (e == null || e.getStatusCode() != 409 || e.getResponseBody() == null || e.getResponseBody().isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(e.getResponseBody());
            JsonNode code = node == null ? null : node.get("code");
            return code == null || code.isNull() ? null : code.asText();
        } catch (IOException ex) {
            return null;
        }
    }

    private String rollUrl(long matchId, String rollId) {
        return baseUrl + MATCHES_ENDPOINT + "/" + matchId + "/mystery-box/rolls/" + URLEncoder.encode(rollId, StandardCharsets.UTF_8);
    }

    /** POSTs the body as JSON; IO errors become an ApiException that keeps the IOException as its cause. */
    private String post(String url, Object body) {
        try {
            return postJson(url, objectMapper.writeValueAsString(body));
        } catch (IOException e) {
            throw new ApiException(url, "IO error calling the survival Mystery Box API", e);
        }
    }

    private interface Call<T> {
        T run() throws IOException;
    }

    private <T> T nullOn404(String url, Call<T> call) {
        try {
            return call.run();
        } catch (ApiException e) {
            if (e.getStatusCode() == 404) return null;
            throw e;
        } catch (IOException e) {
            throw new ApiException(url, "IO error calling the survival Mystery Box API", e);
        }
    }
}
