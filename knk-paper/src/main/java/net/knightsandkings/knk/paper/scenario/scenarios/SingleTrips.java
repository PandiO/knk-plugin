package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NpcMover;

/**
 * Single NPCs on one trip each, all at once (the NPC movement scenarios, PandiO/knk-workspace#621): who arrived, how
 * the others' trips ended, and how long the trips took.
 */
final class SingleTrips {

    private final ScenarioNpcs npcs;
    private final List<NpcMover> movers = new ArrayList<>();
    private final Map<Integer, NavigationResult> results = new HashMap<>();
    private final Map<Integer, Long> started = new HashMap<>();
    private final Map<Integer, Long> took = new HashMap<>();

    private SingleTrips(ScenarioNpcs npcs) {
        this.npcs = npcs;
    }

    /** Spawns an NPC at each start and sends it to the goal of the same index. */
    static SingleTrips send(ScenarioNpcs npcs, List<NavPoint> starts, List<NavPoint> goals) {
        if (starts.size() != goals.size()) {
            throw new IllegalArgumentException(starts.size() + " starts for " + goals.size() + " goals");
        }
        SingleTrips trips = new SingleTrips(npcs);
        List<NpcMover> spawned = new ArrayList<>();
        for (NavPoint start : starts) {
            spawned.add(npcs.spawn(start));
        }
        for (int i = 0; i < spawned.size(); i++) {
            NpcMover mover = spawned.get(i);
            trips.movers.add(mover);
            trips.started.put(mover.id(), npcs.ticks());
            npcs.service().navigate(mover.id(), goals.get(i), result -> {
                trips.results.put(mover.id(), result);
                trips.took.put(mover.id(), npcs.ticks() - trips.started.get(mover.id()));
            });
        }
        return trips;
    }

    List<NpcMover> movers() {
        return movers;
    }

    boolean ended(NpcMover mover) {
        return results.containsKey(mover.id());
    }

    boolean allEnded() {
        return results.size() == movers.size();
    }

    long arrived() {
        return results.values().stream().filter(NavigationResult.Arrived.class::isInstance).count();
    }

    /** {@code "12/12"}. */
    String arrivals() {
        return arrived() + "/" + movers.size();
    }

    /** How the trips that did not arrive ended (or "still walking"), counted: {@code "Failed 1, still walking 2"}. */
    String others() {
        Map<String, Integer> kinds = new TreeMap<>();
        for (NpcMover mover : movers) {
            NavigationResult result = results.get(mover.id());
            if (result instanceof NavigationResult.Arrived) {
                continue;
            }
            kinds.merge(result == null ? "still walking" : result.getClass().getSimpleName(), 1, Integer::sum);
        }
        if (kinds.isEmpty()) {
            return "-";
        }
        List<String> out = new ArrayList<>();
        kinds.forEach((kind, n) -> out.add(kind + " " + n));
        return String.join(", ", out);
    }

    /** Mean and longest trip of those that arrived, in seconds: {@code "9.8 s, longest 14.2 s"}. */
    String tripTimes() {
        long sum = 0;
        long most = 0;
        int n = 0;
        for (NpcMover mover : movers) {
            if (results.get(mover.id()) instanceof NavigationResult.Arrived) {
                long t = took.get(mover.id());
                sum += t;
                most = Math.max(most, t);
                n++;
            }
        }
        return n == 0 ? "-" : String.format(Locale.ROOT, "mean %.1f s, longest %.1f s", sum / 20.0 / n, most / 20.0);
    }
}
