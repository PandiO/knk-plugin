package net.knightsandkings.knk.core.npc.nav;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Steps from one place, judged by the rules as they are now (PandiO/knk-workspace#701): a step into a place in other
 * regions may not enter a region the subject may not enter, nor leave a region it may not leave. The rule is asked for
 * only when a step enters or leaves a region, and each region's entry verdict is kept for the check's life (one
 * recheck, one guard look), so the checks of a long way ahead stay cheap.
 */
public final class StepCheck {

    private final Set<String> here;
    private final Supplier<RegionRule> source;
    private final Map<String, Boolean> entries = new HashMap<>();
    private RegionRule rule;

    /**
     * @param here   the regions the subject stands in
     * @param source the rule for a subject standing in {@code here}, asked for at most once
     */
    public StepCheck(Set<String> here, Supplier<RegionRule> source) {
        this.here = Set.copyOf(here);
        this.source = Objects.requireNonNull(source, "source");
    }

    /** The regions the subject stands in. */
    public Set<String> here() {
        return here;
    }

    /**
     * The first region a step into {@code there} may not cross under the current rules (an entry before an exit), or
     * null when the step is allowed. A step that enters and leaves no region is always allowed.
     */
    public RegionRule.Denied denied(Collection<String> there) {
        boolean enters = !here.containsAll(there);
        boolean leaves = !there.containsAll(here);
        if (!enters && !leaves) {
            return null;
        }
        RegionRule r = rule();
        if (enters) {
            for (String region : there) {
                if (!here.contains(region) && !entries.computeIfAbsent(region, r::test)) {
                    return new RegionRule.Denied(region, false);
                }
            }
        }
        if (leaves) {
            for (String region : r.exitDenied()) {
                if (!there.contains(region)) {
                    return new RegionRule.Denied(region, true);
                }
            }
        }
        return null;
    }

    /** Whether a step into {@code there} is allowed under the current rules. */
    public boolean allows(Collection<String> there) {
        return denied(there) == null;
    }

    private RegionRule rule() {
        if (rule == null) {
            rule = Objects.requireNonNull(source.get(), "rule");
        }
        return rule;
    }
}
