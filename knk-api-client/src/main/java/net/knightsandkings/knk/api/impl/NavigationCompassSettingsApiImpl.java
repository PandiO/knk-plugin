package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.NavigationCompassSettingsDto;
import net.knightsandkings.knk.api.mapper.NavigationCompassSettingsMapper;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.navigation.compass.CompassSettings;
import net.knightsandkings.knk.core.ports.api.NavigationCompassSettingsApi;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/** {@code GET /api/navigation-settings/compass}: which item is the navigation compass and where it works (KNG-234). */
public class NavigationCompassSettingsApiImpl extends BaseApiImpl implements NavigationCompassSettingsApi {

    static final String ENDPOINT = "/navigation-settings/compass";

    public NavigationCompassSettingsApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper,
                                            AuthProvider authProvider, ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<CompassSettings> get() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT;
            try {
                return NavigationCompassSettingsMapper.toCore(parse(get(url), NavigationCompassSettingsDto.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to read the navigation compass settings", e);
            }
        }, executor);
    }
}
