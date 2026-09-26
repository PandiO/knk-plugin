package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import net.knightsandkings.knk.core.menu.BannerPatternSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.knightsandkings.knk.core.siege.SiegeTestData.at;
import static net.knightsandkings.knk.core.siege.SiegeTestData.objective;
import static net.knightsandkings.knk.core.siege.SiegeTestData.team;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Phase 5: the objective banner's 8-stage capture gradient (legacy setBannerStages/setCurrentCapturePoints). */
class CaptureProgressGradientTest {

    /** Legacy index: ceil(current/original*8) with 0 → 1 and a non-zero 1 → 2, minus one. */
    private static int legacyIndex(int current, int original) {
        int part = (int) Math.ceil(((float) current) / original * 8);
        if (current <= 0) part = 1;
        else if (part == 1) part = 2;
        return Math.max(0, part - 1);
    }

    @Test
    void indexMatchesTheLegacyFormulaForEveryPointValue() {
        for (int points = 0; points <= 500; points++) {
            assertEquals(legacyIndex(points, 500), CaptureProgressGradient.index(points, 500), "points " + points);
        }
        assertEquals(0, CaptureProgressGradient.index(0, 500));
        assertEquals(1, CaptureProgressGradient.index(1, 500), "one point left still shows the attacker gradient, not captured");
        assertEquals(7, CaptureProgressGradient.index(500, 500));
        assertEquals(0, CaptureProgressGradient.index(10, 0), "no capture points: shown as captured");
    }

    @Test
    void bannerStagesGoFromAttackerToHolder() {
        BannerPatternSpec captured = CaptureProgressGradient.banner(0, "BLUE", "RED");
        assertEquals("RED", captured.baseColor());
        assertTrue(captured.layers().isEmpty());

        BannerPatternSpec full = CaptureProgressGradient.banner(7, "BLUE", "RED");
        assertEquals("BLUE", full.baseColor());
        assertTrue(full.layers().isEmpty());

        for (int index = 1; index <= 6; index++) {
            BannerPatternSpec spec = CaptureProgressGradient.banner(index, "BLUE", "RED");
            assertEquals("BLUE", spec.baseColor(), "v3 uses the holder colour on every stage (v1 hard-coded RED on stage 6)");
            assertEquals(index, spec.layers().size(), "gradient + (index-1) gradient_up");
            assertEquals(new BannerPatternSpec.Layer("minecraft:gradient", "RED"), spec.layers().get(0));
            spec.layers().subList(1, index).forEach(l ->
                    assertEquals(new BannerPatternSpec.Layer("minecraft:gradient_up", "BLUE"), l));
            assertTrue(spec.errors().isEmpty());
        }
    }

    @Test
    void missingColoursFallBackToWhiteAndTheHolder() {
        assertEquals("WHITE", CaptureProgressGradient.banner(7, null, null).baseColor());
        assertEquals("GREEN", CaptureProgressGradient.banner(0, "GREEN", null).baseColor());
    }

    @Test
    void leadingAttackerIsTheEnemyTeamWithMostMembersPresent() {
        KnkSiegeScenario scenario = SiegeTestData.scenario(1, 5,
                List.of(team(1, SiegeTeamRole.DEFENDER, 1, "D"), team(2, SiegeTeamRole.ATTACKER, 2, "A"),
                        team(3, SiegeTeamRole.ATTACKER, 3, "R")),
                List.of(objective(1, true, 1)), false);
        AllianceResolver alliances = AllianceResolver.of(scenario);
        List<Integer> order = List.of(1, 2, 3);

        assertEquals(3, CaptureProgressGradient.leadingAttacker(
                List.of(at(1, 2, 1), at(2, 3, 1), at(3, 3, 2), at(4, 1, 1), at(5, 1, 1), at(6, 1, 1)), 1, alliances, order, null));
        assertEquals(2, CaptureProgressGradient.leadingAttacker(List.of(at(1, 2, 1), at(2, 3, 1)), 1, alliances, order, null),
                "tie: first in scenario order");
        assertEquals(3, CaptureProgressGradient.leadingAttacker(List.of(), 1, alliances, order, 3), "nobody present: keep the previous");
        assertEquals(2, CaptureProgressGradient.leadingAttacker(null, 1, alliances, order, null), "never attacked: first enemy team");
        assertEquals(1, CaptureProgressGradient.leadingAttacker(List.of(), 2, alliances, order, 2),
                "a previous attacker that now holds it isn't an attacker any more");
        assertNull(CaptureProgressGradient.leadingAttacker(List.of(), 1,
                AllianceResolver.of(SiegeTestData.scenario(2, 5, List.of(team(1, SiegeTeamRole.DEFENDER, 1, "D")),
                        List.of(), false)), List.of(1), null));
    }

    @Test
    void objectiveBannerShowsFullTeamBannersAtTheEndsAndTheGradientBetween() {
        BannerPatternSpec lion = BannerPatternSpec.parse("BLUE|minecraft:cross:YELLOW");
        BannerPatternSpec skull = BannerPatternSpec.parse("RED|minecraft:skull:BLACK");

        assertEquals(lion, CaptureProgressGradient.objectiveBanner(500, 500, false, lion, "BLUE", skull, "RED"));
        assertEquals(skull, CaptureProgressGradient.objectiveBanner(0, 500, false, lion, "BLUE", skull, "RED"));
        BannerPatternSpec half = CaptureProgressGradient.objectiveBanner(250, 500, false, lion, "BLUE", skull, "RED");
        assertEquals("BLUE", half.baseColor());
        assertEquals("minecraft:gradient", half.layers().get(0).patternKey());
        assertEquals("RED", half.layers().get(0).color());
    }

    @Test
    void anObjectiveCapturedForGoodShowsItsNewHoldersBanner_andMissingDesignsUsePlainColours() {
        BannerPatternSpec skull = BannerPatternSpec.parse("RED|minecraft:skull:BLACK");
        // Final capture: points stay 0, the capturer is the holder, the "attacker" is the old holder.
        assertEquals(skull, CaptureProgressGradient.objectiveBanner(0, 500, true, skull, "RED", null, "BLUE"));

        BannerPatternSpec plain = CaptureProgressGradient.objectiveBanner(500, 500, false, null, "GREEN", null, null);
        assertEquals("GREEN", plain.baseColor());
        assertTrue(plain.layers().isEmpty());
    }
}
