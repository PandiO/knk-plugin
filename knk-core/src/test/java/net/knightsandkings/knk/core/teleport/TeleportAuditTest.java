package net.knightsandkings.knk.core.teleport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which profile a staff teleport's audit entry lands on (docs/specs/teleport/DESIGN.md §3.10). */
class TeleportAuditTest {

    private static final TeleportAudit.Point HERE = new TeleportAudit.Point("world", 0, 64, 0);
    private static final TeleportAudit.Point THERE = new TeleportAudit.Point("world", 100, 64, 100);

    private static TeleportAudit audit(Integer actor, int subject, Integer visited) {
        return new TeleportAudit(TeleportKind.STAFF, actor, subject, visited, HERE, THERE, false, null, actor == null);
    }

    @Test
    void staffGoingToAPlayerIsFiledUnderTheVisitedPlayer() {
        assertEquals(2, audit(1, 1, 2).targetUserId()); // /tp Bob
    }

    @Test
    void movingSomeoneElseIsFiledUnderTheMovedPlayer() {
        assertEquals(3, audit(1, 3, 2).targetUserId()); // /tp Carol Bob
        assertEquals(3, audit(1, 3, 1).targetUserId()); // /tphere Carol
    }

    @Test
    void staffGoingToCoordinatesIsFiledUnderThemselves() {
        assertEquals(1, audit(1, 1, null).targetUserId()); // /tp 0 64 0
    }

    @Test
    void consoleOrUnresolvedActorFilesUnderTheMovedPlayer() {
        assertEquals(3, audit(null, 3, 2).targetUserId());
    }
}
