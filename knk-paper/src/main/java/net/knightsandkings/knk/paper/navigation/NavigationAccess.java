package net.knightsandkings.knk.paper.navigation;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.World;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.regions.DomainAccessEvaluator;
import net.knightsandkings.knk.core.regions.RegionDomainResolver;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import net.knightsandkings.knk.core.roads.build.EdgeTagging;
import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.route.AccessPolicy;
import net.knightsandkings.knk.core.roads.route.CompositeAccessPolicy;
import net.knightsandkings.knk.core.roads.route.DomainAvailability;
import net.knightsandkings.knk.core.roads.route.EdgePolyline;
import net.knightsandkings.knk.core.roads.route.GateAvailability;
import net.knightsandkings.knk.core.roads.route.GateAvailability.GateView;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.RouteRequest;
import net.knightsandkings.knk.core.roads.route.SnapPoint;
import net.knightsandkings.knk.core.roads.route.StaticFlagsAvailability;
import net.knightsandkings.knk.paper.gates.GatePassThroughRules;
import net.knightsandkings.knk.paper.regions.RegionIds;
import net.knightsandkings.knk.paper.roads.GateCellsIndex;
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
 *       KNG-17, R6; shared with the KNG-56 border).</li>
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
     * @param bypass      who ignores AllowEntry/AllowExit ({@code knk.region.bypass}, {@code KnKPlugin.hasRegionBypass})
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
        Set<Integer> doorIds = new HashSet<>();
        for (RoadEdge edge : snapshot.edges()) {
            doorIds.addAll(edge.gateDoorIds());
        }
        return CompositeAccessPolicy.of(new StaticFlagsAvailability(), gateAvailability(player, doorIds),
            new DomainAvailability(evaluator, this::domainByRegionId, currentRegions, bypass.test(player)));
    }

    /**
     * Main thread (live test 2026-10-08, N6): a player standing on a blocked edge - the road through a
     * closed gate, the road into a domain they may not enter - may still walk the part of it that does
     * not reach the block. Each part, from the start point to a node, is tagged from the world like the
     * live tags (its regions and gate doors only) and checked with the same policy.
     */
    @Override
    public RouteRequest.StartSides startSides(Player player, RoadNetworkSnapshot snapshot, SnapPoint start,
                                              AccessPolicy policy) {
        RoadEdge edge = snapshot.edge(start.edgeId()).orElse(null);
        World world = player.getWorld();
        if (edge == null || world == null || !policy.check(edge).isBlocked()) {
            return null;
        }
        EdgePolyline polyline = snapshot.polyline(edge);
        GateCells gates = GateCellsIndex.of(gateManager, world.getName());
        boolean towardFrom = partOpen(edge, polyline.subPolyline(start.along(), 0), world, gates, policy);
        boolean towardTo = partOpen(edge, polyline.subPolyline(start.along(), polyline.length()), world, gates, policy);
        return new RouteRequest.StartSides(towardFrom, towardTo);
    }

    private boolean partOpen(RoadEdge edge, List<double[]> part, World world, GateCells gates, AccessPolicy policy) {
        Optional<RoadEdge> sub = partOf(edge, part, b -> regionIds.at(world, b[0], b[1], b[2]), gates);
        return sub.isEmpty() || policy.check(sub.get()).isUsable(); // empty: the start point is the node
    }

    /**
     * Part of {@code edge} along {@code part} (points from the start point to a node) as an edge of its own,
     * tagged only with what the world has along it: regions at feet level ({@code regionsAt(x, feetY, z)})
     * and gate doors. Empty when the part is shorter than one block.
     */
    static Optional<RoadEdge> partOf(RoadEdge edge, List<double[]> part, java.util.function.Function<int[], Set<String>> regionsAt,
                                     GateCells gates) {
        List<int[]> blocks = new java.util.ArrayList<>();
        for (double[] p : part) {
            int[] b = {(int) Math.floor(p[0]), (int) Math.floor(p[1]), (int) Math.floor(p[2])};
            if (blocks.isEmpty() || !java.util.Arrays.equals(blocks.get(blocks.size() - 1), b)) {
                blocks.add(b);
            }
        }
        if (blocks.size() < 2) {
            return Optional.empty();
        }
        Set<String> regions = new LinkedHashSet<>();
        for (int[] s : EdgeTagging.samples(blocks, EdgeTagging.REGION_STEP)) {
            regions.addAll(regionsAt.apply(new int[] {s[0], s[1] + 1, s[2]}));
        }
        return Optional.of(new RoadEdge(edge.id(), edge.fromNodeId(), edge.toNodeId(), blocks, edge.length(), edge.avgWidth(),
            edge.profileId(), edge.streetId(), edge.costMultiplier(), edge.flags(), EdgeTagging.doorsAlong(blocks, gates),
            List.of(), List.copyOf(regions), edge.source(), edge.stale(), edge.confirmed()));
    }

    /**
     * Main thread: the router's gate rule for {@code doorIds}, each door read once from the gate cache
     * and the siege controller — the policy's gate part, shared with the walk search's gate cells
     * (KNG-51 §6). The result can be used from any thread.
     */
    public GateAvailability gateAvailability(Player player, Collection<Integer> doorIds) {
        boolean admin = GatePassThroughRules.isAdmin(player);
        boolean use = player.hasPermission(GatePassThroughRules.USE_NODE);
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
