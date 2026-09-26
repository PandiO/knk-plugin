package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.core.ports.api.RegionsCommandApi;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

/**
 * Implementation of RegionsCommandApi for communicating with the Web API.
 * Handles region management operations.
 */
public class RegionsCommandApiImpl extends BaseApiImpl implements RegionsCommandApi {
    private static final Logger LOGGER = Logger.getLogger(RegionsCommandApiImpl.class.getName());
    private static final String REGIONS_ENDPOINT = "/Regions";

    public RegionsCommandApiImpl(
            String baseUrl,
            OkHttpClient httpClient,
            ObjectMapper objectMapper,
            AuthProvider authProvider,
            ExecutorService executor,
            boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<Boolean> renameRegion(String oldRegionId, String newRegionId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = baseUrl + REGIONS_ENDPOINT + "/rename?oldRegionId=" + 
                    urlEncode(oldRegionId) + "&newRegionId=" + urlEncode(newRegionId);
                
                Request request = newRequest(url)
                    .post(okhttp3.RequestBody.create("", null))
                    .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        LOGGER.warning("Failed to rename region: HTTP " + response.code() + " - " + response.message());
                        return false;
                    }
                    
                    String responseBody = response.body() != null ? response.body().string() : "";
                    boolean result = objectMapper.readValue(responseBody, Boolean.class);
                    LOGGER.info("Region rename request sent: " + oldRegionId + " -> " + newRegionId + ", result: " + result);
                    return result;
                }
            } catch (Exception e) {
                LOGGER.warning("Error renaming region: " + e.getMessage());
                e.printStackTrace();
                return false;
            }
        });
    }

    /**
     * URL encode a string for use in query parameters
     */
    private String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return value;
        }
    }
}
