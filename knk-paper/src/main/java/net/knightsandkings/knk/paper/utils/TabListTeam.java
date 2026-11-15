package net.knightsandkings.knk.paper.utils;

import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Which scoreboard team a player belongs on, and that team's color — this is what colors their
 * name in the tab list and above their head (KNG-7). A team carries one color, so players who
 * share a team must share the color.
 * <p>
 * PandiO/knk-workspace#260 (developer decision 2026-11-05): the name color comes from the first of
 * the player's groups in precedence order (hierarchy, then weight) that sets one, resolved by the
 * API; {@link UserSummary#nameColorGroupId()} names that group. A group that sets a color gets its
 * own team, {@code group_<id>}, and that beats the fixed owner/staff colors: setting the Staff
 * group's tab-list color is how staff get a different one. Otherwise as before (v1's
 * Scoreboard.GetTeam): owner (DARK_PURPLE), staff (BLUE), else "default" colored by the Default
 * group's nameColor. A premium tier is a group like any other: its team is {@code group_<id>} when
 * its color wins. A hex color maps to the nearest of the 16 named colors; GRAY when nothing is set.
 * With no summary at all (not cached yet) the color is null, meaning "leave the shared default
 * team's color as it is" rather than resetting it.
 */
public record TabListTeam(String name, NamedTextColor color) {
    public static final String OWNER = "owner";
    public static final String STAFF = "staff";
    public static final String DEFAULT = "default";
    /** #260: one team per group whose name color won (replaces the per-tier {@code tier_<id>} teams). */
    public static final String GROUP_PREFIX = "group_";

    public static TabListTeam resolve(boolean isOwner, boolean isStaff, UserSummary user) {
        if (user != null && user.nameColorGroupId() != null && user.nameColor() != null) {
            return new TabListTeam(GROUP_PREFIX + user.nameColorGroupId(),
                LegacyStyles.nameColor(user.nameColor(), NamedTextColor.GRAY));
        }
        if (isOwner) {
            return new TabListTeam(OWNER, NamedTextColor.DARK_PURPLE);
        }
        if (isStaff) {
            return new TabListTeam(STAFF, NamedTextColor.BLUE);
        }
        if (user == null) {
            return new TabListTeam(DEFAULT, null);
        }
        return new TabListTeam(DEFAULT, LegacyStyles.nameColor(user.nameColor(), NamedTextColor.GRAY));
    }
}
