package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.ItemInstancesApi;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/** {@code POST /ItemInstances/{id}/clone} (PandiO/knk-workspace#175, {@code /knk item more}). */
public class ItemInstancesApiImpl extends BaseApiImpl implements ItemInstancesApi {

    private static final String BASE_ENDPOINT = "/ItemInstances";

    public ItemInstancesApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper, AuthProvider authProvider,
                                ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<List<Long>> cloneAsync(long sourceId, int count, Integer ownerUserId, Integer actorUserId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + BASE_ENDPOINT + "/" + sourceId + "/clone";
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("count", count);
                body.put("ownerUserId", ownerUserId);
                Request.Builder builder = newRequest(url)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(RequestBody.create(objectMapper.writeValueAsString(body), MediaType.get("application/json")));
                if (actorUserId != null) {
                    builder.header(UsersCommandApiImpl.ACTING_USER_HEADER, String.valueOf(actorUserId));
                }
                JsonNode result = objectMapper.readTree(execute(builder.build(), url));
                List<Long> ids = new ArrayList<>();
                JsonNode array = result != null ? result.get("itemInstanceIds") : null;
                if (array != null && array.isArray()) {
                    array.forEach(id -> ids.add(id.asLong()));
                }
                return ids;
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to clone item instance " + sourceId, e);
            }
        }, executor);
    }
}
