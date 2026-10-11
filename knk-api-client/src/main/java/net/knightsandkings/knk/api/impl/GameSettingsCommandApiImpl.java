package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.GameSettingsDto;
import net.knightsandkings.knk.api.mapper.GameSettingsMapper;
import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkWorldRuntime;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.GameSettingsCommandApi;
import okhttp3.OkHttpClient;

/**
 * {@code PUT /api/GameSettings/runtime-worlds} (docs/specs/game-settings/DESIGN.md §3.6). The API
 * wants the plugin key (or a web user with {@code knk.admin.config}) for it since KNG-52.
 */
public class GameSettingsCommandApiImpl extends BaseApiImpl implements GameSettingsCommandApi {

    static final String ENDPOINT = GameSettingsQueryApiImpl.ENDPOINT + "/runtime-worlds";

    public GameSettingsCommandApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper,
                                      AuthProvider authProvider, ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<KnkGameSettings> reportRuntimeWorlds(List<KnkWorldRuntime> worlds) {
        List<GameSettingsDto.RuntimeWorld> body = (worlds == null ? List.<KnkWorldRuntime>of() : worlds).stream()
            .map(GameSettingsMapper::toDto).toList();
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT;
            try {
                String json = objectMapper.writeValueAsString(new GameSettingsDto.RuntimeWorldsUpdate(body));
                return GameSettingsMapper.toCore(parse(putJson(url, json), GameSettingsDto.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to report the loaded worlds", e);
            }
        }, executor);
    }
}
