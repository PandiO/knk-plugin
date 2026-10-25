package net.knightsandkings.knk.core.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.admission.AdmissionPolicy.Allow;
import net.knightsandkings.knk.core.admission.AdmissionPolicy.Decision;
import net.knightsandkings.knk.core.admission.AdmissionPolicy.Deny;
import net.knightsandkings.knk.core.admission.AdmissionPolicy.Mode;
import net.knightsandkings.knk.core.domain.admission.AdmissionResult;

class AdmissionPolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final AdmissionResult CODE = new AdmissionResult(false, AdmissionResult.REASON_NOT_ADMITTED,
        "K7QH-M2XA", NOW.plusSeconds(15 * 60));
    private static final AdmissionResult DEACTIVATED = new AdmissionResult(false, AdmissionResult.REASON_DEACTIVATED,
        null, null);

    @Test
    void whitelistAndOpenAllowEveryoneWithoutAsking() {
        for (Mode mode : new Mode[] {Mode.WHITELIST, Mode.OPEN}) {
            assertFalse(AdmissionPolicy.needsCheck(mode, false));
            assertInstanceOf(Allow.class, AdmissionPolicy.decide(mode, false, CODE, false, NOW));
            assertInstanceOf(Allow.class, AdmissionPolicy.decide(mode, false, null, false, NOW));
            assertInstanceOf(Allow.class, AdmissionPolicy.decide(mode, false, DEACTIVATED, false, NOW));
        }
    }

    @Test
    void anOperatorBypassesTheInviteGate() {
        assertFalse(AdmissionPolicy.needsCheck(Mode.INVITE, true));
        assertInstanceOf(Allow.class, AdmissionPolicy.decide(Mode.INVITE, true, null, false, NOW));
        assertInstanceOf(Allow.class, AdmissionPolicy.decide(Mode.INVITE, true, DEACTIVATED, false, NOW));
    }

    @Test
    void anAdmittedPlayerGetsIn() {
        assertTrue(AdmissionPolicy.needsCheck(Mode.INVITE, false));
        assertInstanceOf(Allow.class,
            AdmissionPolicy.decide(Mode.INVITE, false, AdmissionResult.admittedResult(), false, NOW));
    }

    @Test
    void aStrangerGetsTheClaimCodeAndItsMinutes() {
        Decision decision = AdmissionPolicy.decide(Mode.INVITE, false, CODE, true, NOW);

        Deny deny = assertInstanceOf(Deny.class, decision);
        assertEquals(AdmissionPolicy.KICK, deny.messageKey());
        assertEquals(Map.of("code", "K7QH-M2XA", "minutes", "15"), deny.placeholders());
        assertFalse(deny.toString().contains("K7QH"), "the code is a credential");
    }

    @Test
    void aDeactivatedAccountIsRefusedEvenWhenKnownOffline() {
        Deny deny = assertInstanceOf(Deny.class, AdmissionPolicy.decide(Mode.INVITE, false, DEACTIVATED, true, NOW));
        assertEquals(AdmissionPolicy.DEACTIVATED, deny.messageKey());
        assertTrue(deny.placeholders().isEmpty());
    }

    @Test
    void apiUnreachableLetsAKnownPlayerInAndTurnsAStrangerAway() {
        assertInstanceOf(Allow.class, AdmissionPolicy.decide(Mode.INVITE, false, null, true, NOW));

        Deny deny = assertInstanceOf(Deny.class, AdmissionPolicy.decide(Mode.INVITE, false, null, false, NOW));
        assertEquals(AdmissionPolicy.UNAVAILABLE, deny.messageKey());
    }

    @Test
    void notAdmittedWithoutACodeIsTreatedAsUnavailable() {
        AdmissionResult noCode = new AdmissionResult(false, AdmissionResult.REASON_NOT_ADMITTED, null, null);

        assertInstanceOf(Allow.class, AdmissionPolicy.decide(Mode.INVITE, false, noCode, true, NOW));
        assertEquals(AdmissionPolicy.UNAVAILABLE,
            assertInstanceOf(Deny.class, AdmissionPolicy.decide(Mode.INVITE, false, noCode, false, NOW)).messageKey());
    }

    @Test
    void minutesComeFromExpiresAtRoundedUp() {
        assertEquals(15, AdmissionPolicy.minutesLeft(NOW.plusSeconds(15 * 60), NOW));
        assertEquals(10, AdmissionPolicy.minutesLeft(NOW.plusSeconds(9 * 60 + 1), NOW));
        assertEquals(30, AdmissionPolicy.minutesLeft(NOW.plusSeconds(30 * 60), NOW));
        assertEquals(1, AdmissionPolicy.minutesLeft(NOW.plusSeconds(5), NOW));
        assertEquals(1, AdmissionPolicy.minutesLeft(NOW.minusSeconds(5), NOW));
        assertEquals(AdmissionPolicy.DEFAULT_CLAIM_CODE_MINUTES, AdmissionPolicy.minutesLeft(null, NOW));
    }

    @Test
    void modeParsing() {
        assertEquals(Mode.INVITE, Mode.parse(" Invite "));
        assertEquals(Mode.WHITELIST, Mode.parse("whitelist"));
        assertEquals(Mode.OPEN, Mode.parse("OPEN"));
        assertNull(Mode.parse("closed"));
        assertNull(Mode.parse(""));
        assertNull(Mode.parse(null));
    }

    @Test
    void aResultNeverPrintsItsCode() {
        assertFalse(CODE.toString().contains("K7QH"));
    }
}
