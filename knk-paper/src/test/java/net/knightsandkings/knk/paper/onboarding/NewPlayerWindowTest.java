package net.knightsandkings.knk.paper.onboarding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class NewPlayerWindowTest {
    private static final Instant START = Instant.parse("2026-10-11T12:00:00Z");
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    /** A clock that the test moves forward. */
    private static final class MovableClock extends Clock {
        private Instant now = START;

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void theFirstJoinWelcomeIsTakenOnceAndOnlyForAMarkedPlayer() {
        NewPlayerWindow window = new NewPlayerWindow(new MovableClock(), Duration.ofMinutes(10));
        window.markFirstJoin(alice);

        assertTrue(window.takeFirstJoinWelcome(alice));
        assertFalse(window.takeFirstJoinWelcome(alice), "a relog in the same session is a later join");
        assertFalse(window.takeFirstJoinWelcome(bob), "an existing account never gets the first-join welcome");
    }

    @Test
    void discoveriesAreQuietForTheConfiguredMinutesAfterTheFirstJoin() {
        MovableClock clock = new MovableClock();
        NewPlayerWindow window = new NewPlayerWindow(clock, Duration.ofMinutes(10));
        window.markFirstJoin(alice);

        assertTrue(window.isQuiet(alice));
        assertFalse(window.isQuiet(bob));
        clock.advance(Duration.ofMinutes(9).plusSeconds(59));
        assertTrue(window.isQuiet(alice));
        clock.advance(Duration.ofSeconds(1));
        assertFalse(window.isQuiet(alice));
    }

    @Test
    void aRelogDoesNotRestartTheQuietMinutes() {
        MovableClock clock = new MovableClock();
        NewPlayerWindow window = new NewPlayerWindow(clock, Duration.ofMinutes(10));
        window.markFirstJoin(alice);
        clock.advance(Duration.ofMinutes(8));
        window.markFirstJoin(alice);
        clock.advance(Duration.ofMinutes(3));

        assertFalse(window.isQuiet(alice));
    }

    @Test
    void zeroQuietMinutesHoldNothing() {
        NewPlayerWindow window = new NewPlayerWindow(new MovableClock(), Duration.ZERO);
        window.markFirstJoin(alice);

        assertFalse(window.isQuiet(alice));
        assertTrue(window.takeFirstJoinWelcome(alice), "the welcome does not depend on the quiet minutes");
    }
}
