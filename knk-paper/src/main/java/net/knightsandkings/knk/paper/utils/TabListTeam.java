package net.knightsandkings.knk.paper.utils;

import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Which scoreboard team a player belongs on, and that team's color — this is what colors their
 * name in the tab list and above their head (KNG-7). Priority as in v1's Scoreboard.GetTeam:
 * owner, then staff, then premium tier, then default. Owner/staff colors are fixed (v1's
 * DARK_PURPLE and BLUE); a premium tier gets its own team per PermissionGroup id, colored by the
 * summary's nameColor (from the API: the tier's NameColor, else the Default group's); everyone
 * else is on "default", also colored by nameColor. GRAY when nothing is set, matching the
 * existing default team. With no summary at all (not cached yet) the color is null, meaning
 * "leave the shared default team's color as it is" rather than resetting it.
 */
public record TabListTeam(String name, NamedTextColor color) {
    public static final String OWNER = "owner";
    public static final String STAFF = "staff";
    public static final String DEFAULT = "default";
    public static final String TIER_PREFIX = "tier_";

    public static TabListTeam resolve(boolean isOwner, boolean isStaff, UserSummary user) {
        if (isOwner) {
            return new TabListTeam(OWNER, NamedTextColor.DARK_PURPLE);
        }
        if (isStaff) {
            return new TabListTeam(STAFF, NamedTextColor.BLUE);
        }
        if (user == null) {
            return new TabListTeam(DEFAULT, null);
        }
        NamedTextColor color = NamedColors.parse(user.nameColor(), NamedTextColor.GRAY);
        if (user.premiumTierGroupId() != null) {
            return new TabListTeam(TIER_PREFIX + user.premiumTierGroupId(), color);
        }
        return new TabListTeam(DEFAULT, color);
    }
}
