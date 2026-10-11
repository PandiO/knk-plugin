package net.knightsandkings.knk.core.domain.roads;

/**
 * One connected component of a world's road network (DESIGN §5.8), as the meta download counts
 * them. Mirrors the web-api's {@code RoadComponentDto}. Bukkit-free.
 *
 * @param id        component id (the smallest node id in it)
 * @param nodeCount nodes in the component
 */
public record RoadComponent(int id, int nodeCount) {
}
