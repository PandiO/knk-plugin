package net.knightsandkings.knk.core.navigation.destinations;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One navigable thing a {@link Provider} offers (destination engine DESIGN §3): what the name lookup, tab completion
 * and the compass cycle work on, and what the handle ({@link AllowedDestination}) points at. The {@code key} is
 * stable across re-orderings and renames ({@code domain:Town:12}, {@code objective:7}, {@code street:5}), so a running
 * session is re-validated by key (§8); ids, never names. The {@link Place} holds everything a routable destination
 * needs, synchronously: no API call at click time (§7.1).
 *
 * @param key      stable id, unique within a list
 * @param label    display name, what the player types
 * @param typeWord the type word before the colon ({@code town}, {@code objective}); the {@code type:name} form picks
 *                 one of several same-named candidates
 * @param aliases  more type words that pick it ({@code gatestructure} and {@code gate} for a gate); usually empty
 * @param id       the numeric id for the {@code type:#id} form; {@link #NO_ID} when there is none
 * @param detail   a short note for lists ("defend, under attack, 62% held"); null for none
 * @param priority ordering inside the list after the scope's own ordering: lower first
 * @param source   the id of the provider that offered it
 * @param place    where it leads
 */
public record Candidate(String key, String label, String typeWord, List<String> aliases, int id, String detail,
                        int priority, String source, Place place) {

    public static final int NO_ID = -1;

    /**
     * What the player asked for after the name: a domain's spawn Location, its region, or nothing ({@code DEFAULT}:
     * the domain's configured default, {@link Place.Domain#defaultMode()}). The core twin of the command's mode word.
     */
    public enum Mode {
        DEFAULT,
        SPAWN,
        REGION;

        public static Optional<Mode> parse(String word) {
            if (word == null) {
                return Optional.empty();
            }
            return switch (word.toLowerCase(Locale.ROOT)) {
                case "spawn" -> Optional.of(SPAWN);
                case "region" -> Optional.of(REGION);
                default -> Optional.empty();
            };
        }
    }

    /**
     * The data a routable destination is built from, held by the candidate itself. The world is the one the place
     * is known to be in; null when it is not known (a region carries no world in the API and is tried in the
     * player's world, as today). A place in another world than the player is listed apart (E11).
     */
    public sealed interface Place permits Place.Point, Place.Region, Place.Street, Place.Node, Place.Domain {

        String world();

        /** A Location or a siege objective / spawnpoint: feet coordinates. */
        record Point(String world, double x, double y, double z) implements Place {
            public Point {
                Objects.requireNonNull(world, "world");
            }
        }

        /** A WorldGuard region (its closest point); {@code world} null when unknown. */
        record Region(String world, String regionId) implements Place {
            public Region {
                Objects.requireNonNull(regionId, "regionId");
            }
        }

        /** A labelled street of the world's road network. */
        record Street(String world, int streetId) implements Place {
            public Street {
                Objects.requireNonNull(world, "world");
            }
        }

        /** A named road node; {@code floor} is its floor block (x, y, z). */
        record Node(String world, int nodeId, double[] floor) implements Place {
            public Node {
                Objects.requireNonNull(world, "world");
                floor = floor == null ? null : floor.clone();
            }
        }

        /**
         * A Town / District / Structure: its spawn Location and/or its region, and which of the two {@code /navigate
         * <name>} leads to without a mode word (KNG-73). One of {@code spawn} and {@code regionId} may be missing;
         * {@link #offers} tells what can be asked for.
         */
        record Domain(Point spawn, String regionId, Mode defaultMode) implements Place {
            public Domain {
                regionId = regionId == null || regionId.isBlank() ? null : regionId;
                defaultMode = defaultMode == Mode.REGION ? Mode.REGION : Mode.SPAWN;
            }

            /** The spawn's world; null without a spawn (the region is tried in the player's world). */
            @Override
            public String world() {
                return spawn == null ? null : spawn.world();
            }

            public boolean hasSpawn() {
                return spawn != null;
            }

            public boolean hasRegion() {
                return regionId != null;
            }

            /** Whether {@code mode} can be served: a spawn for SPAWN, a region for REGION, either for DEFAULT. */
            public boolean offers(Mode mode) {
                return switch (mode == null ? Mode.DEFAULT : mode) {
                    case SPAWN -> hasSpawn();
                    case REGION -> hasRegion();
                    case DEFAULT -> hasSpawn() || hasRegion();
                };
            }
        }
    }

    public Candidate {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(typeWord, "typeWord");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(place, "place");
        if (key.isBlank()) throw new IllegalArgumentException("key must not be blank");
        if (label.isBlank()) throw new IllegalArgumentException("label must not be blank");
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    /** A candidate without aliases, id or detail; {@code priority} 0. */
    public static Candidate of(String key, String label, String typeWord, String source, Place place) {
        return new Candidate(key, label, typeWord, List.of(), NO_ID, null, 0, source, place);
    }

    /** {@code town:Kardenna} - the unambiguous form. */
    public String qualifiedName() {
        return typeWord.toLowerCase(Locale.ROOT) + ":" + label;
    }

    /** Whether the place is in {@code world} or in no known world. */
    public boolean inWorld(String world) {
        String own = place.world();
        return own == null || own.equalsIgnoreCase(world);
    }

    public Candidate withLabel(String label) {
        return new Candidate(key, label, typeWord, aliases, id, detail, priority, source, place);
    }

    public Candidate withAliases(List<String> aliases) {
        return new Candidate(key, label, typeWord, aliases, id, detail, priority, source, place);
    }

    public Candidate withId(int id) {
        return new Candidate(key, label, typeWord, aliases, id, detail, priority, source, place);
    }

    public Candidate withDetail(String detail) {
        return new Candidate(key, label, typeWord, aliases, id, detail, priority, source, place);
    }

    public Candidate withPriority(int priority) {
        return new Candidate(key, label, typeWord, aliases, id, detail, priority, source, place);
    }

    public Candidate withPlace(Place place) {
        return new Candidate(key, label, typeWord, aliases, id, detail, priority, source, place);
    }

    @Override
    public String toString() {
        return qualifiedName() + " [" + key + "]";
    }
}
