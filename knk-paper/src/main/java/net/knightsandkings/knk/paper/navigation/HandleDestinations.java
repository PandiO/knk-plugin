package net.knightsandkings.knk.paper.navigation;

import java.util.Objects;

import net.knightsandkings.knk.core.navigation.destinations.AllowedDestination;
import net.knightsandkings.knk.core.navigation.destinations.Candidate;
import net.knightsandkings.knk.core.navigation.destinations.Candidate.Mode;
import net.knightsandkings.knk.core.navigation.destinations.Candidate.Place;

/**
 * Turns an engine-issued handle into the routable {@link Destination} the session works on (destination engine DESIGN
 * § 3, § 12.1, phase 3, PandiO/knk-workspace#69): synchronously, from the candidate's place - no API call at click
 * time (§ 7.1). A domain follows today's rules ({@link NavigationDestinations#locateDomain}): the spawn Location for
 * {@code spawn}, the region for {@code region}, the domain's default without either; a domain without the one falls
 * back to the other; without a mode word a player already in the domain's region is already there (N9). Pure.
 */
public final class HandleDestinations {

    private HandleDestinations() {
    }

    /** The destination for a player in {@code playerWorld}; null when the domain has neither place for the mode. */
    public static Destination of(AllowedDestination handle, String playerWorld) {
        Objects.requireNonNull(handle, "handle");
        return of(handle.candidate(), handle.mode(), playerWorld);
    }

    static Destination of(Candidate candidate, Mode mode, String playerWorld) {
        String name = candidate.label();
        Place place = candidate.place();
        if (place instanceof Place.Point p) {
            // a landmark's Radius extent (#708): arrived inside its cylinder
            return Destination.point(name, p.world(), p.x(), p.y(), p.z()).withArrivalRadius(p.arrivalRadius());
        }
        if (place instanceof Place.Street s) {
            return Destination.street(name, s.world(), s.streetId());
        }
        if (place instanceof Place.Node n) {
            return Destination.node(name, n.world(), n.nodeId(), n.floor());
        }
        if (place instanceof Place.Region r) {
            return Destination.region(name, r.world() == null ? playerWorld : r.world(), r.regionId());
        }
        Place.Domain d = (Place.Domain) place;
        Mode asked = mode == null ? Mode.DEFAULT : mode;
        Mode wanted = asked != Mode.DEFAULT ? asked : d.defaultMode() == Mode.REGION ? Mode.REGION : Mode.SPAWN;
        Place.Point spawn = d.spawn();
        if (wanted == Mode.SPAWN && spawn != null) {
            return asked == Mode.DEFAULT && d.hasRegion()
                ? Destination.domainPoint(name, spawn.world(), spawn.x(), spawn.y(), spawn.z(), d.regionId())
                : Destination.point(name, spawn.world(), spawn.x(), spawn.y(), spawn.z());
        }
        if (d.hasRegion()) {
            // Regions carry no world in the API; the player's world is tried, as today.
            return Destination.region(name, playerWorld, d.regionId());
        }
        if (wanted == Mode.REGION && spawn != null) {
            return Destination.point(name, spawn.world(), spawn.x(), spawn.y(), spawn.z());
        }
        return null;
    }
}
