package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;
import net.knightsandkings.knk.core.siege.SiegeEffect.CancelReason;
import net.knightsandkings.knk.core.siege.SiegeLobbyStateMachine.SkipResult;
import net.knightsandkings.knk.core.siege.SiegePhase;
import net.knightsandkings.knk.core.siege.VoteTally.DrawMethod;
import net.knightsandkings.knk.core.siege.VoteTally.VoteResult;
import net.knightsandkings.knk.core.siege.WinResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Siege Phase 5 (N9): every vote/skip/cancel/end result maps to a sentence that says what happened
 * or why nothing did - no enum value may fall through to an empty or null message.
 */
class SiegeMessagesTest {

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void everyVoteResultHasAMessage() {
        for (VoteResult r : VoteResult.values()) {
            String text = plain(SiegeMessages.voteResult(r, "Siege of Cinix"));
            assertTrue(text.startsWith("[Siege] "), r + ": " + text);
            assertTrue(text.length() > "[Siege] ".length() + 5, r + ": " + text);
        }
        assertTrue(plain(SiegeMessages.voteResult(VoteResult.REMOVED, "X")).contains("withdrew"), "N17: un-vote is a normal result");
    }

    @Test
    void everySkipResultExplainsItselfForPlayersAndAdmins_N9() {
        for (SkipResult r : SkipResult.values()) {
            for (SiegePhase phase : SiegePhase.values()) {
                for (boolean admin : new boolean[]{false, true}) {
                    String text = plain(SiegeMessages.skipResult(r, "Cinix", phase, admin));
                    assertTrue(text.contains("Cinix"), r + "/" + phase + ": " + text);
                }
            }
        }
        String playerInMatch = plain(SiegeMessages.skipResult(SkipResult.NOT_SKIPPABLE, "Cinix", SiegePhase.IN_PROGRESS, false));
        assertTrue(playerInMatch.contains("only skip a cooldown"), playerInMatch);
        assertTrue(playerInMatch.contains("in progress"), playerInMatch);
    }

    @Test
    void cancelReasonsEndReasonsDrawMethodsAndDecisionsAreAllWorded() {
        for (CancelReason r : CancelReason.values()) assertFalse(SiegeMessages.cancelReason(r, 4).isBlank());
        assertTrue(SiegeMessages.cancelReason(CancelReason.NOT_ENOUGH_PLAYERS, 4).contains("4"));
        for (SiegeEndReason r : SiegeEndReason.values()) assertFalse(SiegeMessages.endReason(r).isBlank());
        for (DrawMethod m : DrawMethod.values()) assertFalse(SiegeMessages.drawMethod(m).isBlank());
        for (WinResolver.Decision d : WinResolver.Decision.values()) assertFalse(SiegeMessages.decision(d).isBlank());
        for (SiegePhase p : SiegePhase.values()) assertFalse(SiegeMessages.phaseLabel(p).isBlank());
    }

    @Test
    void durationsAndClocksReadNaturally() {
        assertEquals("45 seconds", SiegeMessages.duration(45));
        assertEquals("1 second", SiegeMessages.duration(1));
        assertEquals("5 minutes", SiegeMessages.duration(300));
        assertEquals("1 minute 30 seconds", SiegeMessages.duration(90));
        assertEquals("0 seconds", SiegeMessages.duration(-3));
        assertEquals("04:05", SiegeMessages.clock(245));
        assertEquals("00:00", SiegeMessages.clock(-1));
    }
}
