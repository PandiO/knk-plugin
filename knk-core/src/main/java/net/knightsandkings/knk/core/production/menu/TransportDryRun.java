package net.knightsandkings.knk.core.production.menu;

import java.util.List;

/**
 * The transport dry run of a production structure ({@code GET api/ProductionStructures/{id}/transport}): whether the
 * automatic transport would send an order now and why or why not. Nothing changes.
 */
public record TransportDryRun(int structureId, boolean featureEnabled, boolean automaticTransport, String roadConnection,
                              double outputUnits, Long outputMaxUnits, double threshold, long carrierCapacity,
                              int workersPresent, int workersTotal, List<String> warnings, List<String> triggerReasons,
                              List<String> heldReasons, String wouldCreate, int openOrders) {

    public TransportDryRun {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        triggerReasons = triggerReasons == null ? List.of() : List.copyOf(triggerReasons);
        heldReasons = heldReasons == null ? List.of() : List.copyOf(heldReasons);
    }
}
