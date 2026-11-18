package net.knightsandkings.knk.core.navigation.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.navigation.destinations.Candidate;
import net.knightsandkings.knk.core.navigation.destinations.NavigationContext;
import net.knightsandkings.knk.core.navigation.destinations.Provider;
import net.knightsandkings.knk.core.navigation.model.DomainPlace;
import net.knightsandkings.knk.core.navigation.model.ModelPoint;
import net.knightsandkings.knk.core.navigation.model.NavigationModel;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;

/**
 * The world catalogue as a provider of the destination engine (DESIGN § 3, § 5, phase 3, PandiO/knk-workspace#69):
 * the Towns, Districts and Structures (gates included, also as {@code gatestructure:} and {@code gate:}) and the
 * Locations of the navigation model, and the labelled streets and named nodes of the player's world's road network -
 * the same entries as today's {@code NavigationDestinations} catalogue, but every place is already in the candidate
 * (the model holds each domain's spawn Location and region), so nothing is looked up at click time (§ 7.1).
 *
 * <p>Key forms: {@code domain:<Town|District|Structure>:<id>}, {@code location:<id>}, {@code street:<id>},
 * {@code node:<id>}. Order inside the list: domains, then Locations, then streets, then nodes (today's catalogue
 * order), each by name. Without a model (no snapshot and the API not answered yet, § 7.4) only the streets and nodes
 * are offered. The model's part is built once per model and reused (E18: model-backed lists may be cached); the
 * network part once per snapshot of a world. Main thread.
 */
public final class CatalogueProvider implements Provider {

    public static final String ID = "catalogue";

    /** Candidate priorities: today's catalogue order (domains, Locations, streets, nodes). */
    static final int DOMAIN_PRIORITY = 0;
    static final int LOCATION_PRIORITY = 1;
    static final int STREET_PRIORITY = 2;
    static final int NODE_PRIORITY = 3;

    /** Player nicknames of subtype words: {@code gate:Keep Gate} as well as {@code gatestructure:Keep Gate}. */
    static final Map<String, List<String>> NICKNAMES = Map.of("gatestructure", List.of("gate"));

    private final Supplier<NavigationModel> model;
    private final Function<String, RoadNetworkSnapshot> snapshots;

    private NavigationModel builtFor;
    private List<Candidate> modelPart = List.of();
    private final Map<String, Built> networkParts = new java.util.HashMap<>();

    private record Built(RoadNetworkSnapshot snapshot, List<Candidate> candidates) {
    }

    /**
     * @param model     the current navigation model; null when there is none yet
     * @param snapshots a world's road network snapshot; null when none is loaded
     */
    public CatalogueProvider(Supplier<NavigationModel> model, Function<String, RoadNetworkSnapshot> snapshots) {
        this.model = Objects.requireNonNull(model, "model");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Candidate> candidates(NavigationContext ctx) {
        List<Candidate> out = new ArrayList<>(modelPart(model.get()));
        out.addAll(networkPart(ctx.world()));
        return out;
    }

    private List<Candidate> modelPart(NavigationModel current) {
        if (current == null) {
            return List.of();
        }
        if (current != builtFor) {
            modelPart = List.copyOf(fromModel(current));
            builtFor = current;
        }
        return modelPart;
    }

    private List<Candidate> networkPart(String world) {
        RoadNetworkSnapshot snapshot = world == null ? null : snapshots.apply(world);
        if (snapshot == null) {
            return List.of();
        }
        String key = world.toLowerCase(Locale.ROOT);
        Built built = networkParts.get(key);
        if (built == null || built.snapshot() != snapshot) {
            built = new Built(snapshot, List.copyOf(fromNetwork(world, snapshot)));
            networkParts.put(key, built);
        }
        return built.candidates();
    }

    // ==================== candidates ====================

    /** The domains and Locations of {@code model}. */
    public static List<Candidate> fromModel(NavigationModel model) {
        List<Candidate> out = new ArrayList<>();
        for (DomainPlace domain : model.domains()) {
            Candidate candidate = domain(domain);
            if (candidate != null) {
                out.add(candidate);
            }
        }
        for (ModelPoint location : model.locations()) {
            Candidate candidate = location(location);
            if (candidate != null) {
                out.add(candidate);
            }
        }
        return out;
    }

    /** The labelled streets and named nodes of {@code world}'s network. */
    public static List<Candidate> fromNetwork(String world, RoadNetworkSnapshot snapshot) {
        List<Candidate> out = new ArrayList<>();
        for (Map.Entry<Integer, RoadNetworkSnapshot.Street> street : snapshot.streets().entrySet()) {
            String name = street.getValue().name();
            if (name != null && !name.isBlank()) {
                out.add(new Candidate("street:" + street.getKey(), name, "street", List.of(), street.getKey(), null,
                    STREET_PRIORITY, ID, new Candidate.Place.Street(world, street.getKey())));
            }
        }
        for (RoadNode node : snapshot.namedNodes()) {
            if (node.name() != null && !node.name().isBlank()) {
                out.add(new Candidate("node:" + node.id(), node.name(), "node", List.of(), node.id(), null, NODE_PRIORITY,
                    ID, new Candidate.Place.Node(world, node.id(), node.position())));
            }
        }
        return out;
    }

    /**
     * A Town / District / Structure: its spawn Location (when the model knows it) and its region, with its
     * {@code navigationDefault}; null for other domain types and nameless domains (as today's catalogue).
     */
    static Candidate domain(DomainPlace domain) {
        String type = typeWordOf(domain.domainType());
        if (type == null || domain.name().isBlank()) {
            return null;
        }
        List<String> aliases = new ArrayList<>();
        String word = domain.domainType().toLowerCase(Locale.ROOT);
        if (!word.equals(type)) {
            aliases.add(word);
            aliases.addAll(NICKNAMES.getOrDefault(word, List.of()));
        }
        Candidate.Place.Point spawn = domain.spawnPoint().map(CatalogueProvider::point).orElse(null);
        Candidate.Mode defaultMode = Candidate.Mode.parse(domain.navigationDefault()).orElse(Candidate.Mode.SPAWN);
        return new Candidate("domain:" + capitalized(type) + ":" + domain.id(), domain.name(), type, aliases, domain.id(),
            null, DOMAIN_PRIORITY, ID, new Candidate.Place.Domain(spawn, domain.wgRegionId(), defaultMode));
    }

    /** A named Location target. */
    static Candidate location(ModelPoint location) {
        if (location.name() == null || location.name().isBlank()) {
            return null;
        }
        int id = location.id() == null ? Candidate.NO_ID : location.id();
        String key = location.id() == null ? "location:" + location.name().toLowerCase(Locale.ROOT) : "location:" + id;
        return new Candidate(key, location.name(), "location", List.of(), id, null, LOCATION_PRIORITY, ID, point(location));
    }

    private static Candidate.Place.Point point(ModelPoint p) {
        return new Candidate.Place.Point(p.world(), p.x(), p.y(), p.z());
    }

    /**
     * The API's {@code domainType} as a type word: "Town", "District", "Structure", and the subtypes reported by
     * their own entity name ("GateStructure" is a Structure); null for others (today's {@code NavTarget.Type}).
     */
    static String typeWordOf(String domainType) {
        if (domainType == null) {
            return null;
        }
        return switch (domainType.toLowerCase(Locale.ROOT)) {
            case "town" -> "town";
            case "district" -> "district";
            case "structure", "gatestructure" -> "structure";
            default -> null;
        };
    }

    private static String capitalized(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
