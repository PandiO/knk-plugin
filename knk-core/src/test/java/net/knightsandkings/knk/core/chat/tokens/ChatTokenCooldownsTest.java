package net.knightsandkings.knk.core.chat.tokens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** PandiO/knk-workspace#233: the statistics-token cooldown and the "never ask" answers. */
class ChatTokenCooldownsTest {

    private Instant now = Instant.parse("2026-11-04T12:00:00Z");
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };
    private final UUID steve = UUID.randomUUID();

    @Test
    void offMeansAlwaysAllowed() {
        var cooldowns = new ChatTokenCooldowns(clock);
        assertEquals(Duration.ZERO, cooldowns.tryUse(steve, 0));
        assertEquals(Duration.ZERO, cooldowns.tryUse(steve, 0));
    }

    @Test
    void aSecondUseWithinTheCooldownIsRefused_WithTheTimeLeft_AndNotRecorded() {
        var cooldowns = new ChatTokenCooldowns(clock);
        assertEquals(Duration.ZERO, cooldowns.tryUse(steve, 10));
        now = now.plusSeconds(4);
        assertEquals(Duration.ofSeconds(6), cooldowns.tryUse(steve, 10));
        now = now.plusSeconds(6);
        assertEquals(Duration.ZERO, cooldowns.tryUse(steve, 10));
        assertEquals(Duration.ZERO, cooldowns.tryUse(UUID.randomUUID(), 10), "per player");
    }

    @Test
    void neverAskIsPerSetting_AndSurvivesASnapshotRoundTrip() {
        var consent = new ShareConsent();
        assertFalse(consent.neverAskAll(steve, List.of()));
        consent.addNeverAsk(steve, List.of("pvp_kills"));
        assertTrue(consent.neverAskAll(steve, List.of("pvp_kills")));
        assertFalse(consent.neverAskAll(steve, List.of("pvp_kills", "deaths")));

        var copy = new ShareConsent();
        copy.load(consent.snapshot());
        assertTrue(copy.neverAsk(steve, "pvp_kills"));
        copy.clear(steve, List.of("pvp_kills"));
        assertEquals(Map.of(), copy.snapshot());
        assertEquals(Map.of(steve, Set.of("pvp_kills")), consent.snapshot());
    }
}
