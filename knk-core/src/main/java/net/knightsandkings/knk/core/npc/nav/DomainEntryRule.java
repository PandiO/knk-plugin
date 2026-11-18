package net.knightsandkings.knk.core.npc.nav;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import net.knightsandkings.knk.core.regions.DomainAccessEvaluator;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import net.knightsandkings.knk.core.roads.route.DomainAvailability;
import net.knightsandkings.knk.core.roads.route.DomainAvailability.RoadRule;

/**
 * The entry and exit rule per region that the NPC checks use (PandiO/knk-workspace#361, #701): the recheck of an NPC's
 * way ahead, the trespass guard, and a group's follower lanes and guard. It is the road policy's domain rule plus the
 * subject's own lists, answered from the region → domain map only: a region of unknown domain is open, as for the
 * trail.
 *
 * <p>Exits (PandiO/knk-workspace#701) come from the road router's own rule ({@link DomainAvailability#exitDeniedRegions}):
 * a region the subject stands in whose domain denies leaving may not be left, unless the subject bypasses domain rules,
 * its allow list names the region, or the domain's rule does not apply to roads. A walking NPC that would leave it stops
 * inside, at the edge, as at a region it may not enter.
 *
 * <p>Each region is judged on its own (PandiO/knk-workspace#593). A step into two regions at once, such as a district
 * and a gate structure region covering its entry, is allowed only when both may be entered, so a gate region that
 * allows entry never hides the district's denial. There is no region priority here: the domain entry rule has none.
 */
public final class DomainEntryRule {

    private DomainEntryRule() {
    }

    /**
     * @param subject   who moves; a region it stands in is not entered
     * @param evaluator the shared entry and exit rule (R6)
     * @param domains   (world, region id) → the region's domain from the map, without asking the API
     * @param roadRule  which domains' rules apply to roads (rev. 7 Part C)
     * @return whether the subject may step into a region, read now; its {@link RegionRule#mayLeave} answers whether
     *         it may step out of a region it stands in
     */
    public static RegionRule of(AccessSubject subject, DomainAccessEvaluator evaluator,
                                       BiFunction<String, String, Optional<DomainSnapshot>> domains, RoadRule roadRule) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(domains, "domains");
        String world = subject.world();
        Set<String> current = Set.copyOf(subject.currentRegions());
        DomainAvailability rule = new DomainAvailability(evaluator,
            regionId -> subject.regionOverride(regionId) == AccessSubject.RegionOverride.ALLOW ? Optional.empty()
                : domains.apply(world, regionId),
            roadRule, current, null, subject.bypassesDomainRules());
        Predicate<String> enter = regionId -> current.contains(regionId)
            || (subject.regionOverride(regionId) != AccessSubject.RegionOverride.DENY && rule.mayEnter(regionId));
        return RegionRule.of(enter, Set.copyOf(rule.exitDeniedRegions()));
    }
}
