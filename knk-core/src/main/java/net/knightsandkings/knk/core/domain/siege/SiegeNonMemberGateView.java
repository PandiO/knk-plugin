package net.knightsandkings.knk.core.domain.siege;

/**
 * DESIGN §8.5 degrade switch for what non-members see of the siege gates (Phase 7b):
 * per-player pre-lockdown view, or only the temporary TELEPORT pass-through.
 */
public enum SiegeNonMemberGateView {
    PRE_LOCKDOWN_VIEW,
    PASS_THROUGH_ONLY;

    /** Parses "PreLockdownView"/"PassThroughOnly"; unknown values fall back to the default view. */
    public static SiegeNonMemberGateView fromApi(String value) {
        return SiegeEnums.parse(SiegeNonMemberGateView.class, value, PRE_LOCKDOWN_VIEW);
    }
}
