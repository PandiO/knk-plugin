package net.knightsandkings.knk.core.ports.api;

import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.api.dto.MinecraftCatalogSnapshotDto;

/**
 * knk-web-api's {@code MinecraftCatalogController}, plugin side (KNG-200). Uses the plugin API key.
 * A failed call completes exceptionally with the
 * {@link net.knightsandkings.knk.core.exception.ApiException} (HTTP status and body) or the
 * {@link java.io.IOException} as the cause.
 */
public interface MinecraftCatalogApi {

    /** {@code PUT api/MinecraftCatalog/scans/{scanId}/snapshot}: hands the server's catalogue to a requested scan. */
    CompletableFuture<Void> putSnapshot(int scanId, MinecraftCatalogSnapshotDto snapshot);
}
