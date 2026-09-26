package net.knightsandkings.knk.core.messaging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.messaging.SpyRules.Participant;
import net.knightsandkings.knk.core.messaging.SpyRules.Spy;

/** Social spy audience (DESIGN.md §3.3.4; staff + owners, owners' PMs hidden from staff). */
class SpyRulesTest {

    private static ParticipantId id(String name) {
        return ParticipantId.player(UUID.nameUUIDFromBytes(name.getBytes()));
    }

    private static final Participant ALICE = new Participant(id("Alice"), false);
    private static final Participant BOB = new Participant(id("Bob"), false);
    private static final Participant OWNER = new Participant(id("Owner"), true);

    private static final Spy STAFF = new Spy(id("Staff"), true, true, false);
    private static final Spy OTHER_OWNER = new Spy(id("Owner2"), true, true, true);

    @Test
    void staffSeesOrdinaryPm() {
        assertTrue(SpyRules.shouldSee(STAFF, ALICE, BOB));
    }

    @Test
    void participantsAreExcluded() {
        Spy staffAsSpy = new Spy(id("Alice"), true, true, false);

        assertFalse(SpyRules.shouldSee(staffAsSpy, ALICE, BOB));
        assertFalse(SpyRules.shouldSee(staffAsSpy, BOB, ALICE));
    }

    @Test
    void toggleOff_seesNothing() {
        assertFalse(SpyRules.shouldSee(new Spy(id("Staff"), true, false, false), ALICE, BOB));
    }

    @Test
    void withoutTheNode_seesNothing() {
        assertFalse(SpyRules.shouldSee(new Spy(id("Player"), false, true, false), ALICE, BOB));
    }

    @Test
    void exemptSender_hiddenFromNonExemptSpy() {
        assertFalse(SpyRules.shouldSee(STAFF, OWNER, BOB));
    }

    @Test
    void exemptRecipient_hiddenFromNonExemptSpy() {
        assertFalse(SpyRules.shouldSee(STAFF, BOB, OWNER));
    }

    @Test
    void exemptSpy_seesExemptPm() {
        assertTrue(SpyRules.shouldSee(OTHER_OWNER, OWNER, BOB));
        assertTrue(SpyRules.shouldSee(OTHER_OWNER, ALICE, BOB));
    }

    @Test
    void consoleParticipant_isNotExempt() {
        Participant console = new Participant(ParticipantId.CONSOLE, false);

        assertTrue(SpyRules.shouldSee(STAFF, console, ALICE));
    }
}
