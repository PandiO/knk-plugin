package net.knightsandkings.knk.core.ports.api;

import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;

/** Read-only port on the web API's global Game Settings ({@code GET /api/GameSettings}). */
public interface GameSettingsQueryApi {
    CompletableFuture<KnkGameSettings> get();
}
