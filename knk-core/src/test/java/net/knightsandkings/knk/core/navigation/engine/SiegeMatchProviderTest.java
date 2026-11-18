package net.knightsandkings.knk.core.navigation.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchLength;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeSpawnpoint;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.domain.siege.SiegeGateState;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import net.knightsandkings.knk.core.navigation.destinations.Candidate;
import net.knightsandkings.knk.core.navigation.destinations.Candidate.Mode;
import net.knightsandkings.knk.core.navigation.destinations.Candidate.Place;
import net.knightsandkings.knk.core.navigation.destinations.DestinationEngine;
import net.knightsandkings.knk.core.navigation.destinations.InitialScopes;
import net.knightsandkings.knk.core.navigation.destinations.NavigationContext;
import net.knightsandkings.knk.core.navigation.destinations.Provider;
import net.knightsandkings.knk.core.siege.AllianceResolver;
import net.knightsandkings.knk.core.siege.CaptureCalculator;
import net.knightsandkings.knk.core.siege.SiegeCompassTargets;
import net.knightsandkings.knk.core.siege.SiegeObjectiveBoard;

/**
 * The siege match provider (phase 3, PandiO/knk-workspace#69, A05, A18): the compass's objectives and team spawn in
 * its order, the gate approach of #176, and {@code type:name} in a match through the engine.
 */
class SiegeMatchProviderTest {

    static final String WORLD = "siege_world";
    static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000004");
    static final int DEF = 1;
    static final int ATT = 2;

    static KnkLocation at(int n) {
        return new KnkLocation(n, "L" + n, 10.0 * n, 64.0, 5.0, 0f, 0f, WORLD);
    }

    static KnkSiegeObjective objective(int id, String name) {
        return new KnkSiegeObjective(id, id, name, at(id), null, 100, 3.0, id == 1, DEF, true, SiegeGateState.OPEN);
    }

    static KnkSiegeScenario scenario() {
        return new KnkSiegeScenario(1, "Kardenna", null, 5, "Kardenna", null, List.of(), null, 2, 20,
            null, null, KnkSiegeMatchLength.DEFAULT, null, true, false, true,
            List.of(new KnkSiegeTeam(DEF, 1, SiegeTeamRole.DEFENDER, 1, null, "Defenders", "WHITE", null, null,
                    List.of(new KnkSiegeSpawnpoint(10, 0, "Defenders camp", at(8), 5))),
                new KnkSiegeTeam(ATT, 2, SiegeTeamRole.ATTACKER, 2, null, "Raiders", "WHITE", null, null,
                    List.of(new KnkSiegeSpawnpoint(20, 0, "Raiders camp", at(9), 5)))),
            List.of(objective(1, "Keep"), objective(2, "Keep Gate")), List.of(), List.of());
    }

    static SiegeMatchProvider.Match match(int team) {
        KnkSiegeScenario scenario = scenario();
        AllianceResolver alliances = AllianceResolver.of(scenario);
        return new SiegeMatchProvider.Match(scenario,
            new SiegeObjectiveBoard(scenario, new CaptureCalculator(KnkSiegeConfiguration.legacyDefaults()), alliances),
            alliances, team);
    }

    static NavigationContext inMatch(int team) {
        return NavigationContext.of(PLAYER, WORLD).withPermissions(InitialScopes.NAVIGATE_NODE)
            .withSiege(NavigationContext.Siege.match(team, 1));
    }

    @Test
    void theCandidatesAreTheCompassTargetsInTheirOrder() {
        SiegeMatch m = new SiegeMatch(match(DEF));
        List<SiegeCompassTargets.Target> targets = SiegeCompassTargets.forTeam(m.match.scenario(), m.match.board(),
            m.match.alliances(), DEF);
        List<Candidate> candidates = m.provider().candidates(inMatch(DEF));
        assertEquals(targets.stream().map(SiegeCompassTargets.Target::key).toList(),
            candidates.stream().map(Candidate::key).toList());
        assertEquals(List.of("Keep Gate", "Keep", "Defenders camp"), candidates.stream().map(Candidate::label).toList());
        for (int i = 0; i < candidates.size(); i++) {
            assertEquals(i, candidates.get(i).priority());
            assertEquals(SiegeMatchProvider.ID, candidates.get(i).source());
        }
        Candidate keep = candidates.get(1);
        assertEquals("objective", keep.typeWord());
        assertEquals(1, keep.id());
        assertEquals("detail of Keep", keep.detail());
        assertEquals(new Place.Point(WORLD, 10, 64, 5), keep.place());
        assertEquals("spawn", candidates.get(2).typeWord());
        assertEquals(10, candidates.get(2).id());
    }

    @Test
    void theEngineListsThemInTheCompassOrderAndTypeNameWorksInTheMatch() {
        SiegeMatch m = new SiegeMatch(match(DEF));
        DestinationEngine engine = new DestinationEngine(InitialScopes.create(List.of(Provider.fixed("catalogue",
            List.of())), m.provider()));
        assertEquals(List.of("Keep Gate", "Keep", "Defenders camp"),
            engine.listFor(inMatch(DEF)).candidates().stream().map(Candidate::label).toList());
        assertEquals("objective:1", engine.resolve(inMatch(DEF), "objective:Keep", Mode.DEFAULT).destination().candidateKey());
        assertEquals("objective:2", engine.resolve(inMatch(DEF), "objective:#2", Mode.DEFAULT).destination().candidateKey());
        assertEquals("objective:1", engine.resolve(inMatch(DEF), "Keep", Mode.DEFAULT).destination().candidateKey());
        assertEquals(List.of("Keep"), engine.complete(inMatch(DEF), List.of("Keep")).stream().distinct().toList());
    }

    @Test
    void anObjectiveToCaptureBehindAClosedGateLeadsToTheGate() {
        KnkLocation gate = new KnkLocation(2, "gate", 15.0, 64.0, 9.0, 0f, 0f, WORLD);
        AtomicBoolean asked = new AtomicBoolean();
        SiegeMatchProvider provider = new SiegeMatchProvider(uuid -> Optional.of(match(ATT)), (objective, team) -> {
            asked.set(true);
            return objective.id() == 2 && team.id() == ATT ? Optional.of(gate) : Optional.empty();
        }, t -> null);
        Candidate keepGate = provider.candidates(inMatch(ATT)).stream().filter(c -> c.key().equals("objective:2"))
            .findFirst().orElseThrow();
        assertTrue(asked.get());
        assertEquals(new Place.Point(WORLD, 15, 64, 9), keepGate.place());
        assertEquals("objective:2", keepGate.key(), "the key stays the objective's");
    }

    @Test
    void defendersAreNotSentToAGate() {
        SiegeMatchProvider provider = new SiegeMatchProvider(uuid -> Optional.of(match(DEF)),
            (objective, team) -> Optional.of(at(99)), t -> null);
        Candidate keepGate = provider.candidates(inMatch(DEF)).stream().filter(c -> c.key().equals("objective:2"))
            .findFirst().orElseThrow();
        assertEquals(20, assertInstanceOf(Place.Point.class, keepGate.place()).x());
    }

    @Test
    void outsideAMatchOrWithoutATeamNothingIsListed() {
        SiegeMatchProvider none = new SiegeMatchProvider(uuid -> Optional.empty(), null, null);
        assertTrue(none.candidates(inMatch(DEF)).isEmpty());
        DestinationEngine engine = new DestinationEngine(InitialScopes.create(List.of(), none));
        assertEquals(InitialScopes.SIEGE_MATCH_EMPTY_CODE, engine.listFor(inMatch(DEF)).reasonCode());
    }

    @Test
    void theListIsBuiltAgainOnEveryCall() {
        SiegeMatch m = new SiegeMatch(match(DEF));
        assertNotSame(m.provider().candidates(inMatch(DEF)), m.provider().candidates(inMatch(DEF)));
    }

    /** One match and its provider. */
    private record SiegeMatch(SiegeMatchProvider.Match match) {
        SiegeMatchProvider provider() {
            return new SiegeMatchProvider(uuid -> Optional.of(match), null, t -> "detail of " + t.label());
        }
    }
}
