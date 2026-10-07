package net.knightsandkings.knk.paper.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * @param aliases  more type words that pick it besides {@link Type#word()}: a subtype's own word and its
 *                 nickname ({@code gatestructure}, {@code gate} for a gate); usually empty
 */
public record NavTarget(Type type, String name, int id, String world, KnkLocation location, double[] position,
                        List<String> aliases) {

    /**
     * Player nicknames of the catalogue's subtype words: {@code gate:Keep Gate} as well as
     * {@code gatestructure:Keep Gate}. The entity name stays the precise word; the nickname is what players type.
     */
    static final Map<String, List<String>> NICKNAMES = Map.of("gatestructure", List.of("gate"));

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

        /**
         * The catalogue's {@code domainType} → type: "Town", "District", "Structure", and the subtypes the
         * API reports by their own entity name ("GateStructure" is a Structure); null for others.
         */
        public static Type ofDomainType(String domainType) {
            if (domainType == null) {
                return null;
            }
            return switch (domainType.toLowerCase(Locale.ROOT)) {
                case "town" -> TOWN;
                case "district" -> DISTRICT;
                case "structure", "gatestructure" -> STRUCTURE;
                default -> null;
            };
        }
    }

    public NavTarget {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
        position = position == null ? null : position.clone();
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    public static NavTarget location(KnkLocation location) {
        return new NavTarget(Type.LOCATION, location.name() == null ? "Location #" + location.id() : location.name(),
            location.id() == null ? -1 : location.id(), location.world(), location, null, null);
    }

    public static NavTarget domain(Type type, int id, String name) {
        return new NavTarget(type, name, id, null, null, null, null);
    }

    /**
     * A domain from the catalogue, whose {@code domainType} may be a subtype of {@code type}: a
     * "GateStructure" is a Structure that also answers to {@code gatestructure:} and {@code gate:}.
     */
    public static NavTarget domain(Type type, int id, String name, String domainType) {
        List<String> aliases = new ArrayList<>();
        String word = domainType == null ? "" : domainType.toLowerCase(Locale.ROOT);
        if (!word.isEmpty() && !word.equals(type.word())) {
            aliases.add(word);
            aliases.addAll(NICKNAMES.getOrDefault(word, List.of()));
        }
        return new NavTarget(type, name, id, null, null, null, aliases);
    }

    public static NavTarget street(String world, int id, String name) {
        return new NavTarget(Type.STREET, name, id, world, null, null, null);
    }

    public static NavTarget node(String world, int id, String name, double[] position) {
        return new NavTarget(Type.NODE, name, id, world, null, position, null);
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
