package net.knightsandkings.knk.core.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.messaging.ReplyTargets.Outcome;
import net.knightsandkings.knk.core.messaging.ReplyTargets.Presence;

/** docs/specs/private-messages/DESIGN.md §3.3.3 reply-target rules. */
class ReplyTargetsTest {

    private static final ParticipantId ALICE = ParticipantId.player(UUID.nameUUIDFromBytes("Alice".getBytes()));
    private static final ParticipantId BOB = ParticipantId.player(UUID.nameUUIDFromBytes("Bob".getBytes()));
    private static final ParticipantId STAFF = ParticipantId.player(UUID.nameUUIDFromBytes("Staff".getBytes()));

    private final ReplyTargets targets = new ReplyTargets(new MutableClock(Instant.parse("2026-09-26T20:00:00Z")));
    /** Presence of each partner; anything not listed is online and visible. */
    private final Map<ParticipantId, Presence> presence = new HashMap<>();

    private ReplyTargets.Resolution resolve(ParticipantId replier) {
        return targets.resolve(replier, (who, partner) -> presence.getOrDefault(partner, Presence.ONLINE_VISIBLE));
    }

    @Test
    void noLink_isNoTarget() {
        ReplyTargets.Resolution resolution = resolve(ALICE);

        assertEquals(Outcome.NO_TARGET, resolution.outcome());
        assertNull(resolution.link());
    }

    @Test
    void deliveredMessage_linksBothWays() {
        targets.recordDelivered(ALICE, "Alice", BOB, "Bob", true, true);

        assertEquals(BOB, resolve(ALICE).link().partner());
        assertEquals(ALICE, resolve(BOB).link().partner());
        assertEquals("Alice", resolve(BOB).link().partnerName());
        assertEquals(Outcome.OK, resolve(ALICE).outcome());
        assertEquals(Outcome.OK, resolve(BOB).outcome());
    }

    @Test
    void blockedMessage_isNeverRecorded_soSetsNoLink() {
        // The caller only calls recordDelivered for delivered messages.
        targets.recordDelivered(ALICE, "Alice", BOB, "Bob", true, true);

        assertEquals(Outcome.NO_TARGET, resolve(STAFF).outcome());
        assertEquals(BOB, resolve(ALICE).link().partner());
    }

    @Test
    void laterMessage_movesTheLink() {
        targets.recordDelivered(ALICE, "Alice", BOB, "Bob", true, true);
        targets.recordDelivered(STAFF, "Staff", ALICE, "Alice", true, true);

        assertEquals(STAFF, resolve(ALICE).link().partner());
        assertEquals(ALICE, resolve(BOB).link().partner());
    }

    @Test
    void vanishedPartner_reachableWhenTheyStartedTheConversation() {
        targets.recordDelivered(STAFF, "Staff", ALICE, "Alice", true, false);
        presence.put(STAFF, Presence.ONLINE_HIDDEN);

        assertEquals(Outcome.OK, resolve(ALICE).outcome());
    }

    @Test
    void vanishedPartner_staysReachableWhileTheConversationContinues() {
        targets.recordDelivered(STAFF, "Staff", ALICE, "Alice", true, false);
        presence.put(STAFF, Presence.ONLINE_HIDDEN);
        // Alice answers with /r, then wants to /r again.
        targets.recordDelivered(ALICE, "Alice", STAFF, "Staff", false, true);

        assertTrue(resolve(ALICE).link().initiatedByPartner());
        assertEquals(Outcome.OK, resolve(ALICE).outcome());
    }

    @Test
    void vanishedPartner_notRevealedWhenTheReplierStartedTheConversation() {
        targets.recordDelivered(ALICE, "Alice", STAFF, "Staff", true, true);
        presence.put(STAFF, Presence.ONLINE_HIDDEN); // staff vanished afterwards

        assertEquals(Outcome.NOT_FOUND, resolve(ALICE).outcome());
    }

    @Test
    void offlineVanishedPartner_isNotNamed() {
        // A vanished staff member messages Alice, then logs out.
        targets.recordDelivered(STAFF, "Staff", ALICE, "Alice", true, false);
        presence.put(STAFF, Presence.OFFLINE);

        assertEquals(Outcome.NOT_FOUND, resolve(ALICE).outcome());
    }

    @Test
    void offlinePartner_visibleWhenLinked_isPartnerOffline() {
        targets.recordDelivered(ALICE, "Alice", BOB, "Bob", true, true);
        presence.put(BOB, Presence.OFFLINE);

        ReplyTargets.Resolution resolution = resolve(ALICE);
        assertEquals(Outcome.PARTNER_OFFLINE, resolution.outcome());
        assertEquals("Bob", resolution.link().partnerName());
    }

    @Test
    void consolePartner_isAlwaysReachable() {
        targets.recordDelivered(ParticipantId.CONSOLE, "CONSOLE", ALICE, "Alice", true, true);
        presence.put(ParticipantId.CONSOLE, Presence.OFFLINE); // ignored for the console

        assertEquals(Outcome.OK, resolve(ALICE).outcome());
        assertTrue(resolve(ALICE).link().partner().isConsole());
        assertEquals(ALICE, resolve(ParticipantId.CONSOLE).link().partner());
    }

    @Test
    void forget_dropsOwnLink_keepsInboundLinks() {
        targets.recordDelivered(ALICE, "Alice", BOB, "Bob", true, true);

        targets.forget(BOB);

        assertEquals(Outcome.NO_TARGET, resolve(BOB).outcome());
        assertEquals(BOB, resolve(ALICE).link().partner());
    }

    @Test
    void relinkToADifferentPartner_resetsInitiatedByPartner() {
        targets.recordDelivered(STAFF, "Staff", ALICE, "Alice", true, false);
        targets.recordDelivered(ALICE, "Alice", BOB, "Bob", true, true);

        assertFalse(resolve(ALICE).link().initiatedByPartner());
    }
}
