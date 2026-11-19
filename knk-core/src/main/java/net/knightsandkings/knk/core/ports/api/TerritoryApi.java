package net.knightsandkings.knk.core.ports.api;

import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.territory.TerritoryInputs;
import net.knightsandkings.knk.core.territory.TerritoryReport;

/**
 * Territory V1, game server side (PandiO/knk-workspace#689; the API contract of #326): {@code GET api/Territory/inputs}
 * and {@code PUT api/Territory/report} (plugin service key; the API answers 409 while {@code Production:Enabled} is off).
 */
public interface TerritoryApi {

    CompletableFuture<TerritoryInputs> fetchInputs();

    /** Fails when the API refuses or can't be reached; a 409 (feature off on the API) fails with its status code. */
    CompletableFuture<TerritoryReport.Result> report(TerritoryReport report);
}
