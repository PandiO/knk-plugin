package net.knightsandkings.knk.core.npc.nav.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcMover.Contact;
import net.knightsandkings.knk.core.npc.nav.NpcMover.ContactKind;

/**
 * Push detection from the NPC's own movement (implementation plan 4c.4 item 7.2, link M7): its own walk, a stair step
 * and a turn are not pushes; a move back against its walking direction, faster than it walks, sideways right after a
 * contact, or away from where it stands is one; one push per episode; the pusher from the last contact.
 */
class PushWatchTest {

    private static NavPoint at(double x, double y, double z) {
        return new NavPoint("w", x, y, z);
    }

    /** Looks every 2 ticks while the NPC walks +x at 0.25 blocks per tick from x 0. */
    private static PushWatch walkingFor(int looks) {
        PushWatch watch = new PushWatch();
        for (int i = 0; i <= looks; i++) {
            assertNull(watch.look(at(0.5 + 0.5 * i, 65, 0.5), 2L * i, true, 1, 0), "its own walk, look " + i);
        }
        return watch;
    }

    @Test
    void itsOwnWalkIsNoPush() {
        walkingFor(20);
    }

    @Test
    void aStairStepIsNoPush() {
        PushWatch watch = walkingFor(3);
        // up a stair: a block higher and half a block on between two looks, then on the flight's next tread
        assertNull(watch.look(at(2.5, 66, 0.5), 8, true, 1, 0), "a step up is its own walk");
        assertNull(watch.look(at(3.0, 67, 0.5), 10, true, 1, 0), "the next step");
        assertNull(watch.look(at(3.5, 66, 0.5), 12, true, 1, 0), "and down again");
    }

    @Test
    void aTurnAtAWaypointIsNoPushWithoutAContact() {
        PushWatch watch = walkingFor(3);
        // round a corner: the next look is half a block sideways while its direction still says +x
        assertNull(watch.look(at(2.3, 65, 1.0), 8, true, 1, 0));
    }

    @Test
    void movedBackAgainstItsWalkIsAPush() {
        PushWatch watch = walkingFor(4);
        PushWatch.Push p = watch.look(at(2.0, 65, 0.5), 10, true, 1, 0);
        assertNotNull(p, "half a block back between two looks");
        assertEquals(at(2.5, 65, 0.5), p.from(), "from its last own position");
        assertTrue(p.walking());
        assertNull(p.kind(), "nothing the engine reported touched it");
        assertFalse(p.byPlayer());
    }

    @Test
    void fasterThanItWalksIsAPush() {
        PushWatch watch = walkingFor(4);
        assertNotNull(watch.look(at(4.0, 65, 0.5), 10, true, 1, 0), "1.5 blocks on in 2 ticks");
    }

    @Test
    void sidewaysRightAfterAPlayersContactIsAPushByThatPlayer() {
        PushWatch watch = walkingFor(4);
        watch.contact(new Contact(ContactKind.PLAYER, "steve"), 9);
        PushWatch.Push p = watch.look(at(2.9, 65, 0.9), 10, true, 1, 0);
        assertNotNull(p, "0.4 sideways while a player touches it");
        assertEquals(ContactKind.PLAYER, p.kind());
        assertEquals("steve", p.by());
        assertTrue(p.byPlayer());
    }

    @Test
    void aStandingNpcMovedFromWhereItStandsIsPushed() {
        PushWatch watch = new PushWatch();
        assertNull(watch.look(at(5.5, 65, 5.5), 0, false, 0, 0));
        assertNull(watch.look(at(5.7, 65, 5.5), 2, false, 0, 0), "0.2 is no push");
        assertNull(watch.look(at(5.8, 65, 5.5), 4, false, 0, 0), "0.3 from where it stands is none either");
        PushWatch.Push p = watch.look(at(6.0, 65, 5.5), 6, false, 0, 0);
        assertNotNull(p, "0.5 from where it stood: pushed, even slowly");
        assertEquals(at(5.5, 65, 5.5), p.from());
        assertFalse(p.walking());
    }

    @Test
    void onePushPerEpisodeThenAgain() {
        PushWatch watch = new PushWatch();
        watch.look(at(5.5, 65, 5.5), 0, false, 0, 0);
        watch.contact(new Contact(ContactKind.PLAYER, "steve"), 1);
        assertNotNull(watch.look(at(6.0, 65, 5.5), 2, false, 0, 0));
        for (int t = 4; t <= 20; t += 2) {
            watch.contact(new Contact(ContactKind.PLAYER, "steve"), t - 1);
            assertNull(watch.look(at(6.0 + 0.5 * (t - 2) / 2, 65, 5.5), t, false, 0, 0), "the same push, tick " + t);
        }
        assertEquals(1, watch.pushes());
        // still where the push left it: nothing new
        assertNull(watch.look(at(10.5, 65, 5.5), 60, false, 0, 0));
        // pushed again after the episode
        watch.contact(new Contact(ContactKind.PLAYER, "steve"), 61);
        assertNotNull(watch.look(at(11.5, 65, 5.5), 62, false, 0, 0));
        assertEquals(2, watch.pushes());
    }

    @Test
    void anAttackOutranksAPlayersBodyAndSaysWhetherAPlayerAttacked() {
        PushWatch watch = walkingFor(4);
        watch.contact(new Contact(ContactKind.PLAYER, "steve"), 8);
        watch.contact(new Contact(ContactKind.ATTACK, "steve", true), 9);
        PushWatch.Push p = watch.look(at(2.5, 65, 1.6), 10, true, 1, 0);
        assertNotNull(p, "knocked sideways");
        assertEquals(ContactKind.ATTACK, p.kind());
        assertTrue(p.byPlayer());
        PushWatch mob = walkingFor(4);
        mob.contact(new Contact(ContactKind.ATTACK, "zombie-uuid"), 9);
        PushWatch.Push q = mob.look(at(2.5, 65, 1.6), 10, true, 1, 0);
        assertNotNull(q);
        assertFalse(q.byPlayer(), "a mob's attack is no player's push");
    }

    @Test
    void anOldContactNamesNobody() {
        PushWatch watch = walkingFor(4);
        watch.contact(new Contact(ContactKind.NPC, "npc-7"), 9);
        assertEquals(ContactKind.NPC, watch.look(at(1.9, 65, 0.5), 10, true, 1, 0).kind());
        PushWatch later = walkingFor(10);
        later.contact(new Contact(ContactKind.NPC, "npc-7"), 2);
        assertNull(later.look(at(5.0, 65, 0.5), 22, true, 1, 0).kind(), "a contact 20 ticks ago is not the pusher");
    }

    @Test
    void aResetForgetsWhereItWas() {
        PushWatch watch = new PushWatch();
        watch.look(at(5.5, 65, 5.5), 0, false, 0, 0);
        watch.reset(at(20.5, 65, 5.5), false); // K&K teleported it home
        assertNull(watch.look(at(20.5, 65, 5.5), 2, false, 0, 0));
        assertEquals(at(20.5, 65, 5.5), watch.rest());
    }

    @Test
    void aContactNeedsAKind() {
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> new Contact(null, "x"));
        assertTrue(new Contact(ContactKind.PLAYER, "p").player(), "a player's body is a player");
        assertFalse(new NpcMover.Contact(ContactKind.OTHER, "e").player());
    }
}
