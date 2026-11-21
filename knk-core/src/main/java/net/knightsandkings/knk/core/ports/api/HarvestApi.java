package net.knightsandkings.knk.core.ports.api;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.production.harvest.HarvestCatalog;

/**
 * Player harvesting, game server side (PandiO/knk-workspace#134; the API contract of #685, knk-web-api
 * {@code HarvestController}). Both calls use the plugin key. While {@code Production:Enabled} is off the catalogue is
 * empty with {@code featureEnabled} false and a report is answered 409 ({@code ApiException} with status 409).
 */
public interface HarvestApi {

    /** One entry of a report: one player's breaks under one rule in one area at one fortune level. */
    record ReportEntry(String idempotencyKey, int userId, int harvestRuleId, int productionAreaId, int breaks,
                       int fortuneLevel, Instant harvestedAt) {
    }

    /** The answer per entry: Applied, Replayed or Rejected (with {@code error}). */
    record EntryResult(String idempotencyKey, String status, String error, Integer itemBlueprintId, long workerUnits,
                       int regenSeconds, double poolAfter, double poolCapacity) {

        public boolean booked() {
            return "Applied".equalsIgnoreCase(status) || "Replayed".equalsIgnoreCase(status);
        }

        public boolean replayed() {
            return "Replayed".equalsIgnoreCase(status);
        }
    }

    record ReportResult(boolean featureEnabled, List<EntryResult> entries) {
        public ReportResult {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    /** {@code GET api/Harvest/catalog}. */
    CompletableFuture<HarvestCatalog> catalog();

    /** {@code POST api/Harvest/reports}. */
    CompletableFuture<ReportResult> report(List<ReportEntry> entries);
}
