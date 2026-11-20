package net.knightsandkings.knk.core.ports.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.production.transport.LaneDue;
import net.knightsandkings.knk.core.production.transport.LaneMeasurement;
import net.knightsandkings.knk.core.production.transport.RoadConnection;

/**
 * Transport lanes and road connections, game server side (PandiO/knk-workspace#681; the API contract of #79, knk-web-api
 * {@code TransportLanesController} and {@code ProductionStructuresController}). The lane list is empty while the API's
 * {@code Production:Enabled} is off. Failures carry the status code ({@code ApiException}).
 */
public interface TransportLanesApi {

    /** The API's answer to a report. */
    record ReportResult(int created, int updated) {
    }

    /** {@code GET api/TransportLanes/due?limit=}: the pairs to measure (Missing, then Stale). */
    CompletableFuture<List<LaneDue>> due(int limit);

    /** {@code POST api/TransportLanes/report} with at most 500 lanes. */
    CompletableFuture<ReportResult> report(List<LaneMeasurement> lanes);

    /** {@code PUT api/ProductionStructures/{id}/road-connection}. */
    CompletableFuture<Void> setRoadConnection(int structureId, RoadConnection state);
}
