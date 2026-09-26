package net.knightsandkings.knk.core.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.messaging.PrivateMessageGate.Denial;
import net.knightsandkings.knk.core.messaging.PrivateMessageGate.SendAttempt;

/** KNG-18 Phase 2: the ignore gate (DESIGN.md §3.3.5) and its place last in the chain. */
class IgnoreGateTest {

    private static final ParticipantId ALICE = ParticipantId.player(UUID.nameUUIDFromBytes("Alice".getBytes()));
    private static final ParticipantId BOB = ParticipantId.player(UUID.nameUUIDFromBytes("Bob".getBytes()));

    /** Alice ignores Bob. */
    private final IgnoreGate gate = new IgnoreGate((recipient, sender) -> recipient.equals(ALICE) && sender.equals(BOB));

    private static SendAttempt attempt(ParticipantId from, ParticipantId to, Set<String> senderNodes) {
        return new SendAttempt(from, to, "hi", false, false, senderNodes, null);
    }

    @Test
    void ignoredSender_isDenied() {
        Optional<Denial> denial = gate.check(attempt(BOB, ALICE, null));

        assertEquals(PrivateMessageGate.Reason.IGNORED, denial.orElseThrow().reason());
    }

    @Test
    void otherDirection_passes() {
        assertTrue(gate.check(attempt(ALICE, BOB, null)).isEmpty());
    }

    @Test
    void bypassNode_passes() {
        assertTrue(gate.check(attempt(BOB, ALICE, Set.of(PrivateMessageNodes.BYPASS_IGNORE))).isEmpty());
    }

    @Test
    void console_isNeverIgnored() {
        IgnoreGate everything = new IgnoreGate((recipient, sender) -> true);

        assertTrue(everything.check(attempt(ParticipantId.CONSOLE, ALICE, null)).isEmpty());
        assertTrue(everything.check(attempt(ALICE, ParticipantId.CONSOLE, null)).isEmpty());
    }

    @Test
    void noLookup_passes() {
        assertTrue(new IgnoreGate(IgnoreGate.Lookup.NONE).check(attempt(BOB, ALICE, null)).isEmpty());
    }

    @Test
    void rateLimit_winsOverIgnore() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-26T12:00:00Z"));
        RateLimiter limiter = new RateLimiter(1, Duration.ofSeconds(5), Duration.ofSeconds(10), clock);
        List<PrivateMessageGate> chain = List.of(new FrozenGate(), new RateLimitGate(limiter), gate);

        assertEquals(PrivateMessageGate.Reason.IGNORED,
                PrivateMessageGate.firstDenial(chain, attempt(BOB, ALICE, null)).orElseThrow().reason());
        assertEquals(PrivateMessageGate.Reason.RATE_LIMITED,
                PrivateMessageGate.firstDenial(chain, attempt(BOB, ALICE, null)).orElseThrow().reason());
    }
}
