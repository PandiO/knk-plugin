package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.TempRegionReleasesApi;
import net.knightsandkings.knk.core.regions.TempRegionRelease;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * PandiO/knk-workspace#269: {@code GET api/WorldTasks/temp-region-releases?serverId=} and
 * {@code POST api/WorldTasks/temp-region-releases/{id}/settle} with the plugin service key.
 */
public class TempRegionReleasesApiImpl extends BaseApiImpl implements TempRegionReleasesApi {
    private static final String ENDPOINT = "/WorldTasks/temp-region-releases";

    public TempRegionReleasesApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper,
                                     AuthProvider authProvider, ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<List<TempRegionRelease>> listDue(String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + (serverId == null || serverId.isBlank() ? ""
                    : "?serverId=" + URLEncoder.encode(serverId.trim(), StandardCharsets.UTF_8));
            try {
                JsonNode root = objectMapper.readTree(get(url));
                List<TempRegionRelease> releases = new ArrayList<>();
                if (root != null && root.isArray()) {
                    for (JsonNode node : root) {
                        int id = node.path("id").asInt(0);
                        int taskId = node.path("worldTaskId").asInt(0);
                        String regionId = text(node, "regionId");
                        if (id <= 0 || taskId <= 0 || regionId == null) {
                            continue;
                        }
                        releases.add(new TempRegionRelease(id, taskId, regionId, text(node, "world"), text(node, "reason")));
                    }
                }
                return releases;
            } catch (ApiException e) {
                throw e;
            } catch (IOException e) {
                throw new RuntimeException("Failed to list the temp regions released for removal", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> settle(int releaseId, TempRegionRelease.Outcome outcome, String serverId, String detail) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/" + releaseId + "/settle";
            try {
                ObjectNode body = objectMapper.createObjectNode();
                body.put("outcome", outcome.wire());
                if (serverId != null) body.put("serverId", serverId);
                if (detail != null) body.put("detail", detail.length() > 200 ? detail.substring(0, 200) : detail);
                postJson(url, objectMapper.writeValueAsString(body));
                return Boolean.TRUE;
            } catch (ApiException e) {
                if (e.getStatusCode() == 404) {
                    return Boolean.FALSE;
                }
                throw e;
            } catch (IOException e) {
                throw new RuntimeException("Failed to settle temp region release " + releaseId, e);
            }
        }, executor);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }
}
