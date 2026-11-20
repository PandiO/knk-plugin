package net.knightsandkings.knk.core.production.transport;

import java.time.Instant;

/**
 * One pair of the API's lane list ({@code GET api/TransportLanes/due}, knk-web-api P5, PandiO/knk-workspace#79): a
 * production structure and a warehouse of its effective town that accepts deliveries, or two warehouses of a town. A
 * lane counts in both directions, so only one direction is listed.
 *
 * @param from   the from structure's position; null when it has no Location
 * @param to     the to structure's position; null when it has no Location
 * @param reason {@code Missing} or {@code Stale}
 */
public record LaneDue(int fromStructureId, int toStructureId, String fromName, String toName, LanePosition from,
                      LanePosition to, String reason, Instant lastMeasuredAt) {

    /** {@code #4 North logger → #9 Docks}. */
    public String label() {
        return "#" + fromStructureId + (fromName == null || fromName.isBlank() ? "" : " " + fromName) + " → #"
                + toStructureId + (toName == null || toName.isBlank() ? "" : " " + toName);
    }
}
