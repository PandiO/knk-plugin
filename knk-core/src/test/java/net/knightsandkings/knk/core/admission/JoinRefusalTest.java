package net.knightsandkings.knk.core.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** PandiO/knk-workspace#159: refused-join notice text and its per-player throttle. */
class JoinRefusalTest {

    private static final UUID STEVE = UUID.nameUUIDFromBytes("Steve".getBytes());

    @Test
    void describesTheReasonAndDetail() {
        assertEquals("Steve tried to join and was refused: banned (griefing)",
            new JoinRefusal(STEVE, "Steve", JoinRefusal.Reason.BANNED, " griefing ").describe());
        assertEquals("Steve tried to join and was refused: not on the whitelist",
            new JoinRefusal(STEVE, "Steve", JoinRefusal.Reason.NOT_WHITELISTED, "").describe());
    }

    @Test
    void admissionKeys() {
        assertEquals(JoinRefusal.Reason.NOT_ADMITTED, JoinRefusal.fromAdmissionKey(AdmissionPolicy.KICK));
        assertEquals(JoinRefusal.Reason.DEACTIVATED, JoinRefusal.fromAdmissionKey(AdmissionPolicy.DEACTIVATED));
        assertEquals(JoinRefusal.Reason.ACCESS_UNCHECKABLE, JoinRefusal.fromAdmissionKey(AdmissionPolicy.UNAVAILABLE));
        assertEquals(JoinRefusal.Reason.OTHER, JoinRefusal.fromAdmissionKey("something.else"));
    }

    @Test
    void oneNoticePerPlayerPerCooldown() {
        Instant[] now = {Instant.parse("2026-10-26T12:00:00Z")};
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now[0]; }
        };
        JoinRefusalThrottle throttle = new JoinRefusalThrottle(Duration.ofSeconds(60), clock);
        UUID alex = UUID.nameUUIDFromBytes("Alex".getBytes());

        assertTrue(throttle.tryAcquire(STEVE));
        assertFalse(throttle.tryAcquire(STEVE));
        assertTrue(throttle.tryAcquire(alex));
        now[0] = now[0].plusSeconds(60);
        assertTrue(throttle.tryAcquire(STEVE));
    }
}
