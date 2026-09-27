package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.regions.DomainAccessEvaluator;
import net.knightsandkings.knk.core.regions.DomainAccessEvaluator.Denial;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Domain entry and exit rules on an edge (DESIGN §6.7 rows 2-3, plan 2d, D11, reuse R6/R7):
 * <ul>
 *   <li><b>Entry:</b> an edge that passes a region whose domain denies entry
 *       ({@link DomainAccessEvaluator#entry}) is BLOCKED - unless the player is already inside that
 *       region (they are not entering it).</li>
 *   <li><b>Exit:</b> for every region the player is in now whose domain denies leaving
 *       ({@link DomainAccessEvaluator#exit}), an edge that does not pass that region is BLOCKED
 *       (it leaves the domain). Phase 2d decision: an edge that lists the region counts as staying
 *       inside, so the route ends on the last edge inside the domain.</li>
 * </ul>
 * Domains are looked up <b>by WorldGuard region id</b> (D11) through the {@link DomainLookup}
 * port (paper: {@code RegionDomainResolver.getDomainByRegionIdNoRefresh}, falling back to
 * {@code resolveRegionsFromApi} off the main thread); the same evaluator instance the region
 * tracker uses is passed in (one source of truth). With {@code bypass} (staff, owner mode,
 * KNG-17's {@code knk.region.bypass}) everything is OPEN. Per-region decisions are cached for the
 * request.
 */
public final class DomainAvailability implements AccessPolicy {

    /** Port: the domain owning a WorldGuard region, if any. */
    @FunctionalInterface
    public interface DomainLookup {
        Optional<DomainSnapshot> domainByRegionId(String regionId);
    }

    public static final String ENTRY_MESSAGE = "you may not enter %s";
    public static final String EXIT_MESSAGE = "you may not leave %s";

    private final DomainAccessEvaluator evaluator;
    private final DomainLookup lookup;
    private final Set<String> currentRegionIds;
    private final boolean bypass;
    private final Map<String, Optional<Denial>> entryByRegion = new HashMap<>();
    private final List<Exit> exits;

    private record Exit(String regionId, Denial denial) {
    }

    /**
     * @param evaluator        the shared evaluator (R6)
     * @param lookup           domain by region id
     * @param currentRegionIds the WorldGuard regions the player stands in now (R8's {@code at})
     * @param bypass           whether the player may ignore entry/exit rules
     */
    public DomainAvailability(DomainAccessEvaluator evaluator, DomainLookup lookup, Set<String> currentRegionIds,
                              boolean bypass) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.currentRegionIds = Set.copyOf(currentRegionIds);
        this.bypass = bypass;
        List<Exit> found = new ArrayList<>();
        if (!bypass) {
            for (String regionId : this.currentRegionIds) {
                lookup.domainByRegionId(regionId).flatMap(evaluator::exit)
                    .ifPresent(denial -> found.add(new Exit(regionId, denial)));
            }
            found.sort((a, b) -> a.regionId.compareTo(b.regionId));
        }
        this.exits = List.copyOf(found);
    }

    /** The regions the player stands in whose domains forbid leaving (empty with bypass). */
    public List<String> exitDeniedRegions() {
        return exits.stream().map(Exit::regionId).toList();
    }

    @Override
    public EdgeVerdict check(RoadEdge edge) {
        if (bypass) {
            return EdgeVerdict.open();
        }
        for (Exit exit : exits) {
            if (!edge.regionIds().contains(exit.regionId)) {
                DomainSnapshot d = exit.denial.domain();
                return EdgeVerdict.blocked(String.format(EXIT_MESSAGE, d.name()),
                    EdgeVerdict.Cause.domain(d.id() == null ? -1 : d.id(), d.name()));
            }
        }
        for (String regionId : edge.regionIds()) {
            if (currentRegionIds.contains(regionId)) {
                continue; // already inside: not an entry
            }
            Optional<Denial> denial = entryByRegion.computeIfAbsent(regionId,
                id -> lookup.domainByRegionId(id).flatMap(evaluator::entry));
            if (denial.isPresent()) {
                DomainSnapshot d = denial.get().domain();
                return EdgeVerdict.blocked(String.format(ENTRY_MESSAGE, d.name()),
                    EdgeVerdict.Cause.domain(d.id() == null ? -1 : d.id(), d.name()));
            }
        }
        return EdgeVerdict.open();
    }
}
