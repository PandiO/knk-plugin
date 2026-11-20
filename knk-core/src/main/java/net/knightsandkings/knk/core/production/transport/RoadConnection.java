package net.knightsandkings.knk.core.production.transport;

/**
 * A production structure's connection to its town's road network (DESIGN § 7.6, D5), as {@code PUT
 * api/ProductionStructures/{id}/road-connection} takes it. Rural producers that are not {@link #Connected} get no
 * automatic transport.
 */
public enum RoadConnection {
    Connected,
    Unconnected,
    Unmeasured
}
