package net.knightsandkings.knk.core.ports.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkWorldRuntime;

/** Write port on the web API's global Game Settings (docs/specs/game-settings/DESIGN.md §3.6). */
public interface GameSettingsCommandApi {

    /**
     * {@code PUT /api/GameSettings/runtime-worlds}: the worlds this server has loaded, so the Game
     * Settings page can list them and add a settings block for a new one. The report replaces only
     * this server's worlds (KNG-118), so several game servers can report side by side.
     *
     * @param serverId this game server's id ({@code game-settings.server-id}); null = the API's "default"
     * @return the settings after the report (with a block for every reported world)
     */
    CompletableFuture<KnkGameSettings> reportRuntimeWorlds(String serverId, List<KnkWorldRuntime> worlds);

    /** {@link #reportRuntimeWorlds(String, List)} as the API's "default" server. */
    default CompletableFuture<KnkGameSettings> reportRuntimeWorlds(List<KnkWorldRuntime> worlds) {
        return reportRuntimeWorlds(null, worlds);
    }
}
