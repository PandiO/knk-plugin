package net.knightsandkings.knk.core.targeting;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetSelectorTest {

    private static final TargetPolicy POLICY = TargetPolicy.defaults().withCommitment(5_000, 0.25)
        .withAggroRadius(12).withLeashDistance(24);

    private static TargetCandidate player(String id, double x) {
        return TargetCandidate.eligible("player:" + id, TargetKind.PLAYER, x, 64, 0);
    }

    private static TargetCandidate gate(double x) {
        return TargetCandidate.eligible("gate:7", TargetKind.GATE, x, 64, 0);
    }

    private static Optional<TargetSelector.Scored> select(TargetSelector s, List<TargetCandidate> c,
                                                           Optional<TargetCommitment> current, double actorX,
                                                           boolean direct, long now) {
        return s.select(c, current, actorX, 64, 0, 0, 64, 0, direct, now);
    }

    @Test
    void closerCandidatesScoreHigherAndPreferenceIsNotMultipliedByDistance() {
        assertTrue(POLICY.score(TargetKind.GATE, 5) > POLICY.score(TargetKind.GATE, 50));
        assertEquals(POLICY.preference(TargetKind.GATE) / 2, POLICY.score(TargetKind.GATE, POLICY.proximityScale()), 1e-9);
        assertEquals(0, POLICY.score(TargetKind.DISTRACTOR, 1));
    }

    @Test
    void ineligibleCandidatesNeverEnterScoring() {
        TargetSelector s = new TargetSelector(POLICY);
        TargetCandidate ghost = TargetCandidate.denied("player:g", TargetKind.PLAYER, 1, 64, 0, "ghost");
        Optional<TargetSelector.Scored> chosen = select(s, List.of(ghost, gate(30)), Optional.empty(), 0, false, 0);
        assertEquals("gate:7", chosen.orElseThrow().candidate().id());
        assertTrue(select(s, List.of(ghost), Optional.empty(), 0, false, 0).isEmpty());
    }

    @Test
    void playersOutsideTheAggroRadiusOrLeashAreSkippedUnlessPursuingDirectly() {
        TargetSelector s = new TargetSelector(POLICY);
        assertTrue(select(s, List.of(player("far", 20)), Optional.empty(), 0, false, 0).isEmpty());
        assertEquals("player:far", select(s, List.of(player("far", 20)), Optional.empty(), 0, true, 0).orElseThrow().candidate().id());
        // Within aggro of the actor (actor at 30) but beyond the leash from the anchor at 0.
        assertTrue(select(s, List.of(player("kiter", 35)), Optional.empty(), 30, false, 0).isEmpty());
        assertEquals("player:kiter", select(s, List.of(player("kiter", 35)), Optional.empty(), 30, true, 0).orElseThrow().candidate().id());
    }

    @Test
    void commitmentHoldsAgainstOrdinaryScoreChangesThenTheMarginApplies() {
        TargetSelector s = new TargetSelector(POLICY);
        TargetCandidate gate = gate(10);
        TargetCommitment held = new TargetCommitment(gate.id(), TargetKind.GATE, POLICY.score(TargetKind.GATE, 10), 0);
        TargetCandidate slightlyBetter = player("p", 8); // 1/(1+0.5)=0.667 vs gate 0.9/(1+0.625)=0.554: +20 %, under the 25 % margin
        assertEquals(gate.id(), select(s, List.of(gate, slightlyBetter), Optional.of(held), 0, false, 1_000).orElseThrow().candidate().id());
        assertEquals(gate.id(), select(s, List.of(gate, slightlyBetter), Optional.of(held), 0, false, 10_000).orElseThrow().candidate().id());
        TargetCandidate muchBetter = player("q", 1); // 1/(1+1/16)=0.94: well over the margin
        assertEquals(gate.id(), select(s, List.of(gate, muchBetter), Optional.of(held), 0, false, 1_000).orElseThrow().candidate().id(),
            "no switch before the commitment expires");
        assertEquals("player:q", select(s, List.of(gate, muchBetter), Optional.of(held), 0, false, 5_000).orElseThrow().candidate().id(),
            "after the commitment the challenger beats the margin");
    }

    @Test
    void aHeldTargetThatBecameIneligibleIsReleasedAtOnce() {
        TargetSelector s = new TargetSelector(POLICY);
        TargetCommitment held = new TargetCommitment("player:a", TargetKind.PLAYER, 1, 0);
        TargetCandidate ghost = TargetCandidate.denied("player:a", TargetKind.PLAYER, 1, 64, 0, "ghost");
        Optional<TargetSelector.Scored> chosen = select(s, List.of(ghost, gate(40)), Optional.of(held), 0, false, 100);
        assertEquals("gate:7", chosen.orElseThrow().candidate().id());
    }

    @Test
    void expiryAloneNeverForcesASwitch() {
        TargetSelector s = new TargetSelector(POLICY);
        TargetCandidate gate = gate(10);
        TargetCommitment held = new TargetCommitment(gate.id(), TargetKind.GATE, 0.1, 0);
        TargetCandidate equal = TargetCandidate.eligible("objective:1", TargetKind.OBJECTIVE, 11, 64, 0);
        assertEquals(gate.id(), select(s, List.of(gate, equal), Optional.of(held), 0, false, 60_000).orElseThrow().candidate().id());
    }

    @Test
    void policyValidation() {
        assertThrows(IllegalArgumentException.class, () -> new TargetPolicy(Map.of(TargetKind.PLAYER, -1.0), 16, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> TargetPolicy.defaults().withCommitment(-1, 0));
        assertFalse(new TargetCommitment("x", TargetKind.GATE, 1, 0).expired(1_000, POLICY));
        assertTrue(new TargetCommitment("x", TargetKind.GATE, 1, 0).expired(5_000, POLICY));
    }
}
