package net.knightsandkings.knk.core.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Private-message rate limit: 5 per 5 s window, duplicates within 10 s (DESIGN.md §3.3.6). */
class RateLimiterTest {

    private static final ParticipantId ALICE = ParticipantId.player(UUID.nameUUIDFromBytes("Alice".getBytes()));
    private static final ParticipantId BOB = ParticipantId.player(UUID.nameUUIDFromBytes("Bob".getBytes()));
    private static final ParticipantId CAROL = ParticipantId.player(UUID.nameUUIDFromBytes("Carol".getBytes()));

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-26T20:00:00Z"));
    private final RateLimiter limiter = new RateLimiter(5, Duration.ofSeconds(5), Duration.ofSeconds(10), clock);

    private Optional<Duration> send(String text) {
        return limiter.tryAcquire(ALICE, BOB, text, false);
    }

    @Test
    void allowsUpToTheLimitInsideTheWindow() {
        for (int i = 0; i < 5; i++) {
            assertTrue(send("hi " + i).isEmpty(), "message " + i);
            clock.advance(Duration.ofMillis(500));
        }
    }

    @Test
    void sixthMessageInTheWindow_isRefusedWithTheWait() {
        for (int i = 0; i < 5; i++) {
            send("hi " + i);
            clock.advance(Duration.ofSeconds(1)); // sent at t=0..4
        }
        // now t=5: the first message just left the 5 s window
        assertTrue(send("again").isEmpty());

        Optional<Duration> wait = send("too many"); // t=5: window holds t=1..5
        assertTrue(wait.isPresent());
        assertEquals(1, RateLimiter.waitSeconds(wait.get()));
    }

    @Test
    void refusedAttempts_areNotRecorded() {
        for (int i = 0; i < 5; i++) {
            send("hi " + i);
        }
        assertTrue(send("x").isPresent());
        assertTrue(send("y").isPresent());

        clock.advance(Duration.ofSeconds(5).plusMillis(1));
        assertTrue(send("z").isEmpty());
    }

    @Test
    void windowSlides() {
        for (int i = 0; i < 5; i++) {
            send("hi " + i);
        }
        clock.advance(Duration.ofSeconds(4));
        Optional<Duration> wait = send("later");
        assertEquals(1, RateLimiter.waitSeconds(wait.orElseThrow()));

        clock.advance(Duration.ofSeconds(1).plusMillis(1));
        assertTrue(send("later").isEmpty());
    }

    @Test
    void duplicateToSameRecipient_isSpam() {
        assertTrue(send("buy my stuff").isEmpty());
        clock.advance(Duration.ofSeconds(3));

        Optional<Duration> wait = send("  Buy my STUFF ");
        assertEquals(7, RateLimiter.waitSeconds(wait.orElseThrow()));

        clock.advance(Duration.ofSeconds(7).plusMillis(1));
        assertTrue(send("buy my stuff").isEmpty());
    }

    @Test
    void sameTextToADifferentRecipient_isNotADuplicate() {
        assertTrue(send("hello").isEmpty());
        assertTrue(limiter.tryAcquire(ALICE, CAROL, "hello", false).isEmpty());
    }

    @Test
    void limitIsPerSender() {
        for (int i = 0; i < 5; i++) {
            send("hi " + i);
        }
        assertTrue(limiter.tryAcquire(CAROL, BOB, "hi", false).isEmpty());
    }

    @Test
    void bypass_isNeverLimitedAndNotRecorded() {
        for (int i = 0; i < 20; i++) {
            assertTrue(limiter.tryAcquire(ALICE, BOB, "same", true).isEmpty());
        }
        assertTrue(send("same").isEmpty());
    }

    @Test
    void forget_clearsHistory() {
        for (int i = 0; i < 5; i++) {
            send("hi " + i);
        }
        limiter.forget(ALICE);

        assertTrue(send("fresh").isEmpty());
    }

    @Test
    void gate_exemptsConsoleAndBypassNode() {
        RateLimitGate gate = new RateLimitGate(new RateLimiter(1, Duration.ofSeconds(5), Duration.ofSeconds(10), clock));
        PrivateMessageGate.SendAttempt console = new PrivateMessageGate.SendAttempt(
                ParticipantId.CONSOLE, BOB, "hi", false, false, null, null);
        PrivateMessageGate.SendAttempt staff = new PrivateMessageGate.SendAttempt(
                ALICE, BOB, "hi", false, false, java.util.Set.of(PrivateMessageNodes.BYPASS_RATE_LIMIT), null);
        PrivateMessageGate.SendAttempt player = new PrivateMessageGate.SendAttempt(
                CAROL, BOB, "hi", false, false, null, null);

        assertTrue(gate.check(console).isEmpty());
        assertTrue(gate.check(console).isEmpty());
        assertTrue(gate.check(staff).isEmpty());
        assertTrue(gate.check(staff).isEmpty());
        assertTrue(gate.check(player).isEmpty());
        PrivateMessageGate.Denial denial = gate.check(new PrivateMessageGate.SendAttempt(
                CAROL, BOB, "something else", false, false, null, null)).orElseThrow();
        assertEquals(PrivateMessageGate.Reason.RATE_LIMITED, denial.reason());
        assertEquals("Slow down — you can send another message in 5s.", denial.message());
    }
}
