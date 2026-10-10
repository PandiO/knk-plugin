package net.knightsandkings.knk.paper.navigation;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.regions.DomainAccessEvaluator;
import net.knightsandkings.knk.core.regions.RegionDomainResolver;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import net.knightsandkings.knk.core.roads.route.AccessPolicy;
import net.knightsandkings.knk.core.roads.route.CompositeAccessPolicy;
import net.knightsandkings.knk.core.roads.route.DomainAvailability;
import net.knightsandkings.knk.core.roads.route.DomainAvailability.RoadRule;
import net.knightsandkings.knk.core.roads.route.GateAvailability;
import net.knightsandkings.knk.core.roads.route.GateAvailability.GateView;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.StaticFlagsAvailability;
import net.knightsandkings.knk.paper.gates.GatePassThroughRules;
import net.knightsandkings.knk.paper.regions.RegionIds;
import net.knightsandkings.knk.paper.siege.SiegeGateController;

/**
 * The per-request {@link AccessPolicy} of one player (DESIGN §6.7, plan Phase 4 task 7, Phase 2d
 * decision 11): {@code CompositeAccessPolicy.of(StaticFlagsAvailability, GateAvailability,
 * DomainAvailability)} with the paper adapters:
 * <ul>
 *   <li>{@code GateState} ← {@link GateManager#getGate} → {@link GateView} from the door's
 *       <i>effective</i> accessors (R5), {@link SiegeGateController#isLocked} and
 *       {@link SiegeGateController#canCarryNonMember} (R24, R39, D2);</li>
 *   <li>{@code PassRule} ← {@link GatePassThroughRules} (R25);</li>
 *   <li>{@code DomainLookup} ← {@link RegionDomainResolver#getDomainByRegionIdNoRefresh}, falling
 *       back to the API off the main thread (R7); the player's current regions from
 *       {@link RegionIds#at} (R8); the bypass is {@code KnKPlugin.hasRegionBypass} ({@code knk.region.bypass},
 *       KNG-17, R6; shared with the KNG-56 border);</li>
 *   <li>{@code RoadRule} ← {@link NavigationDestinations#roadAccessIgnored} (rev. 7 Part C, KNG-92): domains
 *       whose rule is "Ignored" for roads are skipped.</li>
 * </ul>
 * {@link #policyFor} runs on the main thread and reads every gate the network mentions once, so the
 * policy itself can be used from the routing thread without touching Bukkit or the gate cache.
 */
public final class NavigationAccess implements NavigationService.PolicyFactory {

    private static final Logger LOGGER = Logger.getLogger(NavigationAccess.class.getName());
    private static final long API_FALLBACK_SECONDS = 3;

    private final GateManager gateManager;
    private final Supplier<SiegeGateController> siegeGates;
    private final RegionIds regionIds;
    private final RegionDomainResolver resolver;
    private final Predicate<Player> bypass;
    private final DomainAccessEvaluator evaluator;
    private final RoadRule roadRule;

    /**
     * @param gateManager the gate cache (R5)
     * @param siegeGates  the siege gate controller when siege initialized, read per call (may return null)
     * @param regionIds   the shared WorldGuard region query (R8)
     * @param resolver    region id → domain (R7)
     * @param bypass      who ignores AllowEntry/AllowExit ({@code knk.region.bypass}, {@code KnKPlugin.hasRegionBypass})
     * @param evaluator   the shared entry/exit rule (R6)
     */
    public NavigationAccess(GateManager gateManager, Supplier<SiegeGateController> siegeGates, RegionIds regionIds,
                            RegionDomainResolver resolver, Predicate<Player> bypass, DomainAccessEvaluator evaluator) {
        this(gateManager, siegeGates, regionIds, resolver, bypass, evaluator, RoadRule.ALWAYS);
    }

    /**
     * @param roadRule which domains' entry/exit rules apply to roads (rev. 7 Part C)
     */
    public NavigationAccess(GateManager gateManager, Supplier<SiegeGateController> siegeGates, RegionIds regionIds,
                            RegionDomainResolver resolver, Predicate<Player> bypass, DomainAccessEvaluator evaluator,
                            RoadRule roadRule) {
        this.gateManager = Objects.requireNonNull(gateManager, "gateManager");
        this.siegeGates = Objects.requireNonNull(siegeGates, "siegeGates");
        this.regionIds = Objects.requireNonNull(regionIds, "regionIds");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.bypass = Objects.requireNonNull(bypass, "bypass");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.roadRule = Objects.requireNonNull(roadRule, "roadRule");
    }

    /** Main thread: reads the player's regions, nodes and every gate of the network once. */
    @Override
    public AccessPolicy policyFor(Player player, RoadNetworkSnapshot snapshot) {
        return policyFor(player, snapshot, null);
    }

    /** As {@link #policyFor(Player, RoadNetworkSnapshot)}, knowing the destination's regions (KNG-110); null: unknown. */
    @Override
    public AccessPolicy policyFor(Player player, RoadNetworkSnapshot snapshot, Set<String> destinationRegions) {
        Set<String> currentRegions = regionIds.at(player.getLocation());
        Set<Integer> doorIds = new HashSet<>();
        for (RoadEdge edge : snapshot.edges()) {
            doorIds.addAll(edge.gateDoorIds());
        }
        return CompositeAccessPolicy.of(new StaticFlagsAvailability(), gateAvailability(player, doorIds),
            new DomainAvailability(evaluator, this::domainByRegionId, roadRule, currentRegions, destinationRegions,
                bypass.test(player)));
    }

    /**
     * KNG-110: the router's entry rule per region ({@link DomainAvailability#mayEnter}) for the trail, with the domain
     * cache only - the trail is drawn on the main thread, and a region of unknown domain is open, as for the router.
     */
    @Override
    public Predicate<String> mayEnter(Player player) {
        return mayEnter(player, null);
    }

    @Override
    public Predicate<String> mayEnter(Player player, Set<String> destinationRegions) {
        DomainAvailability rule = new DomainAvailability(evaluator, resolver::getDomainByRegionIdNoRefresh, roadRule,
            regionIds.at(player.getLocation()), destinationRegions, bypass.test(player));
        return rule::mayEnter;
    }

    /** KNG-110: the WorldGuard regions at a floor point, at feet level. */
    @Override
    public Set<String> regionsAt(org.bukkit.World world, double[] floorPoint) {
        return regionIds.at(world, (int) Math.floor(floorPoint[0]), (int) Math.floor(floorPoint[1]) + 1,
            (int) Math.floor(floorPoint[2]));
    }

    /**
     * Main thread: the router's gate rule for {@code doorIds}, each door read once from the gate cache
     * and the siege controller — the policy's gate part, shared with the walk search's gate cells
     * (KNG-51 §6). The result can be used from any thread.
     */
    public GateAvailability gateAvailability(Player player, Collection<Integer> doorIds) {
        boolean admin = GatePassThroughRules.isAdmin(player);
        boolean use = GatePassThroughRules.mayUse(player);
        SiegeGateController siege = siegeGates.get();
        Map<Integer, GateView> gates = new HashMap<>();
        Map<Integer, Boolean> passable = new HashMap<>();
        for (int doorId : doorIds) {
            CachedGateDoor door = gateManager.getGate(doorId);
            if (door == null) {
                continue;
            }
            boolean locked = siege != null && siege.isLocked(door.getGateStructureId());
            boolean carries = locked && siege.canCarryNonMember(door);
            gates.put(doorId, new GateView(doorId, gateName(door), door.getCurrentState(), door.isJammed(),
                door.isEffectivelyDestroyed(), door.isEffectivelyAllowPassThrough(), locked, carries));
            passable.put(doorId, GatePassThroughRules.canPass(admin, use, door));
        }
        GateAvailability.GateState gateState = doorId -> Optional.ofNullable(gates.get(doorId));
        GateAvailability.PassRule passRule = doorId -> passable.getOrDefault(doorId, false);
        return new GateAvailability(gateState, passRule);
    }

    /** Whether the player ignores domain entry/exit denials ({@code knk.region.bypass}). */
    public boolean bypasses(Player player) {
        return bypass.test(player);
    }

    /** The shared entry/exit rule (R6). */
    public DomainAccessEvaluator evaluator() {
        return evaluator;
    }

    /** The WorldGuard regions the player stands in now (R8). */
    public Set<String> regionsAt(Player player) {
        return regionIds.at(player.getLocation());
    }

    /** The domain of a region: the cache, else the API (any thread; blocks briefly off the main thread). */
    public Optional<DomainSnapshot> domainByRegionId(String regionId) {
        Optional<DomainSnapshot> cached = resolver.getDomainByRegionIdNoRefresh(regionId);
        if (cached.isPresent()) {
            // KNG-104: an expired entry is answered as it is and re-asked in the background, so a change made in the
            // web app (AllowEntry/AllowExit, a domain moved to another region) reaches the next route or re-check
            resolver.refreshIfStale(Set.of(regionId));
            return cached;
        }
        try {
            resolver.resolveRegionsFromApi(Set.of(regionId)).get(API_FALLBACK_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "[Navigation] Domain of region " + regionId + " not resolvable now: " + e.getMessage());
            return Optional.empty();
        }
        return resolver.getDomainByRegionIdNoRefresh(regionId);
    }

    static String gateName(CachedGateDoor door) {
        String structure = door.getStructureName();
        if (structure != null && !structure.isBlank()) {
            return structure;
        }
        return door.getName();
    }
}
