package net.knightsandkings.knk.paper.navigation;

import java.util.Locale;
import java.util.Objects;

import net.knightsandkings.knk.core.domain.location.KnkLocation;

/**
 * One entry of the {@code /navigate} catalogue (DESIGN §6.1): a Location, a Town / District /
 * Structure, a labelled street of the road network, or a named road node - what the name lookup
 * ({@code NamedTargets}, R21) and tab completion work on. Turning an entry into a routable
 * {@link Destination} is {@link NavigationDestinations#locate}.
 *
 * @param type     what it is
 * @param name     its display name
 * @param id       the Location / domain / street / node id
 * @param world    the world it lives in when known now (streets, nodes, Locations); null otherwise
 * @param location a Location's coordinates (type LOCATION only)
 * @param position a road node's floor block (type NODE only)
 */
public record NavTarget(Type type, String name, int id, String world, KnkLocation location, double[] position) {

    /** The type word players type before the colon ({@code town:Kardenna}). */
    public enum Type {
        LOCATION("location"),
        TOWN("town"),
        DISTRICT("district"),
        STRUCTURE("structure"),
        STREET("street"),
        NODE("node");

        private final String word;

        Type(String word) {
            this.word = word;
        }

        public String word() {
            return word;
        }

        public boolean isDomain() {
            return this == TOWN || this == DISTRICT || this == STRUCTURE;
        }

        /** The catalogue's {@code domainType} ("Town", "District", "Structure") → type; null for others. */
        public static Type ofDomainType(String domainType) {
            if (domainType == null) {
                return null;
            }
            return switch (domainType.toLowerCase(Locale.ROOT)) {
                case "town" -> TOWN;
                case "district" -> DISTRICT;
                case "structure" -> STRUCTURE;
                default -> null;
            };
        }
    }

    public NavTarget {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
        position = position == null ? null : position.clone();
    }

    public static NavTarget location(KnkLocation location) {
        return new NavTarget(Type.LOCATION, location.name() == null ? "Location #" + location.id() : location.name(),
            location.id() == null ? -1 : location.id(), location.world(), location, null);
    }

    public static NavTarget domain(Type type, int id, String name) {
        return new NavTarget(type, name, id, null, null, null);
    }

    public static NavTarget street(String world, int id, String name) {
        return new NavTarget(Type.STREET, name, id, world, null, null);
    }

    public static NavTarget node(String world, int id, String name, double[] position) {
        return new NavTarget(Type.NODE, name, id, world, null, position);
    }

    /** {@code town:Kardenna} - the unambiguous form. */
    public String qualifiedName() {
        return type.word() + ":" + name;
    }

    @Override
    public String toString() {
        return qualifiedName() + "#" + id;
    }
}
