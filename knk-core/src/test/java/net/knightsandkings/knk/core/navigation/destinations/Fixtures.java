package net.knightsandkings.knk.core.navigation.destinations;

import java.util.List;
import java.util.UUID;

import net.knightsandkings.knk.core.navigation.destinations.Candidate.Mode;
import net.knightsandkings.knk.core.navigation.destinations.Candidate.Place;

/** Candidates and contexts the engine tests share: a small world catalogue and a siege match. */
final class Fixtures {

    static final String WORLD = "world";
    static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private Fixtures() {
    }

    static NavigationContext player() {
        return NavigationContext.of(PLAYER, WORLD).withPermissions(InitialScopes.NAVIGATE_NODE);
    }

    static Candidate town(int id, String name, String world) {
        return domain("Town", "town", id, name, world, List.of());
    }

    static Candidate district(int id, String name, String world) {
        return domain("District", "district", id, name, world, List.of());
    }

    /** A gate: a Structure that also answers to {@code gatestructure:} and {@code gate:}. */
    static Candidate gate(int id, String name, String world) {
        return domain("Structure", "structure", id, name, world, List.of("gatestructure", "gate"));
    }

    /** A domain with a spawn in {@code world} (null: region only, so it is tried in the player's world). */
    static Candidate domain(String type, String word, int id, String name, String world, List<String> aliases) {
        Place.Point spawn = world == null ? null : new Place.Point(world, 10, 64, 10);
        return new Candidate("domain:" + type + ":" + id, name, word, aliases, id, null, 0, "catalogue",
            new Place.Domain(spawn, type.toLowerCase() + "_" + id, Mode.SPAWN));
    }

    static Candidate location(int id, String name, String world) {
        return new Candidate("location:" + id, name, "location", List.of(), id, null, 0, "catalogue",
            new Place.Point(world, 1, 65, 1));
    }

    static Candidate street(int id, String name, String world) {
        return new Candidate("street:" + id, name, "street", List.of(), id, null, 0, "catalogue",
            new Place.Street(world, id));
    }

    static Candidate node(int id, String name, String world) {
        return new Candidate("node:" + id, name, "node", List.of(), id, null, 0, "catalogue",
            new Place.Node(world, id, new double[] {3, 64, 3}));
    }

    /** A siege objective as the match provider lists it; {@code priority} is the compass order. */
    static Candidate objective(int id, String name, String detail, int priority) {
        return new Candidate("objective:" + id, name, "objective", List.of(), id, detail, priority, "siege-match",
            new Place.Point(WORLD, 100, 64, 100));
    }

    static Candidate teamSpawn(int id, String name, int priority) {
        return new Candidate("spawn:" + id, name, "spawn", List.of(), id, null, priority, "siege-match",
            new Place.Point(WORLD, 200, 64, 200));
    }
}
