package net.knightsandkings.knk.core.map;

import java.util.List;

/**
 * Reads every online player for the overview map's player report (PandiO/knk-workspace#738, OVERVIEW_MAP_DESIGN § 7.1.2,
 * § 8). On Paper the snapshot must be taken on the main thread and stay cheap: it runs every
 * {@code map.reporting.interval-ticks} (§ 9.1).
 */
@FunctionalInterface
public interface OnlinePlayerSnapshotPort {

    List<OnlinePlayerPosition> snapshot();
}
