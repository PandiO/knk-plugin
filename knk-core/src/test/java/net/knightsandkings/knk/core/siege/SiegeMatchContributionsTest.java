package net.knightsandkings.knk.core.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.siege.SiegeMatchContributions.Contribution;

/** Alpha run 1 (PandiO/knk-workspace#165/#166): gate damage and active time per match member. */
class SiegeMatchContributionsTest {

    private static final Instant T0 = Instant.parse("2026-10-27T20:00:00Z");
    private final UUID alice = UUID.randomUUID();
    private final SiegeMatchContributions c = new SiegeMatchContributions();

    private static Instant at(long seconds) {
        return T0.plusSeconds(seconds);
    }

    /** One observation per second in [from, to), all active. */
    private void activeEachSecond(UUID player, long from, long to) {
        for (long s = from; s < to; s++) c.observe(player, at(s), null);
    }

    @Test
    void anActiveMember_isActiveTheWholeMatch() {
        c.joined(alice, 7, at(0));
        activeEachSecond(alice, 0, 600);
        c.close(at(600), id -> null);

        assertEquals(new Contribution(0, 600, 600), c.of(alice, at(900)));
    }

    @Test
    void automaticAfk_countsFromTheLastActivity_andActivityResumesAtTheNextTick() {
        c.joined(alice, 7, at(0));
        activeEachSecond(alice, 0, 400);
        // The tracker reports AFK from 300 s (last activity) once the idle threshold passes at 600 s.
        for (long s = 400; s < 700; s++) c.observe(alice, at(s), s >= 600 ? at(300) : null);
        activeEachSecond(alice, 700, 1000);
        c.close(at(1000), id -> null);

        // Active 0-300 and 700-1000.
        assertEquals(new Contribution(0, 600, 1000), c.of(alice, at(1000)));
    }

    @Test
    void afkAtTheEnd_closesAtTheAfkStart() {
        c.joined(alice, 7, at(0));
        activeEachSecond(alice, 0, 500);
        c.close(at(600), id -> at(450));

        assertEquals(new Contribution(0, 450, 600), c.of(alice, at(600)));
    }

    @Test
    void aRejoinKeepsTheFirstJoinAsWindowStart_timeAwayIsNotActive() {
        c.joined(alice, 7, at(0));
        activeEachSecond(alice, 0, 200);
        c.left(alice, at(200), null);
        c.observe(alice, at(250), null); // not present: ignored
        c.joined(alice, 7, at(300));
        activeEachSecond(alice, 300, 600);
        c.close(at(600), id -> null);

        assertEquals(new Contribution(0, 500, 600), c.of(alice, at(600)));
    }

    @Test
    void aLeaverWindowEndsAtTheirLeave() {
        c.joined(alice, 7, at(0));
        activeEachSecond(alice, 0, 120);
        c.left(alice, at(120), null);
        c.close(at(600), id -> null);

        assertEquals(new Contribution(0, 120, 120), c.of(alice, at(600)));
    }

    @Test
    void aLateJoinerWindowStartsAtTheirJoin() {
        c.joined(alice, 7, at(300));
        activeEachSecond(alice, 300, 600);
        c.close(at(600), id -> null);

        assertEquals(new Contribution(0, 300, 300), c.of(alice, at(600)));
    }

    @Test
    void gateDamage_addsUpOverTheMatch_includingAcrossARejoin_onlyForMembers() {
        UUID stranger = UUID.randomUUID();
        c.joined(alice, 7, at(0));
        c.addGateDamage(alice, 40.5);
        c.left(alice, at(10), null);
        c.addGateDamage(alice, 10); // fire they lit still burns while they are away
        c.joined(alice, 7, at(20));
        c.addGateDamage(alice, 60);
        c.addGateDamage(alice, Double.NaN);
        c.addGateDamage(alice, -5);
        c.addGateDamage(stranger, 100);

        assertEquals(110.5, c.of(alice, at(30)).gateDamage());
        assertFalse(c.contains(stranger));
        assertEquals(Contribution.NONE, c.of(stranger, at(30)));
    }

    @Test
    void playersByUserId_mapsTheJoinedUserIds() {
        UUID bob = UUID.randomUUID();
        c.joined(alice, 7, at(0));
        c.joined(bob, null, at(0));

        assertEquals(Map.of(7, alice), c.playersByUserId());
        assertTrue(c.contains(bob));
    }
}
