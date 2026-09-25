package net.knightsandkings.knk.paper.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Tab-list footer lines for title/prestige (IMPLEMENTATION_PLAN.md §4) and premium tier (§5).
 */
class ScoreboardUtilTest {

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static UserSummary summary(String titleName, int prestige, String premiumTierName, OffsetDateTime premiumExpiresAt) {
        return new UserSummary(1, "alice", UUID.randomUUID(), null, 0, 0, 0, false, false,
            GatePassThroughMethod.DEFAULT, ActiveMode.NONE, titleName == null ? null : 1, titleName, prestige,
            premiumTierName == null ? null : 12, premiumTierName, premiumExpiresAt);
    }

    @Test
    void premiumLine_nullSummary_isEmpty() {
        assertEquals("", plain(ScoreboardUtil.premiumTierFooterLine(null)));
    }

    @Test
    void premiumLine_noPremiumTier_isEmpty() {
        assertEquals("", plain(ScoreboardUtil.premiumTierFooterLine(summary("Novice", 0, null, null))));
    }

    @Test
    void premiumLine_permanentTier_showsNameOnly() {
        assertEquals("\n§6Noble", plain(ScoreboardUtil.premiumTierFooterLine(summary("Novice", 0, "Noble", null))));
    }

    @Test
    void premiumLine_temporaryTier_showsUtcExpiryDate() {
        // 23:30 at +02:00 is 21:30 UTC the same day; 01:30 at +02:00 would be the previous UTC day.
        var expires = OffsetDateTime.of(2026, 10, 1, 1, 30, 0, 0, ZoneOffset.ofHours(2));
        assertEquals("\n§6Royal §7(until 2026-09-30)",
            plain(ScoreboardUtil.premiumTierFooterLine(summary("Novice", 0, "Royal", expires))));
    }

    @Test
    void titleLine_unchangedByPremiumFields() {
        assertEquals("\n§bMaster §7(+5 prestige XP)",
            plain(ScoreboardUtil.titleFooterLine(summary("Master", 5, "Royal", null))));
    }

    @Test
    void legacyFourteenArgConstructor_leavesPremiumFieldsNull() {
        var s = new UserSummary(1, "alice", UUID.randomUUID(), null, 0, 0, 0, false, false,
            GatePassThroughMethod.DEFAULT, ActiveMode.NONE, 1, "Novice", 0);
        assertEquals("", plain(ScoreboardUtil.premiumTierFooterLine(s)));
        assertEquals(s, s.withActiveMode(ActiveMode.NONE));
    }
}
