package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.GameSettingsDto;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkSpawnReference;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.GameSettingsQueryApi;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/** {@code GET /api/GameSettings} - the /spawn destination (docs/specs/teleport/DESIGN.md §3.6). */
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
        if (dto == null) {
            return null;
        }
        GameSettingsDto.LocationReference reference = dto.joinSpawnReference();
        KnkSpawnReference spawn = null;
        if (reference != null) {
            GameSettingsDto.LocationSnapshot snapshot = reference.location();
            KnkLocation location = snapshot == null ? null : new KnkLocation(snapshot.locationId(), snapshot.name(),
                    snapshot.x(), snapshot.y(), snapshot.z(), snapshot.yaw(), snapshot.pitch(), snapshot.world());
            spawn = new KnkSpawnReference(KnkSpawnReference.SourceType.parse(reference.sourceType()),
                    reference.sourceId() != null ? reference.sourceId() : 0, reference.displayLabel(), location);
        }
        return new KnkGameSettings(dto.joinSpawnMode(), spawn);
    }
}
