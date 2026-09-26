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

/** Frozen players may only message knk.freeze holders (DESIGN.md §4 D10), and the gate chain order. */
class FrozenGateTest {

    private static final ParticipantId ALICE = ParticipantId.player(UUID.nameUUIDFromBytes("Alice".getBytes()));
    private static final ParticipantId STAFF = ParticipantId.player(UUID.nameUUIDFromBytes("Staff".getBytes()));

    private final FrozenGate gate = new FrozenGate();

    @Test
    void notFrozen_passes() {
        assertTrue(gate.check(new SendAttempt(ALICE, STAFF, "hi", false, false, null, null)).isEmpty());
    }

    @Test
    void frozen_toFreezeHolder_passes() {
        assertTrue(gate.check(new SendAttempt(ALICE, STAFF, "hi", true, true, null, Set.of(PrivateMessageNodes.FREEZE))).isEmpty());
    }

    @Test
    void frozen_toConsole_passes() {
        assertTrue(gate.check(new SendAttempt(ALICE, ParticipantId.CONSOLE, "hi", true, true, null, null)).isEmpty());
    }

    @Test
    void frozen_toAnyoneElse_isRefused() {
        Denial denial = gate.check(new SendAttempt(ALICE, STAFF, "hi", false, true, null, Set.of())).orElseThrow();

        assertEquals(PrivateMessageGate.Reason.FROZEN, denial.reason());
        assertEquals("You can only message staff while frozen.", denial.message());
    }

    @Test
    void chain_firstDenialWins_andLaterGatesDoNotRun() {
        RateLimiter limiter = new RateLimiter(1, Duration.ofSeconds(5), Duration.ofSeconds(10),
                new MutableClock(Instant.parse("2026-09-26T20:00:00Z")));
        List<PrivateMessageGate> chain = List.of(gate, new RateLimitGate(limiter));
        SendAttempt frozen = new SendAttempt(ALICE, STAFF, "hi", false, true, null, null);

        Optional<Denial> denial = PrivateMessageGate.firstDenial(chain, frozen);
        assertEquals(PrivateMessageGate.Reason.FROZEN, denial.orElseThrow().reason());

        // The frozen attempt never reached the rate limiter, so this first real message passes.
        assertTrue(PrivateMessageGate.firstDenial(chain, new SendAttempt(ALICE, STAFF, "hi", false, false, null, null)).isEmpty());
    }
}
