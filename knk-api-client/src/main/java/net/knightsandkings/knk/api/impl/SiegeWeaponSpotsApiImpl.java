package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.SiegeWeaponSpotsApi;
import okhttp3.OkHttpClient;

/**
 * Siege weapons phase 2 (PandiO/knk-workspace#783): {@code GET api/SiegeScenarios/{id}/teams} and
 * {@code POST api/SiegeScenarios/{id}/weapon-spots}. Both pass with the plugin key ({@code DashboardReads} and
 * {@code RequireServiceOrPermission(knk.siege.admin.manage)} on the API side).
 */
public class SiegeWeaponSpotsApiImpl extends BaseApiImpl implements SiegeWeaponSpotsApi {

    public SiegeWeaponSpotsApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper,
                                   AuthProvider authProvider, ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<List<Team>> listTeams(int scenarioId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/SiegeScenarios/" + scenarioId + "/teams";
            try {
                JsonNode body = parse(get(url), JsonNode.class, url);
                List<Team> teams = new ArrayList<>();
                if (body != null && body.isArray()) {
                    for (JsonNode team : body) {
                        teams.add(new Team(team.path("id").asInt(), text(team, "name"), text(team, "clanName"),
                                text(team, "role")));
                    }
                }
                return teams;
            } catch (ApiException e) {
                if (e.getStatusCode() == 404) return null;
                throw e;
            } catch (IOException e) {
                throw new ApiException(url, "IO error fetching the siege scenario's teams", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<CreatedSpot> createSpot(int scenarioId, NewSpot spot) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/SiegeScenarios/" + scenarioId + "/weapon-spots";
            ObjectNode body = objectMapper.createObjectNode();
            body.put("siegeScenarioId", scenarioId);
            body.put("siegeTeamId", spot.teamId());
            body.put("name", spot.name());
            body.put("minX", spot.bounds().minX());
            body.put("minY", spot.bounds().minY());
            body.put("minZ", spot.bounds().minZ());
            body.put("maxX", spot.bounds().maxX());
            body.put("maxY", spot.bounds().maxY());
            body.put("maxZ", spot.bounds().maxZ());
            if (spot.wgRegionId() != null) body.put("wgRegionId", spot.wgRegionId());
            body.put("facing", spot.facing().apiName());
            body.put("sizeClass", spot.sizeClass().apiName());
            try {
                JsonNode created = parse(postJson(url, objectMapper.writeValueAsString(body)), JsonNode.class, url);
                return new CreatedSpot(created.path("id").asInt(), text(created, "name"), text(created, "facing"),
                        text(created, "sizeClass"));
            } catch (IOException e) {
                throw new ApiException(url, "IO error creating the siege weapon spot", e);
            }
        }, executor);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
