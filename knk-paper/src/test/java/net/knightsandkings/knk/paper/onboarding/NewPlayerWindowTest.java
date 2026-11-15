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
    void aFirstJoinIsANewAccountOrAnExistingAccountTheServerHasNotSeen() {
        assertTrue(NewPlayerWindow.isFirstJoin(true, false), "game-first: the API created the account at this join");
        assertTrue(NewPlayerWindow.isFirstJoin(false, false),
            "web-first or /link: the account exists, the player has no data on this server yet");
        assertFalse(NewPlayerWindow.isFirstJoin(false, true), "a returning player");
        assertTrue(NewPlayerWindow.isFirstJoin(true, true), "a new account always counts, whatever the player data says");
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

    /**
     * #486: an erased account's player keeps Minecraft's player files (hasPlayedBefore) and may get an account back
     * through a claim or the website (not created at this join): without the erasure's mark that was "Welcome back".
     */
    @Test
    void aPlayerWhoseAccountWasErased_IsOnTheirFirstJoinAgain() {
        assertFalse(NewPlayerWindow.isFirstJoin(false, true), "the bug: kept player files and a claimed account");
        assertTrue(NewPlayerWindow.isFirstJoin(false, true, true), "the erasure's mark makes it a first join");
        assertFalse(NewPlayerWindow.isFirstJoin(false, true, false), "a returning player without an erasure");
    }

    @Test
    void forget_GivesAnErasedPlayerANewWindow_InTheSameServerSession() {
        MovableClock clock = new MovableClock();
        NewPlayerWindow window = new NewPlayerWindow(clock, Duration.ofMinutes(10));
        window.markFirstJoin(alice);
        assertTrue(window.takeFirstJoinWelcome(alice));
        clock.advance(Duration.ofMinutes(30));
        assertFalse(window.isQuiet(alice));

        assertTrue(window.forget(alice) == 1);
        window.markFirstJoin(alice);

        assertTrue(window.takeFirstJoinWelcome(alice), "the new account's first join gets the welcome again");
        assertTrue(window.isQuiet(alice), "and its own quiet minutes, from the new first join");
        assertTrue(window.forget(bob) == 0);
    }
}
