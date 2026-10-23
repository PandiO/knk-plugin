package net.knightsandkings.knk.core.navigation.destinations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.destinations.Candidate.Mode;
import net.knightsandkings.knk.core.navigation.destinations.Candidate.Place;
import net.knightsandkings.knk.core.util.NamedTargets;

/** A candidate's place and the name lookup generalised over candidates (DESIGN §3, §3.1). */
class CandidateTest {

    private final Candidate kardenna = Fixtures.town(1, "Kardenna", "world");
    private final Candidate marketTown = Fixtures.town(2, "Market", "world");
    private final Candidate marketDistrict = Fixtures.district(3, "Market", "world");
    private final Candidate keepGate = Fixtures.gate(4, "Keep Gate", "world");
    private final Candidate mill = Fixtures.location(5, "Kardenna Mill", "world");
    private final Candidate objective = Fixtures.objective(7, "Keep Gate", "capture", 0);
    private final List<Candidate> all = List.of(kardenna, marketTown, marketDistrict, keepGate, mill);

    @Test
    void aCandidateNeedsAKeyALabelAndAPlace() {
        assertThrows(IllegalArgumentException.class, () -> kardenna.withLabel(" "));
        assertThrows(NullPointerException.class, () -> kardenna.withPlace(null));
        assertThrows(IllegalArgumentException.class,
            () -> new Candidate("", "x", "town", null, 1, null, 0, "catalogue", kardenna.place()));
        Candidate plain = Candidate.of("k", "Name", "Town", "src", new Place.Street("world", 1));
        assertEquals(List.of(), plain.aliases());
        assertEquals(Candidate.NO_ID, plain.id());
        assertNull(plain.detail());
        assertEquals("town:Name", plain.qualifiedName(), "the qualified form uses the lower-case type word");
    }

    @Test
    void aDomainOffersWhatItHas() {
        Place.Point spawn = new Place.Point("world", 1, 2, 3);
        Place.Domain both = new Place.Domain(spawn, "town_1", Mode.REGION);
        Place.Domain spawnOnly = new Place.Domain(spawn, " ", null);
        Place.Domain regionOnly = new Place.Domain(null, "town_1", Mode.SPAWN);
        Place.Domain nothing = new Place.Domain(null, null, Mode.SPAWN);

        assertTrue(both.offers(Mode.DEFAULT) && both.offers(Mode.SPAWN) && both.offers(Mode.REGION));
        assertEquals(Mode.REGION, both.defaultMode());
        assertNull(spawnOnly.regionId(), "a blank region id is none");
        assertEquals(Mode.SPAWN, spawnOnly.defaultMode(), "no default given: spawn, as today");
        assertTrue(spawnOnly.offers(Mode.DEFAULT) && spawnOnly.offers(Mode.SPAWN));
        assertFalse(spawnOnly.offers(Mode.REGION));
        assertTrue(regionOnly.offers(Mode.DEFAULT) && regionOnly.offers(Mode.REGION));
        assertFalse(regionOnly.offers(Mode.SPAWN));
        assertFalse(nothing.offers(Mode.DEFAULT));
        assertTrue(nothing.offers(null) == nothing.offers(Mode.DEFAULT));
    }

    @Test
    void thePlaceWorldDecidesWhereACandidateIsListed() {
        assertEquals("world", kardenna.place().world());
        assertTrue(kardenna.inWorld("WORLD"), "ignoring case");
        assertFalse(kardenna.inWorld("nether"));
        Candidate regionOnly = Fixtures.town(8, "Old Fort", null);
        assertNull(regionOnly.place().world(), "a region carries no world");
        assertTrue(regionOnly.inWorld("nether"), "tried in the player's world, as today");
        assertNull(new Place.Region(null, "r").world());
        assertThrows(NullPointerException.class, () -> new Place.Point(null, 0, 0, 0));
    }

    @Test
    void aNodeKeepsItsOwnCopyOfTheFloor() {
        double[] floor = {1, 2, 3};
        Place.Node node = new Place.Node("world", 1, floor);
        floor[0] = 9;
        assertEquals(1, node.floor()[0]);
        assertNull(new Place.Node("world", 1, null).floor());
    }

    @Test
    void modeWordsParse() {
        assertEquals(Optional.of(Mode.SPAWN), Mode.parse("Spawn"));
        assertEquals(Optional.of(Mode.REGION), Mode.parse("region"));
        assertEquals(Optional.empty(), Mode.parse("there"));
        assertEquals(Optional.empty(), Mode.parse(null));
    }

    @Test
    void namesResolveByTypeWordIdAndAlias() {
        NamedTargets<Candidate> names = DestinationEngine.NAMES;
        assertSame(kardenna, names.resolve(all, "kardenna").target());
        assertTrue(names.resolve(all, "market").ambiguous());
        assertEquals(List.of(marketTown, marketDistrict), names.resolve(all, "market").choices());
        assertSame(marketDistrict, names.resolve(all, "district:Market").target());
        assertSame(marketTown, names.resolve(all, "town:#2").target());
        assertSame(mill, names.resolve(all, "#5").target());
        assertSame(keepGate, names.resolve(all, "gate:Keep Gate").target());
        assertSame(keepGate, names.resolve(all, "GateStructure:keep gate").target());
        assertFalse(names.resolve(all, "gate:Kardenna").found(), "the alias belongs to the gate only");
        assertEquals("structure:Keep Gate", keepGate.qualifiedName());
        assertEquals("objective:Keep Gate", objective.qualifiedName(), "the same name in a match has its own type word");
    }

    @Test
    void suggestionsAndCompletionWorkOverCandidates() {
        NamedTargets<Candidate> names = DestinationEngine.NAMES;
        assertEquals(List.of(kardenna, mill), names.suggestions(all, "kar"));
        assertEquals(List.of(marketDistrict), names.suggestions(all, "district:m"));
        assertEquals(List.of("Kardenna", "town:Market", "district:Market", "Keep"), names.complete(all, ""));
        assertEquals(List.of("Gate"), names.complete(all, List.of("Keep", "")));
        assertEquals(List.of("gate:Keep"), names.complete(all, "gate:"));
        assertEquals(List.of("town:Kardenna", "town:Market"), names.complete(all, "town:"));
    }
}
