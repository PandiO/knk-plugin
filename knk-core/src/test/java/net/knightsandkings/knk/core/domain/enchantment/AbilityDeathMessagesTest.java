package net.knightsandkings.knk.core.domain.enchantment;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#222: death messages for area-ability kills. */
class AbilityDeathMessagesTest {

    @Test
    void theDefaultPatternNamesTheVictimTheEnchantmentAndThePlayer() {
        AbilityDeathMessages messages = new AbilityDeathMessages(null, null);
        assertEquals("Steve was killed with Flash Chaos by Alex",
                messages.format("flash_chaos", "Steve", "Alex", "Flash Chaos"));
    }

    @Test
    void aPatternPerEnchantmentWinsOverTheDefault_CaseIgnored() {
        AbilityDeathMessages messages = new AbilityDeathMessages("%victim% died to %enchantment% (%player%)",
                Map.of("chaos", "%victim% was blown apart by %player%'s %enchantment%"));
        assertEquals("Steve was blown apart by Alex's Chaos", messages.format("CHAOS", "Steve", "Alex", "Chaos"));
        assertEquals("Steve died to Flash Chaos (Alex)", messages.format("flash_chaos", "Steve", "Alex", "Flash Chaos"));
    }

    @Test
    void blankPatternsFallBack_AndAMissingNameShowsTheId() {
        AbilityDeathMessages messages = new AbilityDeathMessages(" ", Map.of("chaos", ""));
        assertEquals("Steve was killed with chaos by Alex", messages.format("chaos", "Steve", "Alex", null));
    }

    @Test
    void theHitIsKnownOnlyWhileTheDamageRuns() {
        AreaKillAttribution attribution = new AreaKillAttribution();
        UUID victim = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        List<Optional<AreaKillAttribution.Hit>> seen = new ArrayList<>();

        attribution.during(victim, new AreaKillAttribution.Hit("chaos", attacker, "Alex"),
                () -> seen.add(attribution.current(victim)));

        assertEquals(Optional.of(new AreaKillAttribution.Hit("chaos", attacker, "Alex")), seen.get(0));
        assertTrue(attribution.current(victim).isEmpty());
        assertTrue(attribution.current(UUID.randomUUID()).isEmpty());
    }

    @Test
    void theHitIsForgottenWhenTheDamageThrows_AndANestedHitRestoresTheOuterOne() {
        AreaKillAttribution attribution = new AreaKillAttribution();
        UUID victim = UUID.randomUUID();
        AreaKillAttribution.Hit outer = new AreaKillAttribution.Hit("chaos", UUID.randomUUID(), "Alex");
        AreaKillAttribution.Hit inner = new AreaKillAttribution.Hit("flash_chaos", UUID.randomUUID(), "Sam");
        List<Optional<AreaKillAttribution.Hit>> seen = new ArrayList<>();

        attribution.during(victim, outer, () -> {
            attribution.during(victim, inner, () -> seen.add(attribution.current(victim)));
            seen.add(attribution.current(victim));
        });
        assertEquals(List.of(Optional.of(inner), Optional.of(outer)), seen);

        assertThrows(IllegalStateException.class, () -> attribution.during(victim, outer, () -> {
            throw new IllegalStateException("boom");
        }));
        assertTrue(attribution.current(victim).isEmpty());
    }
}
