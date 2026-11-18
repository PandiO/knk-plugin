package net.knightsandkings.knk.paper.scenario.scenarios;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.core.npc.nav.AccessRules;
import net.knightsandkings.knk.core.npc.nav.AccessSubject;
import net.knightsandkings.knk.core.npc.nav.DomainEntryRule;
import net.knightsandkings.knk.core.realtime.Coalescer;
import net.knightsandkings.knk.core.realtime.EntityChangeRouter;
import net.knightsandkings.knk.core.regions.DomainAccessEvaluator;
import net.knightsandkings.knk.core.regions.RegionDomainResolver;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import net.knightsandkings.knk.core.roads.route.DomainAvailability.RoadRule;
import net.knightsandkings.knk.paper.navigation.DomainCacheRewarm;
import net.knightsandkings.knk.paper.navigation.NavigationDomainChanges;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * Domain rules for an NPC scenario read as on a server (PandiO/knk-workspace#593, #701): a fake API (the web app's
 * current rules), the plugin's region → domain map, and the rules NPCs walk by through {@link DomainEntryRule} (entry
 * and exit). A change reaches the NPCs through the plugin's own classes, either way it does on a server:
 * <ol>
 *   <li>{@link #realtime}: {@code EntityChanged} clears the map, {@link NavigationDomainChanges} asks for one
 *       {@link DomainCacheRewarm} after the coalescer's delay, the regions are asked about again (the fake API answers
 *       {@link #API_TICKS} later), then the availability event;</li>
 *   <li>{@link #refresh}: {@code /knk cache refresh} clears the map and sends the availability event ("navigation
 *       routes"), then the re-warm and its second event.</li>
 * </ol>
 * The NPC rules read the map as the plugin's do ({@code RegionDomainResolver.getLastKnownDomain}): after a clear the
 * last known answers until the re-warm is done (PandiO/knk-workspace#724).
 */
final class ScenarioDomainRules {

    /** Ticks the fake API takes to answer. */
    static final int API_TICKS = 10;

    private final String world;
    private final Map<String, DomainSnapshot> api = new ConcurrentHashMap<>();
    private final RegionDomainResolver map = new RegionDomainResolver();
    private final EntityChangeRouter router = new EntityChangeRouter();
    private final DomainCacheRewarm rewarm;
    private final AtomicInteger events = new AtomicInteger();
    private final AccessRules rules;
    private Runnable onEvent = () -> { };
    private int apiTicks = API_TICKS;
    private Runnable beforeSwitch = () -> { };

    /** @param regions the regions the re-warm asks about (the roads' regions on a server) */
    ScenarioDomainRules(ScenarioScene scene, Set<String> regions) {
        Plugin plugin = scene.services().plugin();
        this.world = scene.world().getName();
        DomainAccessEvaluator evaluator = new DomainAccessEvaluator();
        this.rules = new AccessRules() {
            @Override
            public Predicate<String> mayEnter(AccessSubject subject) {
                return DomainEntryRule.of(subject, evaluator, map::getLastKnownDomain, RoadRule.ALWAYS);
            }

            @Override
            public boolean gateBlocks(AccessSubject subject, int doorId) {
                return false;
            }
        };
        Coalescer coalescer = new Coalescer((delay, task) -> Bukkit.getScheduler().runTaskLater(plugin, task,
            Math.max(1L, delay.toMillis() / 50L)), Duration.ofMillis(500));
        this.rewarm = new DomainCacheRewarm(() -> true, () -> List.of(world),
            w -> world.equals(w) ? Set.copyOf(regions) : Set.of(),
            (w, asked) -> answerLater(plugin, w, asked),
            task -> Bukkit.getScheduler().runTask(plugin, task),
            this::event, Logger.getLogger("scenario-domain-rules"), map::holdGeneration, () -> beforeSwitch.run(),
            map::releaseLastKnown); // PandiO/knk-workspace#724: the hold of the last known answers, as on a server
        for (String type : NavigationDomainChanges.DOMAIN_TYPES) {
            router.on(type, (id, key, op) -> map.clearRegionCache()); // KnKPlugin's own handler
        }
        new NavigationDomainChanges(coalescer, rewarm).register(router);
    }

    static DomainSnapshot domain(int id, String name, String region, boolean allowEntry, boolean allowExit,
                                 String type) {
        return new DomainSnapshot(id, name, "", region, allowEntry, allowExit, type, Set.of(), Set.of(), Set.of(),
            Set.of());
    }

    /** The rules NPCs walk by, from the map. */
    AccessRules rules() {
        return rules;
    }

    /** What the web app holds for a region from now on (the map learns it on the next ask). */
    void api(DomainSnapshot domain) {
        api.put(domain.wgRegionId(), domain);
    }

    /** The map has the API's current answers (the start-up warm-up). */
    void warm() {
        api.values().forEach(d -> map.registerDomain(world, d));
    }

    /** The plugin's map now, without asking the API. */
    java.util.Optional<DomainSnapshot> mapped(String region) {
        return map.getDomainByRegionIdNoRefresh(world, region);
    }

    /** Runs on every availability event, after it is counted (the scenario hands it to its NPCs). */
    void onEvent(Runnable then) {
        this.onEvent = then;
    }

    /**
     * PandiO/knk-workspace#724: runs once a re-warm's answers are in, before its hold ends and its event is sent (the
     * scenario lets its NPCs' trespass guards look, as the plugin does).
     */
    void onBeforeSwitch(Runnable then) {
        this.beforeSwitch = then;
    }

    /** Whether the NPC checks are kept on the last known answers now (a re-warm runs). */
    boolean holding() {
        return map.holding();
    }

    /** How many ticks the fake API takes to answer from now on (default {@link #API_TICKS}; a slow API is longer). */
    void apiTicks(int ticks) {
        this.apiTicks = ticks;
    }

    /** Availability events sent so far. */
    int events() {
        return events.get();
    }

    /** A change saved in the web app, with realtime on: {@code EntityChanged} for the domain. */
    void realtime(String type, int domainId) {
        router.route(type, domainId, null, "Updated");
    }

    /** {@code /knk cache refresh}: the map is cleared, the first event comes with it empty, then the re-warm. */
    void refresh() {
        map.clearRegionCache();
        event();
        rewarm.run();
    }

    private void event() {
        events.incrementAndGet();
        onEvent.run();
    }

    /** The fake API: the regions' current domains go into the map {@link #apiTicks} ticks from now. */
    private CompletableFuture<Void> answerLater(Plugin plugin, String w, Collection<String> regions) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (String region : regions) {
                DomainSnapshot domain = api.get(region);
                if (domain != null) {
                    map.registerDomain(w, domain);
                }
            }
            done.complete(null);
        }, apiTicks);
        return done;
    }
}
