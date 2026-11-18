package net.knightsandkings.knk.core.ports.api;

import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.navigation.compass.CompassSettings;

/** Read-only port on the web API's navigation compass settings ({@code GET /api/navigation-settings/compass}, KNG-234). */
public interface NavigationCompassSettingsApi {
    CompletableFuture<CompassSettings> get();
}
