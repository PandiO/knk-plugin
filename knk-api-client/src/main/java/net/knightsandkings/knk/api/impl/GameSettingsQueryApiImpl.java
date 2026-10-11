package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.GameSettingsDto;
import net.knightsandkings.knk.api.mapper.GameSettingsMapper;
import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.GameSettingsQueryApi;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * {@code GET /api/GameSettings}: the /spawn destination (docs/specs/teleport/DESIGN.md §3.6) and
 * everything else the plugin applies (docs/specs/game-settings/DESIGN.md).
 */
public class GameSettingsQueryApiImpl extends BaseApiImpl implements GameSettingsQueryApi {

    static final String ENDPOINT = "/GameSettings";

    public GameSettingsQueryApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper,
                                    AuthProvider authProvider, ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<KnkGameSettings> get() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT;
            try {
                return toCore(parse(get(url), GameSettingsDto.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to read the game settings", e);
            }
        }, executor);
    }

    static KnkGameSettings toCore(GameSettingsDto dto) {
        return GameSettingsMapper.toCore(dto);
    }
}
