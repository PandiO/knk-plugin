package net.knightsandkings.knk.core.hub;

import java.util.List;
import java.util.Objects;

import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkGroupOverride;
import net.knightsandkings.knk.core.domain.settings.KnkHubSettings;
import net.knightsandkings.knk.core.domain.settings.KnkHubSettings.OutageAction;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.PermissionGroupRef;

/**
 * Which outage action applies to a player, after which delay, and whether they are exempt (hub DESIGN §13, §16.4
 * decision 5). Action and delay are separate fields: each comes from the first of the player's groups (in precedence
 * order) that sets it, else from the hub settings. A player is exempt while their <em>active</em> mode is staff or
 * owner and any of their groups ticks that mode's box; holding the node alone exempts nobody.
 */
public final class OutagePolicy {

    /** The action and delay for one player. */
    public record Applicable(OutageAction action, int delaySeconds) {
    }

    private OutagePolicy() {
    }

    public static Applicable applicable(KnkGameSettings settings, List<PermissionGroupRef> groups) {
        KnkHubSettings hub = settings != null ? settings.hub() : KnkHubSettings.none();
        OutageAction action = null;
        Integer delay = null;
        for (KnkGroupOverride group : groupOverrides(settings, groups)) {
            if (action == null && group.outageAction() != null) {
                action = group.outageAction();
            }
            if (delay == null && group.outageDelaySeconds() != null) {
                delay = group.outageDelaySeconds();
            }
        }
        return new Applicable(action != null ? action : hub.outageAction(),
            Math.max(0, delay != null ? delay : hub.outageDelaySeconds()));
    }

    public static boolean exempt(KnkGameSettings settings, List<PermissionGroupRef> groups, ActiveMode activeMode) {
        if (activeMode == null || activeMode == ActiveMode.NONE) {
            return false;
        }
        for (KnkGroupOverride group : groupOverrides(settings, groups)) {
            if ((activeMode == ActiveMode.STAFF && group.exemptInStaffMode())
                || (activeMode == ActiveMode.OWNER && group.exemptInOwnerMode())) {
                return true;
            }
        }
        return false;
    }

    private static List<KnkGroupOverride> groupOverrides(KnkGameSettings settings, List<PermissionGroupRef> groups) {
        if (settings == null || groups == null) {
            return List.of();
        }
        return groups.stream().filter(Objects::nonNull)
            .map(group -> settings.groupOverride(group.id()).orElse(null))
            .filter(Objects::nonNull)
            .toList();
    }
}
