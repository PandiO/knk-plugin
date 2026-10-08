package net.knightsandkings.knk.core.domain.roads;

/**
 * One point of a survey walk's breadcrumb as stored with the survey (DESIGN §3.2). Mirrors the
 * web-api's {@code RoadBreadcrumbPointDto}. Bukkit-free.
 *
 * @param x      floor block x
 * @param y      floor block y
 * @param z      floor block z
 * @param onRoad whether the sample's floor was a road material of the surveyed profile
 */
public record RoadBreadcrumbPoint(int x, int y, int z, boolean onRoad) {
}
