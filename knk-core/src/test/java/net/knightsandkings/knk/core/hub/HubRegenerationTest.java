package net.knightsandkings.knk.core.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.settings.KnkHubRules;

/** Hub DESIGN §16.5: the default hub regeneration is vanilla 1.21.10 Peaceful. */
class HubRegenerationTest {

    private static final KnkHubRules PEACEFUL = KnkHubRules.defaults();

    @Test
    void oneSecondOfPeacefulHealsOneSaturatesOneAndFeedsTwo() {
        HubRegeneration.Vitals vitals = new HubRegeneration.Vitals(10, 20, 10, 0f);
        for (long tick = 1; tick <= 20; tick++) {
            vitals = HubRegeneration.tick(PEACEFUL, tick, vitals);
        }
        assertEquals(11, vitals.health());
        assertEquals(1f, vitals.saturation());
        assertEquals(12, vitals.food());
    }

    @Test
    void nothingGoesOverItsMaximumOrHealsTheDead() {
        HubRegeneration.Vitals full = HubRegeneration.tick(PEACEFUL, 20, new HubRegeneration.Vitals(19.5, 20, 20, 19.5f));
        assertEquals(20, full.health());
        assertEquals(20, full.food());
        assertEquals(20f, full.saturation());
        assertEquals(0, HubRegeneration.tick(PEACEFUL, 20, new HubRegeneration.Vitals(0, 20, 5, 0f)).health());
    }

    @Test
    void aZeroAmountTurnsItOffAndNothingIsDueBetweenIntervals() {
        KnkHubRules noHealing = new KnkHubRules(false, false, false, false, true, true, true, false, false, 0, 20, 0, 10, 0, 20);
        assertFalse(HubRegeneration.anythingDue(noHealing, 20));
        assertFalse(HubRegeneration.anythingDue(PEACEFUL, 7));
        assertTrue(HubRegeneration.anythingDue(PEACEFUL, 10));
        assertEquals(10, HubRegeneration.tick(noHealing, 20, new HubRegeneration.Vitals(10, 20, 10, 0f)).health());
    }
}
