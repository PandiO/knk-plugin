package net.knightsandkings.knk.paper.navigation.walk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import org.bukkit.World;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.regions.DomainAccessEvaluator;
import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.route.DomainAvailability;
import net.knightsandkings.knk.core.roads.route.GateAvailability;
import net.knightsandkings.knk.core.roads.route.RegionShape;
import net.knightsandkings.knk.core.roads.walk.CellAccess;
import net.knightsandkings.knk.core.roads.walk.DeniedRegionAccess;
import net.knightsandkings.knk.core.roads.walk.DoorCellAccess;
import net.knightsandkings.knk.core.roads.walk.GateCellAccess;
import net.knightsandkings.knk.core.util.BlockKey;
import net.knightsandkings.knk.paper.navigation.NavigationAccess;

/**
 * Builds one player's {@link CellAccess} for a captured walk box (KNG-51 {@code LAST_MILE_PATHFINDING.md}
 * §6), so the search thread touches no Bukkit, gate cache or WorldGuard:
 * <ul>
 *   <li><b>Gates</b> — every gate door with a footprint block in the box gets its verdict from the
 *       road router's rule ({@link NavigationAccess#gateAvailability} → {@link GateCellAccess}).</li>
 *   <li><b>Domains</b> — the WorldGuard regions overlapping the box, copied into Bukkit-free
 *       {@link RegionShape}s (decision L3-2: {@code ProtectedRegion.contains} is not documented as
 *       thread-safe and regions can be redefined while a search runs), each marked "the player is in it
 *       now"; {@link WalkAccess#resolve} keeps the entry/exit-denying ones ({@link DeniedRegionAccess}).</li>
 *   <li><b>Doors</b> — each hand-openable door block in the box is checked once: WorldGuard's own
 *       answer to a click ({@code testBuild} with {@code INTERACT} and {@code USE}, as the region
 *       protection listener does for doors), WorldGuard's region bypass or KnK's
 *       {@code knk.region.bypass} → allowed. Denied blocks → {@link DoorCellAccess}. The KnK domain half of
 *       the door rule (§11-2) is the domain part above, which applies to door cells like any other.</li>
 * </ul>
 * {@link #prepare} runs on the main thread; {@link WalkAccess#resolve} on the routing thread (domain
 * lookups may wait briefly for the API, like the road router's). The server instance comes from
 * {@link WorldGuardWalkAccess#factory} (kept apart so this class loads without WorldGuard in tests).
 */
public final class WalkAccessFactory {

    /** Port: the WorldGuard regions (never {@code __global__}) overlapping a box, as shapes by region id. Main thread. */
    @FunctionalInterface
    public interface RegionsInBox {
        Map<String, RegionShape> regions(World world, WalkBox box);
    }

    /** Port: may the player open the door block at {@code (x, y, z)}? Main thread. */
    @FunctionalInterface
    public interface DoorUse {
        boolean mayUse(Player player, World world, int x, int y, int z);
    }

    /**
     * The main-thread half of a player's cell access: gate and door verdicts decided, regions collected.
     * Immutable; {@link #resolve} may run on any thread.
     */
    public record WalkAccess(GateCellAccess gates, DoorCellAccess doors, List<DeniedRegionAccess.Candidate> regions,
                             boolean bypass, DomainAvailability.DomainLookup lookup, DomainAccessEvaluator evaluator) {
        public WalkAccess {
            Objects.requireNonNull(gates, "gates");
            Objects.requireNonNull(doors, "doors");
            regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
            Objects.requireNonNull(lookup, "lookup");
            Objects.requireNonNull(evaluator, "evaluator");
        }

        /** Routing thread: looks the regions' domains up (may block briefly) and composes gates, doors and domains. */
        public CellAccess resolve() {
            return CellAccess.all(gates, doors, DeniedRegionAccess.resolve(regions, lookup, evaluator, bypass));
        }
    }

    private final BiFunction<Player, Collection<Integer>, GateAvailability> gateRule;
    private final Function<Player, Set<String>> moverRegions;
    private final Predicate<Player> bypass;
    private final RegionsInBox regionsInBox;
    private final DoorUse doorUse;
    private final DomainAvailability.DomainLookup lookup;
    private final DomainAccessEvaluator evaluator;

    /**
     * @param gateRule     the router's gate rule for a player and doors ({@link NavigationAccess#gateAvailability})
     * @param moverRegions the WorldGuard regions the player stands in now
     * @param bypass       {@code knk.region.bypass} (opens domains and doors)
     * @param regionsInBox the regions overlapping a box
     * @param doorUse      the door interact check
     * @param lookup       domain by region id ({@link NavigationAccess#domainByRegionId})
     * @param evaluator    the shared entry/exit rule
     */
    public WalkAccessFactory(BiFunction<Player, Collection<Integer>, GateAvailability> gateRule,
                             Function<Player, Set<String>> moverRegions, Predicate<Player> bypass,
                             RegionsInBox regionsInBox, DoorUse doorUse, DomainAvailability.DomainLookup lookup,
                             DomainAccessEvaluator evaluator) {
        this.gateRule = Objects.requireNonNull(gateRule, "gateRule");
        this.moverRegions = Objects.requireNonNull(moverRegions, "moverRegions");
        this.bypass = Objects.requireNonNull(bypass, "bypass");
        this.regionsInBox = Objects.requireNonNull(regionsInBox, "regionsInBox");
        this.doorUse = Objects.requireNonNull(doorUse, "doorUse");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    /**
     * Main thread: the player's access for a captured box.
     *
     * @param gates    the world's gate-door cells (the ones the capture used)
     * @param capture  a {@link WalkSnapshotService.Status#READY} capture
     * @param headroom the mover's headroom ({@code MovementProfile.headroom})
     */
    public WalkAccess prepare(Player player, World world, GateCells gates, WalkSnapshotService.WalkCapture capture,
                              int headroom) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(capture, "capture");
        if (!capture.ready()) {
            throw new IllegalArgumentException("capture is " + capture.status());
        }
        WalkBox box = capture.box();
        boolean bypasses = bypass.test(player);

        Set<Integer> doorIds = gates instanceof net.knightsandkings.knk.paper.roads.GateCellsIndex index
            ? index.doorIdsWithin(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())
            : doorIdsIn(gates, box);
        Map<Integer, String> blocked = doorIds.isEmpty() ? Map.of()
            : GateCellAccess.decideAll(gateRule.apply(player, doorIds), doorIds);
        GateCellAccess gateAccess = new GateCellAccess(gates == null ? GateCells.NONE : gates, headroom, blocked);

        List<Long> denied = new ArrayList<>();
        if (!bypasses) {
            for (long key : capture.doorBlocks()) {
                if (!doorUse.mayUse(player, world, BlockKey.x(key), BlockKey.y(key), BlockKey.z(key))) {
                    denied.add(key);
                }
            }
        }
        DoorCellAccess doorAccess = new DoorCellAccess(denied, headroom);

        List<DeniedRegionAccess.Candidate> candidates = new ArrayList<>();
        if (!bypasses) {
            Set<String> inside = moverRegions.apply(player);
            regionsInBox.regions(world, box).forEach((id, shape) ->
                candidates.add(new DeniedRegionAccess.Candidate(id, shape, inside.contains(id))));
        }
        return new WalkAccess(gateAccess, doorAccess, candidates, bypasses, lookup, evaluator);
    }

    /** Door ids of a {@link GateCells} that is not an index (tests): scan the box. */
    private static Set<Integer> doorIdsIn(GateCells gates, WalkBox box) {
        Set<Integer> ids = new java.util.HashSet<>();
        if (gates == null) {
            return ids;
        }
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    gates.doorAt(x, y, z).ifPresent(ids::add);
                }
            }
        }
        return ids;
    }
}
