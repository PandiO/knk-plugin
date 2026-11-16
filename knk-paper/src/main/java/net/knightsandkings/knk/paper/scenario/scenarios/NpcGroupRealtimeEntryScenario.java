package net.knightsandkings.knk.paper.scenario.scenarios;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.core.npc.nav.AccessRules;
import net.knightsandkings.knk.core.npc.nav.AccessSubject;
import net.knightsandkings.knk.core.npc.nav.DomainEntryRule;
import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.group.Formation;
import net.knightsandkings.knk.core.realtime.Coalescer;
import net.knightsandkings.knk.core.realtime.EntityChangeRouter;
import net.knightsandkings.knk.core.regions.DomainAccessEvaluator;
import net.knightsandkings.knk.core.regions.RegionDomainResolver;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import net.knightsandkings.knk.core.roads.route.DomainAvailability.RoadRule;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.navigation.DomainCacheRewarm;
import net.knightsandkings.knk.paper.navigation.NavigationDomainChanges;
import net.knightsandkings.knk.paper.scenario.NpcCornerScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * PandiO/knk-workspace#593 (Phase 4 developer test step 10, #506, #533) on a real server: entry to a district is removed
 * in the web app with realtime on while a group walks in, and the district's entry is covered by a gate structure
 * region (WorldGuard regions over the corner scene's east road: a town over the scene, the district from z 10, the gate
 * region over the district's first blocks, so the NPCs step into district and gate region at once). The rules are read
 * as on a server, from a region → domain map through {@link DomainEntryRule}. The change goes the realtime way, through
 * the plugin's own classes: {@code EntityChanged} for the District clears the map, {@link NavigationDomainChanges}
 * asks for one {@link DomainCacheRewarm} after the coalescer's delay, the roads' regions are asked about again (a fake
 * API answering 10 ticks later), then the availability event. The followers outside when the event comes stop at the
 * edge, the ones inside walk on, all follow when entry returns the same way; nobody trespasses. Needs Citizens and
 * WorldGuard.
 */
public final class NpcGroupRealtimeEntryScenario implements Scenario<ScenarioScene> {

    static final int TIMEOUT = 60 * 20;
    /** The district's edge: the east road from z 10 on. */
    static final int EDGE_Z = 10;
    /** Ticks the fake API takes to answer. */
    static final int API_TICKS = 10;

    @Override
    public String name() {
        return "npc-group-realtime-entry";
    }

    @Override
    public String description() {
        return "Entry to a district under a gate region is removed through realtime mid-group: outside followers stop, "
            + "inside walk on, all follow after";
    }

    private static DomainSnapshot domain(int id, String name, String region, boolean allowEntry, String type) {
        return new DomainSnapshot(id, name, "", region, allowEntry, true, type, Set.of(), Set.of(), Set.of(), Set.of());
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        NpcCornerScene corners = NpcCornerScene.in(scene);
        Plugin plugin = scene.services().plugin();
        String world = scene.world().getName();
        String town = scene.uniqueName("town");
        String district = scene.uniqueName("district");
        String gate = scene.uniqueName("gate");
        int districtId = scene.fakeId(2);

        // the API (the web app's current rules) and the plugin's region -> domain map
        Map<String, DomainSnapshot> api = new java.util.concurrent.ConcurrentHashMap<>();
        api.put(town, domain(scene.fakeId(1), "Town", town, true, "Town"));
        api.put(district, domain(districtId, "Residential District", district, true, "District"));
        api.put(gate, domain(scene.fakeId(3), "South Gate", gate, true, "GateStructure"));
        RegionDomainResolver map = new RegionDomainResolver();
        api.values().forEach(d -> map.registerDomain(world, d));
        DomainAccessEvaluator evaluator = new DomainAccessEvaluator();
        AccessRules rules = new AccessRules() {
            @Override
            public Predicate<String> mayEnter(AccessSubject subject) {
                return DomainEntryRule.of(subject, evaluator, map::getDomainByRegionIdNoRefresh, RoadRule.ALWAYS);
            }

            @Override
            public boolean gateBlocks(AccessSubject subject, int doorId) {
                return false;
            }
        };

        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        List<NpcMover> members = new ArrayList<>();
        List<NpcMover> outside = new ArrayList<>();
        List<NpcMover> inside = new ArrayList<>();
        Map<Integer, Double> zAtEvent = new HashMap<>();
        Map<Integer, Double> furthestZ = new HashMap<>();
        AtomicInteger events = new AtomicInteger();
        double edge = scene.worldZ(EDGE_Z);
        NavPoint goal = corners.feet(18.5, NpcCornerScene.GROUND, 26.5);

        // the realtime wiring as KnKPlugin builds it, with a fake API behind the re-warm
        Coalescer coalescer = new Coalescer((delay, task) -> Bukkit.getScheduler().runTaskLater(plugin, task,
            Math.max(1L, delay.toMillis() / 50L)), Duration.ofMillis(500));
        DomainCacheRewarm rewarm = new DomainCacheRewarm(() -> true, () -> List.of(world),
            w -> world.equals(w) ? Set.of(town, district, gate) : Set.of(),
            (w, regions) -> answerLater(plugin, map, w, regions, api),
            task -> Bukkit.getScheduler().runTask(plugin, task),
            () -> {
                if (events.incrementAndGet() == 1) {
                    // the first event: the denial reaches the NPCs now; who is outside and inside from here on
                    for (NpcMover mover : members.subList(1, members.size())) {
                        NavPoint at = mover.position().orElseThrow();
                        zAtEvent.put(mover.id(), at.z());
                        (at.blockZ() >= (int) edge ? inside : outside).add(mover);
                    }
                }
                npcs.get().rulesChanged(); // road navigation's availability event, as the rule listener hands it on
            }, Logger.getLogger("npc-group-realtime-entry"));
        EntityChangeRouter router = new EntityChangeRouter();
        for (String type : NavigationDomainChanges.DOMAIN_TYPES) {
            router.on(type, (id, key, op) -> map.clearRegionCache()); // KnKPlugin's own handler
        }
        new NavigationDomainChanges(coalescer, rewarm).register(router);

        plan.act("build the corner scene, a town, a district from z 10 and a gate region over its entry", () -> {
                corners.build();
                ScenarioRegions.cuboid(scene, town, 0, 0, 0, ScenarioScene.SIZE - 1, NpcCornerScene.HEIGHT,
                    ScenarioScene.SIZE - 1);
                ScenarioRegions.cuboid(scene, district, 24, 0, EDGE_Z, 28, NpcCornerScene.HEIGHT, 27);
                ScenarioRegions.cuboid(scene, gate, 24, 0, EDGE_Z, 28, NpcCornerScene.HEIGHT, EDGE_Z + 3);
            })
            .cleanup("remove the regions", () -> ScenarioRegions.removeAll(scene, town, district, gate))
            .act("start NPC navigation on Citizens with the domain rules", () -> {
                ScenarioNpcs started = ScenarioNpcs.start(scene, corners.routeLine(), NavigationSettings.defaults(),
                    rules, List.of());
                npcs.set(started);
                started.everyTick(() -> {
                    for (NpcMover mover : members) {
                        mover.position().ifPresent(p -> furthestZ.merge(mover.id(), p.z(), Math::max));
                    }
                });
            })
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            })
            .act("a group of 8 in a column walks down the east road towards the district", () -> {
                for (int i = 0; i < 8; i++) {
                    members.add(npcs.get().spawn(corners.feet(22.5 - 1.5 * i, NpcCornerScene.GROUND, 4.5)));
                }
                npcs.get().patrolGroup(members, Formation.Type.COLUMN, goal, corners.start());
            })
            .await("the leader reaches the gate", TIMEOUT, () -> members.get(0).position()
                .map(p -> p.z() >= edge - 1).orElse(false))
            .act("entry to the district is removed in the web app (realtime: EntityChanged for the District)", () -> {
                api.put(district, domain(districtId, "Residential District", district, false, "District"));
                router.route("District", districtId, null, "Updated");
            })
            .await("the denial reached the NPCs (coalescer, re-warm, availability event)", 5 * 20, () -> events.get() == 1)
            .check("followers are outside and inside", () -> outside.size() >= 2 && !inside.isEmpty() ? null
                : "outside " + outside.size() + ", inside " + inside.size())
            .check("the district denies entry in the map again", () ->
                map.getDomainByRegionIdNoRefresh(world, district).map(d -> Boolean.FALSE.equals(d.allowEntry()))
                    .orElse(false) ? null : "the map has " + map.getDomainByRegionIdNoRefresh(world, district))
            .waitTicks(80)
            .check("the followers outside stopped at the gate", () -> {
                for (NpcMover mover : outside) {
                    NavPoint at = mover.position().orElseThrow();
                    if (at.blockZ() >= (int) edge || furthestZ.get(mover.id()) >= edge) {
                        return mover.name() + " entered the district, at " + at + npcs.get().tail(20);
                    }
                }
                return null;
            })
            .check("the ones inside walked on", () -> {
                for (NpcMover mover : inside) {
                    if (furthestZ.get(mover.id()) < zAtEvent.get(mover.id()) + 3) {
                        return mover.name() + " stopped inside" + npcs.get().tail(20);
                    }
                }
                return null;
            })
            .act("entry returns the same way", () -> {
                api.put(district, domain(districtId, "Residential District", district, true, "District"));
                router.route("District", districtId, null, "Updated");
            })
            .await("everybody follows into the district", TIMEOUT, () -> {
                for (NpcMover mover : outside) {
                    if (furthestZ.get(mover.id()) < edge + 8) {
                        return false;
                    }
                }
                return true;
            })
            .check("nobody trespassed", () -> npcs.get().metrics().total(Event.TRESPASS) == 0 ? null
                : "trespasses " + npcs.get().metrics().total(Event.TRESPASS) + npcs.get().tail(20));
    }

    /** The fake API: {@code regions}' current domains go into the map {@link #API_TICKS} ticks from now. */
    private static CompletableFuture<Void> answerLater(Plugin plugin, RegionDomainResolver map, String world,
                                                       Collection<String> regions, Map<String, DomainSnapshot> api) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (String region : regions) {
                DomainSnapshot domain = api.get(region);
                if (domain != null) {
                    map.registerDomain(world, domain);
                }
            }
            done.complete(null);
        }, API_TICKS);
        return done;
    }
}
