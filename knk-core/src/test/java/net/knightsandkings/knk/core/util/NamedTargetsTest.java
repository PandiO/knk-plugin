package net.knightsandkings.knk.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The generalised /warp lookup (road navigation plan R21): names, type:name, type:#id, completion. */
class NamedTargetsTest {

    record Place(int id, String name, String type) {
    }

    private final NamedTargets<Place> targets = new NamedTargets<>(Place::name, Place::type, Place::id);
    private final NamedTargets<Place> withoutIds = new NamedTargets<>(Place::name, Place::type);

    private final Place kardenna = new Place(1, "Kardenna", "Town");
    private final Place marketTown = new Place(2, "Market", "Town");
    private final Place marketDistrict = new Place(3, "Market", "District");
    private final Place newHaven = new Place(4, "New Haven", "Town");
    private final Place mill = new Place(5, "Kardenna Mill", "location");
    private final List<Place> all = List.of(kardenna, marketTown, marketDistrict, newHaven, mill);

    @Test
    void namesMatchIgnoringCaseAndSpacing() {
        assertSame(kardenna, targets.resolve(all, "kARDENNA").target());
        assertSame(newHaven, targets.resolve(all, "new  haven ").target());
        assertSame(mill, targets.resolve(all, "Kardenna Mill").target());
    }

    @Test
    void aSharedNameIsAmbiguousUntilTheTypeIsGiven() {
        NamedTargets.Match<Place> match = targets.resolve(all, "market");

        assertFalse(match.found());
        assertTrue(match.ambiguous());
        assertEquals(List.of(marketTown, marketDistrict), match.choices());
        assertSame(marketDistrict, targets.resolve(all, "district:Market").target());
        assertSame(marketTown, targets.resolve(all, "TOWN:market").target());
        assertEquals("town:Market", targets.qualifiedName(marketTown));
    }

    @Test
    void idFormsPickByIdWhenAnAccessorExists() {
        assertSame(marketDistrict, targets.resolve(all, "district:#3").target());
        assertSame(mill, targets.resolve(all, "#5").target());
        assertFalse(targets.resolve(all, "town:#3").found(), "the type must fit too");
        assertFalse(targets.resolve(all, "#99").found());
        assertFalse(withoutIds.resolve(all, "#5").found(), "no id accessor: '#5' is just a name nobody has");
    }

    @Test
    void anUnknownTypePrefixCanStillBeALiteralName() {
        List<Place> places = List.of(new Place(9, "Spawn:Hill", "Town"));
        assertSame(places.get(0), targets.resolve(places, "spawn:hill").target());
        assertFalse(targets.resolve(all, "").found());
        assertFalse(targets.resolve(List.of(), "Kardenna").found());
    }

    @Test
    void suggestionsAreNamePrefixes() {
        assertEquals(List.of(newHaven), targets.suggestions(all, "new"));
        assertEquals(List.of(marketTown, marketDistrict), targets.suggestions(all, "mar"));
        assertEquals(List.of(marketDistrict), targets.suggestions(all, "district:m"));
        assertEquals(List.of(), targets.suggestions(all, ""));
    }

    @Test
    void completionOffersNamesAndQualifiedFormsForSharedNames() {
        assertEquals(List.of("Kardenna", "town:Market", "district:Market", "New"), targets.complete(all, ""),
            "the first word of 'Kardenna Mill' is offered once");
        assertEquals(List.of("Kardenna"), targets.complete(all, "ka"));
        assertEquals(List.of("district:Market"), targets.complete(all, "dis"));
        assertEquals(List.of("town:Market", "district:Market"), targets.complete(all, "MAR"));
    }

    @Test
    void completionGoesOneWordAtATime() {
        List<Place> places = List.of(new Place(1, "Residential District", "District"), kardenna);
        assertEquals(List.of("Residential"), targets.complete(places, List.of("res")));
        assertEquals(List.of("District"), targets.complete(places, List.of("Residential", "")));
        assertEquals(List.of("District"), targets.complete(places, List.of("residential", "d")));
        assertEquals(List.of(), targets.complete(places, List.of("Kardenna", "")), "a one-word name has no second word");
    }
}
