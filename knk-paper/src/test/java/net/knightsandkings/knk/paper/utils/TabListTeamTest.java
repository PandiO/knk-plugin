package net.knightsandkings.knk.paper.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * KNG-7: tab-list / nametag team per player. PandiO/knk-workspace#260: the group whose name color won (by
 * precedence, resolved by the API) gets its own team and beats the fixed owner/staff colors; otherwise owner,
 * staff, default as before.
 */
class TabListTeamTest {

    /** A summary whose nameColor came from group {@code fromGroup} (null = the Default group's, or none). */
    private static UserSummary summary(Integer fromGroup, String nameColor) {
        return new UserSummary(1, "alice", UUID.randomUUID(), null, 0, 0, 0, false, false,
            GatePassThroughMethod.DEFAULT, ActiveMode.NONE, null, null, 0,
            null, null, null, false, null, null, null, null, nameColor, List.of(), fromGroup);
    }

    @Test
    void owner_withoutAGroupColor_isPurple() {
        assertEquals(new TabListTeam("owner", NamedTextColor.DARK_PURPLE), TabListTeam.resolve(true, true, summary(null, "&7")));
    }

    @Test
    void staff_withoutAGroupColor_isBlue() {
        assertEquals(new TabListTeam("staff", NamedTextColor.BLUE), TabListTeam.resolve(false, true, summary(null, "&7")));
    }

    @Test
    void groupColor_getsTeamPerGroupId_andBeatsTheFixedStaffAndOwnerColors() {
        // The developer's S2 test: the Staff group (id 50) sets its tab-list color.
        assertEquals(new TabListTeam("group_50", NamedTextColor.RED), TabListTeam.resolve(false, true, summary(50, "&c")));
        assertEquals(new TabListTeam("group_50", NamedTextColor.RED), TabListTeam.resolve(true, true, summary(50, "&c")));
    }

    @Test
    void premiumTierColor_isAGroupTeamLikeAnyOther() {
        assertEquals(new TabListTeam("group_12", NamedTextColor.AQUA), TabListTeam.resolve(false, false, summary(12, "&b")));
    }

    @Test
    void groupColor_hexOrFormatted_usesNearestNamedColor() {
        assertEquals(new TabListTeam("group_12", NamedTextColor.GOLD), TabListTeam.resolve(false, false, summary(12, "&x&f&f&a&a&0&0&l")));
    }

    @Test
    void groupIdWithoutAColor_fallsBackToTheUsualTeams() {
        assertEquals(new TabListTeam("default", NamedTextColor.GRAY), TabListTeam.resolve(false, false, summary(12, null)));
    }

    @Test
    void default_usesDefaultGroupNameColor() {
        assertEquals(new TabListTeam("default", NamedTextColor.WHITE), TabListTeam.resolve(false, false, summary(null, "&f")));
    }

    @Test
    void olderApi_withoutNameColorGroupId_keepsTheOldTeams() {
        UserSummary old = new UserSummary(1, "alice", UUID.randomUUID(), null, 0, 0, 0, false, false,
            GatePassThroughMethod.DEFAULT, ActiveMode.NONE, null, null, 0,
            12, "Royal", null, false, null, null, null, null, "&b");
        assertEquals(new TabListTeam("staff", NamedTextColor.BLUE), TabListTeam.resolve(false, true, old));
        assertEquals(new TabListTeam("default", NamedTextColor.AQUA), TabListTeam.resolve(false, false, old));
    }

    @Test
    void noSummary_defaultTeam_leavesColorAlone() {
        TabListTeam team = TabListTeam.resolve(false, false, null);
        assertEquals("default", team.name());
        assertNull(team.color());
    }
}
