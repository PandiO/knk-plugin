package net.knightsandkings.knk.core.npc.nav;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The access rules per region for one subject, judged from the regions it stands in now (PandiO/knk-workspace#701): the
 * entry rule ({@link #test}: whether it may step into a region; a region it stands in is not entered) and the exit
 * rule ({@link #mayLeave}: whether it may step out of a region it stands in). A step from where the subject stands to
 * a place in other regions is allowed when every region it enters may be entered and every region it leaves may be
 * left ({@link #deniedStep}). The NPC walking checks ask this: the recheck of the way ahead, the trespass guard, and a
 * group's follower lanes and guard.
 */
public interface RegionRule extends Predicate<String> {

    /** Every region may be entered and left. */
    RegionRule OPEN = of(regionId -> true);

    /** Whether the subject may step out of {@code regionId}; true for a region it does not stand in. */
    boolean mayLeave(String regionId);

    /** The regions the subject stands in that it may not leave (empty when it may leave every one). */
    Set<String> exitDenied();

    /**
     * The first region a step into {@code there} breaks the rules of, read now: a region entered that may not be
     * entered ({@code region <id>}), else a region left that may not be left ({@code exit <id>}). Null when the step
     * is allowed.
     *
     * @param here  the regions the subject stands in (the ones this rule was made for)
     * @param there the regions at the place it would step to
     */
    default Denied deniedStep(Set<String> here, Collection<String> there) {
        for (String region : there) {
            if (!here.contains(region) && !test(region)) {
                return new Denied(region, false);
            }
        }
        for (String region : exitDenied()) {
            if (!there.contains(region)) {
                return new Denied(region, true);
            }
        }
        return null;
    }

    /**
     * A region a step may not cross.
     *
     * @param region the WorldGuard region id
     * @param exit   true when the step leaves it (exit rule), false when it enters it (entry rule)
     */
    record Denied(String region, boolean exit) {

        /** {@code region <id>} for an entry, {@code exit <id>} for an exit: the recheck's key and log text. */
        public String key() {
            return (exit ? "exit " : "region ") + region;
        }

        /** {@code entered <id>} or {@code left <id>}, for the trespass log. */
        public String verb() {
            return (exit ? "left " : "entered ") + region;
        }
    }

    /**
     * An entry rule as a region rule: {@code enter} itself when it is one (Paper's {@code NavigationAccess} answers
     * with {@link DomainEntryRule}, which knows exits), else with every exit allowed (rules without exits, such as
     * test fakes).
     */
    static RegionRule of(Predicate<String> enter) {
        Objects.requireNonNull(enter, "enter");
        if (enter instanceof RegionRule rule) {
            return rule;
        }
        return new RegionRule() {
            @Override
            public boolean test(String regionId) {
                return enter.test(regionId);
            }

            @Override
            public boolean mayLeave(String regionId) {
                return true;
            }

            @Override
            public Set<String> exitDenied() {
                return Set.of();
            }
        };
    }

    /**
     * A region rule from an entry rule and the regions the subject stands in that it may not leave.
     */
    static RegionRule of(Predicate<String> enter, Set<String> exitDenied) {
        Objects.requireNonNull(enter, "enter");
        Set<String> denied = Set.copyOf(exitDenied);
        return new RegionRule() {
            @Override
            public boolean test(String regionId) {
                return enter.test(regionId);
            }

            @Override
            public boolean mayLeave(String regionId) {
                return !denied.contains(regionId);
            }

            @Override
            public Set<String> exitDenied() {
                return denied;
            }
        };
    }
}
