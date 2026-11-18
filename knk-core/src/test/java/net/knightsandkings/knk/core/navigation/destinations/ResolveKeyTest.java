package net.knightsandkings.knk.core.navigation.destinations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.destinations.Candidate.Mode;
import net.knightsandkings.knk.core.navigation.destinations.Candidate.Place;
import net.knightsandkings.knk.core.navigation.destinations.DestinationEngine.Resolution.Outcome;

/**
 * {@link DestinationEngine#resolveKey} (phase 3, PandiO/knk-workspace#69): the compass cycle, a resume of the stored
 * key and the command's match prefix get a handle for a listed key, through the list built now - never for a key the
 * list does not hold.
 */
class ResolveKeyTest {

    private final Provider world = Provider.fixed("catalogue", List.of(
        Fixtures.town(1, "Kardenna", Fixtures.WORLD),
        Fixtures.town(2, "Farland", "other_world"),
        new Candidate("domain:District:3", "Old Quarter", "district", List.of(), 3, null, 0, "catalogue",
            new Place.Domain(null, "old_quarter", Mode.REGION))));
    private final Provider match = Provider.fixed("siege-match", List.of(Fixtures.objective(7, "Keep Gate", "defend", 0)));
    private final DestinationEngine engine = new DestinationEngine(InitialScopes.create(List.of(world), match));

    @Test
    void aListedKeyGetsAHandleWithTheModeAndTheListsScope() {
        DestinationEngine.Resolution r = engine.resolveKey(Fixtures.player(), "domain:Town:1", Mode.SPAWN);
        assertEquals(Outcome.FOUND, r.outcome());
        assertEquals("domain:Town:1", r.destination().candidateKey());
        assertEquals(Mode.SPAWN, r.destination().mode());
        assertEquals(InitialScopes.WORLD, r.destination().scopeId());
        assertEquals(Fixtures.PLAYER, r.destination().player());
    }

    @Test
    void aKeyThatIsNotListedGetsNoHandle() {
        DestinationEngine.Resolution r = engine.resolveKey(Fixtures.player(), "domain:Town:99", Mode.DEFAULT);
        assertEquals(Outcome.NOT_FOUND, r.outcome());
        assertNull(r.destination());
    }

    @Test
    void aKeyOfAnotherWorldIsAnsweredApart() {
        DestinationEngine.Resolution r = engine.resolveKey(Fixtures.player(), "domain:Town:2", Mode.DEFAULT);
        assertEquals(Outcome.OTHER_WORLD, r.outcome());
        assertEquals("Farland", r.candidate().label());
    }

    @Test
    void aWorldKeyIsNotFoundInAMatchAndAMatchKeyIsFound() {
        NavigationContext inMatch = Fixtures.player().withSiege(NavigationContext.Siege.match(1, 1));
        assertEquals(Outcome.NOT_FOUND, engine.resolveKey(inMatch, "domain:Town:1", Mode.DEFAULT).outcome());
        DestinationEngine.Resolution keep = engine.resolveKey(inMatch, "objective:7", Mode.DEFAULT);
        assertTrue(keep.found());
        assertEquals(InitialScopes.SIEGE_MATCH, keep.destination().scopeId());
    }

    @Test
    void aRefusedListGivesTheScopesReason() {
        DestinationEngine.Resolution r = engine.resolveKey(Fixtures.player().withFrozen(true), "domain:Town:1", Mode.DEFAULT);
        assertEquals(Outcome.NOT_ALLOWED, r.outcome());
        assertEquals(InitialScopes.FROZEN_CODE, r.reason().code());
    }

    @Test
    void aModeTheDomainCannotServeIsNoLocation() {
        DestinationEngine.Resolution r = engine.resolveKey(Fixtures.player(), "domain:District:3", Mode.SPAWN);
        assertEquals(Outcome.NO_LOCATION, r.outcome());
        assertEquals("Old Quarter", r.candidate().label());
        assertTrue(engine.resolveKey(Fixtures.player(), "domain:District:3", Mode.REGION).found());
    }
}
