package net.knightsandkings.knk.paper.navigation;

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
 *       {@link RegionIds#at} (R8); the bypass is the region tracker's {@code knk.region.bypass}
 *       predicate (KNG-17, R6).</li>
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

    /**
     * @param gateManager the gate cache (R5)
     * @param siegeGates  the siege gate controller when siege initialized, read per call (may return null)
     * @param regionIds   the shared WorldGuard region query (R8)
     * @param resolver    region id → domain (R7)
     * @param bypass      who ignores AllowEntry/AllowExit ({@code WorldGuardRegionTracker.bypassesDenials})
     * @param evaluator   the shared entry/exit rule (R6)
     */
    public NavigationAccess(GateManager gateManager, Supplier<SiegeGateController> siegeGates, RegionIds regionIds,
                            RegionDomainResolver resolver, Predicate<Player> bypass, DomainAccessEvaluator evaluator) {
        this.gateManager = Objects.requireNonNull(gateManager, "gateManager");
        this.siegeGates = Objects.requireNonNull(siegeGates, "siegeGates");
        this.regionIds = Objects.requireNonNull(regionIds, "regionIds");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.bypass = Objects.requireNonNull(bypass, "bypass");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    /** Main thread: reads the player's regions, nodes and every gate of the network once. */
    @Override
    public AccessPolicy policyFor(Player player, RoadNetworkSnapshot snapshot) {
        Set<String> currentRegions = regionIds.at(player.getLocation());
        boolean admin = GatePassThroughRules.isAdmin(player);
        boolean use = player.hasPermission(GatePassThroughRules.USE_NODE);
        SiegeGateController siege = siegeGates.get();
        Map<Integer, GateView> gates = new HashMap<>();
        Map<Integer, Boolean> passable = new HashMap<>();
        Set<Integer> doorIds = new HashSet<>();
        for (RoadEdge edge : snapshot.edges()) {
            doorIds.addAll(edge.gateDoorIds());
        }
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
        DomainAvailability.DomainLookup lookup = this::domainByRegionId;
        return CompositeAccessPolicy.of(new StaticFlagsAvailability(), new GateAvailability(gateState, passRule),
            new DomainAvailability(evaluator, lookup, currentRegions, bypass.test(player)));
    }

    /** The domain of a region: the cache, else the API (any thread; blocks briefly off the main thread). */
    Optional<DomainSnapshot> domainByRegionId(String regionId) {
        Optional<DomainSnapshot> cached = resolver.getDomainByRegionIdNoRefresh(regionId);
        if (cached.isPresent()) {
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
