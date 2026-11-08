package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.core.survival.SurvivalSubstate;
import net.knightsandkings.knk.core.survival.end.SurvivalEndReason;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/** DESIGN §7 / S53 (link S8b): special rounds are announced by name, exposed by key; kill totals span the rounds. */
class SurvivalSpecialRoundsTest {

    @Test
    void aSpecialRoundIsAnnouncedWithTheProfileNameAndReportedByKey() throws Exception {
        SurvivalMatchFixture f = new SurvivalMatchFixture(1, SurvivalMatchFixture.SPECIAL_AT_WAVE_1);
        List<String> rounds = new ArrayList<>();
        f.match.addHook(new MatchHook() {
            @Override
            public void roundStarted(int round, Optional<String> specialProfileId) {
                rounds.add("start " + round + " " + specialProfileId.orElse("-"));
            }

            @Override
            public void roundCompleted(int round) {
                rounds.add("done " + round);
            }
        });
        assertEquals("wolves", f.config.specialProfileKey("3"));
        assertEquals("Wolves", f.config.specialProfileName("3"));
        assertEquals("9", f.config.specialProfileKey("9"), "unknown profiles fall back to the id");

        f.startIntoWave1();
        assertEquals(Optional.of("3"), f.match.specialProfileId());
        assertEquals(Optional.of("wolves"), f.match.specialProfileKey());
        ArgumentCaptor<Title> title = ArgumentCaptor.forClass(Title.class);
        verify(f.players.get(0), atLeastOnce()).showTitle(title.capture());
        String subtitle = PlainTextComponentSerializer.plainText().serialize(title.getValue().subtitle());
        assertTrue(subtitle.contains("Special round: Wolves"), subtitle);
        assertTrue(subtitle.contains("2 enemies"), subtitle);
        assertTrue(f.match.statusLines().stream().anyMatch(l -> l.contains("special wolves")), f.match.statusLines().toString());
        assertEquals(List.of("start 1 3"), rounds);

        f.spawnAll();
        f.match.onMobDeath(f.spawned.get(0), f.players.get(0));
        f.match.onMobDeath(f.spawned.get(1), null);
        assertEquals(SurvivalSubstate.INTERMISSION, f.match.rounds().substate());
        assertEquals(Optional.empty(), f.match.specialProfileKey(), "no special key outside an active wave");
        assertEquals(List.of("start 1 3", "done 1"), rounds);

        f.advance(2_100); // round 2 is a normal round
        assertEquals(Optional.empty(), f.match.specialProfileId());
        assertEquals(List.of("start 1 3", "done 1", "start 2 -"), rounds);
    }

    @Test
    void killTotalsAccumulateOverTheRoundsAndSurviveTheEnd() throws Exception {
        SurvivalMatchFixture f = new SurvivalMatchFixture(1);
        f.startIntoWave1();
        f.spawnAll();
        f.match.onMobDeath(f.spawned.get(0), f.players.get(0));
        f.match.onMobDeath(f.spawned.get(1), f.players.get(0));
        assertEquals(2, f.match.earnedKills());
        assertEquals(SurvivalSubstate.INTERMISSION, f.match.rounds().substate());
        f.advance(2_100); // round 2: 3 enemies
        f.spawnAll();
        assertEquals(2, f.match.earnedKills(), "the completed round stays counted once");
        f.match.onMobDeath(f.spawned.get(2), f.players.get(0));
        assertEquals(3, f.match.earnedKills());
        assertEquals(0, f.match.systemKills());
        f.match.end(SurvivalEndReason.ADMIN_ABORT);
        assertEquals(3, f.match.earnedKills(), "the current round is counted at the end");
        assertEquals(List.of(SurvivalEndReason.ADMIN_ABORT), f.ended);
    }
}
