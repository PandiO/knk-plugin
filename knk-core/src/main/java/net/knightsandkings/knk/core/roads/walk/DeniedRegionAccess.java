package net.knightsandkings.knk.core.roads.walk;

import net.knightsandkings.knk.core.regions.DomainAccessEvaluator;
import net.knightsandkings.knk.core.regions.DomainAccessEvaluator.Denial;
import net.knightsandkings.knk.core.roads.route.DomainAvailability;
import net.knightsandkings.knk.core.roads.route.RegionShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Domain entry and exit rules at cell level (KNG-51 {@code LAST_MILE_PATHFINDING.md} §6 "Domains"):
 * the cell-level analogue of {@link DomainAvailability}, with the same evaluator and the same
 * semantics —
 * <ul>
 *   <li><b>entry:</b> a cell inside a region the mover is <em>not</em> in now, whose domain denies
 *       entry ({@link DomainAccessEvaluator#entry}), is blocked;</li>
 *   <li><b>exit:</b> for a region the mover <em>is</em> in now whose domain denies leaving
 *       ({@link DomainAccessEvaluator#exit}), every cell outside it is blocked;</li>
 *   <li><b>bypass</b> ({@code knk.region.bypass}): everything open.</li>
 * </ul>
 * Containment is pure geometry on the cell's <b>feet block</b> against a Bukkit-free
 * {@link RegionShape} copy of the WorldGuard region (the region tracker's rule: the block the feet
 * are in). The paper side collects the {@link Candidate}s overlapping the search box on the main
 * thread; {@link #resolve} looks the domains up — which may block briefly on the API, so it runs on
 * the routing thread like the road router's lookups — and keeps only the denying regions.
 * Immutable once built; safe on any thread.
 */
public final class DeniedRegionAccess implements CellAccess {

    /**
     * A WorldGuard region overlapping the search box.
     *
     * @param regionId     the WorldGuard region id (domains are looked up by it, D11)
     * @param shape        the region's geometry
     * @param moverInside  whether the mover stands in it now (then only its exit rule applies)
     */
    public record Candidate(String regionId, RegionShape shape, boolean moverInside) {
        public Candidate {
            Objects.requireNonNull(regionId, "regionId");
            Objects.requireNonNull(shape, "shape");
        }
    }

    /** A region whose domain denies entering it ({@code exit = false}) or leaving it ({@code exit = true}). */
    public record Rule(String regionId, RegionShape shape, boolean exit, String reason) {
        public Rule {
            Objects.requireNonNull(regionId, "regionId");
            Objects.requireNonNull(shape, "shape");
            Objects.requireNonNull(reason, "reason");
        }

        boolean blocks(int x, int feetY, int z) {
            boolean inside = shape.contains(x, feetY, z);
            return exit != inside;
        }
    }

    /** No denied regions. */
    public static final DeniedRegionAccess NONE = new DeniedRegionAccess(List.of());

    private final List<Rule> rules;

    public DeniedRegionAccess(List<Rule> rules) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
    }

    /**
     * Keeps the candidates whose domain denies the mover: entry for regions the mover is not in, exit
     * for regions they are in ({@link DomainAvailability}'s split). With {@code bypass}, none. Domain
     * lookups may block (paper: the resolver's API fallback) — call off the main thread.
     */
    public static DeniedRegionAccess resolve(List<Candidate> candidates, DomainAvailability.DomainLookup lookup,
                                             DomainAccessEvaluator evaluator, boolean bypass) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(evaluator, "evaluator");
        if (bypass) {
            return NONE;
        }
        List<Rule> rules = new ArrayList<>();
        for (Candidate candidate : candidates) {
            Optional<Denial> denial = lookup.domainByRegionId(candidate.regionId())
                .flatMap(candidate.moverInside() ? evaluator::exit : evaluator::entry);
            if (denial.isPresent()) {
                String name = denial.get().domain().name();
                String reason = String.format(candidate.moverInside()
                    ? DomainAvailability.EXIT_MESSAGE : DomainAvailability.ENTRY_MESSAGE, name);
                rules.add(new Rule(candidate.regionId(), candidate.shape(), candidate.moverInside(), reason));
            }
        }
        rules.sort(Comparator.comparing(Rule::regionId));
        return new DeniedRegionAccess(rules);
    }

    public List<Rule> rules() {
        return rules;
    }

    @Override
    public double extraCost(int x, int feetY, int z) {
        for (Rule rule : rules) {
            if (rule.blocks(x, feetY, z)) {
                return BLOCKED;
            }
        }
        return 0.0;
    }

    @Override
    public Optional<String> denyReason(int x, int feetY, int z) {
        for (Rule rule : rules) {
            if (rule.blocks(x, feetY, z)) {
                return Optional.of(rule.reason());
            }
        }
        return Optional.empty();
    }
}
