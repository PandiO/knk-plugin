package net.knightsandkings.knk.paper.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.kyori.adventure.text.format.NamedTextColor;

/** KNG-7: tab-list / nametag team per player — owner, staff, premium tier, default. */
class TabListTeamTest {

    private static UserSummary summary(Integer tierId, String nameColor) {
        return new UserSummary(1, "alice", UUID.randomUUID(), null, 0, 0, 0, false, false,
            GatePassThroughMethod.DEFAULT, ActiveMode.NONE, null, null, 0,
            tierId, tierId == null ? null : "Royal", null, false, null, null, null, null, nameColor);
    }

    @Test
    void owner_winsOverEverything() {
        assertEquals(new TabListTeam("owner", NamedTextColor.DARK_PURPLE), TabListTeam.resolve(true, true, summary(12, "AQUA")));
    }

    @Test
    void staff_winsOverPremiumTier() {
        assertEquals(new TabListTeam("staff", NamedTextColor.BLUE), TabListTeam.resolve(false, true, summary(12, "AQUA")));
    }

    @Test
    void premiumTier_getsTeamPerGroupId_coloredByNameColor() {
        assertEquals(new TabListTeam("tier_12", NamedTextColor.AQUA), TabListTeam.resolve(false, false, summary(12, "AQUA")));
    }

    @Test
    void premiumTier_withoutNameColor_isGray() {
        assertEquals(new TabListTeam("tier_12", NamedTextColor.GRAY), TabListTeam.resolve(false, false, summary(12, null)));
    }

    @Test
    void default_usesDefaultGroupNameColor() {
        assertEquals(new TabListTeam("default", NamedTextColor.WHITE), TabListTeam.resolve(false, false, summary(null, "WHITE")));
    }

    @Test
    void noSummary_defaultTeam_leavesColorAlone() {
        TabListTeam team = TabListTeam.resolve(false, false, null);
        assertEquals("default", team.name());
        assertNull(team.color());
    }
}
