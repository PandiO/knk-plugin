package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.items.identity.ItemConversionLog;
import net.knightsandkings.knk.core.items.identity.MaterialBlueprintMap;
import net.knightsandkings.knk.core.ports.api.ItemIdentityApi;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * PandiO/knk-workspace#141: {@code GET api/ItemIdentity/material-map} and {@code POST api/ItemIdentity/conversion-counts}
 * (the latter with the plugin service key).
 */
public class ItemIdentityApiImpl extends BaseApiImpl implements ItemIdentityApi {
    private static final String ENDPOINT = "/ItemIdentity";

    public ItemIdentityApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper,
                               AuthProvider authProvider, ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<MaterialBlueprintMap> fetchMaterialMap() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return parseMap(objectMapper.readTree(get(baseUrl + ENDPOINT + "/material-map")));
            } catch (ApiException e) {
                throw e;
            } catch (IOException e) {
                throw new RuntimeException("Failed to fetch the item identity material map", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> reportConversionCounts(List<ItemConversionLog.Count> counts) {
        return CompletableFuture.runAsync(() -> {
            if (counts == null || counts.isEmpty()) {
                return;
            }
            try {
                ObjectNode body = objectMapper.createObjectNode();
                ArrayNode lines = body.putArray("counts");
                for (ItemConversionLog.Count count : counts) {
                    lines.addObject()
                            .put("source", count.source())
                            .put("outcome", count.outcome())
                            .put("materialKey", count.materialKey())
                            .put("count", count.count());
                }
                postJson(baseUrl + ENDPOINT + "/conversion-counts", objectMapper.writeValueAsString(body), false);
            } catch (ApiException e) {
                throw e;
            } catch (IOException e) {
                throw new RuntimeException("Failed to report item conversion counts", e);
            }
        }, executor);
    }

    /** The map from the API's JSON ({@code {"entries":[{"materialKey","blueprintId","shadow"}]}}); broken rows are skipped. */
    static MaterialBlueprintMap parseMap(JsonNode root) {
        List<MaterialBlueprintMap.Entry> entries = new ArrayList<>();
        JsonNode list = root != null ? root.path("entries") : null;
        if (list != null && list.isArray()) {
            for (JsonNode node : list) {
                String key = node.path("materialKey").asText(null);
                int id = node.path("blueprintId").asInt(0);
                if (key != null && !key.isBlank() && id > 0) {
                    entries.add(new MaterialBlueprintMap.Entry(key, id, node.path("shadow").asBoolean(false)));
                }
            }
        }
        return MaterialBlueprintMap.of(entries);
    }
}
