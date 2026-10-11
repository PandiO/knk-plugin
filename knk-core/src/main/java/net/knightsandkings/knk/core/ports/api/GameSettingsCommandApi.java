package net.knightsandkings.knk.core.ports.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkWorldRuntime;

/** Write port on the web API's global Game Settings (docs/specs/game-settings/DESIGN.md §3.6). */
public interface GameSettingsCommandApi {

    /**
     * {@code PUT /api/GameSettings/runtime-worlds}: the worlds this server has loaded, so the Game
     * Settings page can list them and add a settings block for a new one.
     *
     * @return the settings after the report (with a block for every reported world)
     */
    CompletableFuture<KnkGameSettings> reportRuntimeWorlds(List<KnkWorldRuntime> worlds);
}
