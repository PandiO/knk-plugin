package net.knightsandkings.knk.core.navigation.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.destinations.Candidate;
import net.knightsandkings.knk.core.navigation.destinations.Candidate.Mode;
import net.knightsandkings.knk.core.navigation.destinations.DestinationEngine;
import net.knightsandkings.knk.core.navigation.destinations.DestinationList;
import net.knightsandkings.knk.core.navigation.destinations.InitialScopes;
import net.knightsandkings.knk.core.navigation.destinations.Provider;

/**
 * The compass's general targets as a filter and ordering of the World list (phase 3, PandiO/knk-workspace#69, A19):
 * the configured lines in their order, each resolved like {@code /navigate <line>}.
 */
class CompassTargetsTest {

    private final CatalogueProvider catalogue = new CatalogueProvider(CatalogueProviderTest::model,
        w -> CatalogueProviderTest.network());
    private final DestinationEngine engine = new DestinationEngine(InitialScopes.create(List.of(catalogue),
        Provider.fixed("siege-match", List.of())));

    private DestinationList list() {
        return engine.listFor(CatalogueProviderTest.player());
    }

    @Test
    void theLinesAreKeptInTheirOrderWithTheirModeWords() {
        List<CompassTargets.Pick> picks = CompassTargets.of(list(),
            List.of("Main Street", "Kardenna spawn", "district:Market region", "location:Fountain"));
        assertEquals(List.of("street:5", "domain:Town:1", "domain:District:2", "location:41"),
            picks.stream().map(p -> p.candidate().key()).toList());
        assertEquals(List.of(Mode.DEFAULT, Mode.SPAWN, Mode.REGION, Mode.DEFAULT),
            picks.stream().map(CompassTargets.Pick::mode).toList());
        assertEquals("Kardenna spawn", picks.get(1).line());
    }

    @Test
    void unknownAmbiguousAndOtherWorldLinesAreLeftOut() {
        List<CompassTargets.Pick> picks = CompassTargets.of(list(), List.of("Nowhere", "Market", "Farland", " ", "Kardenna"));
        assertEquals(List.of("Kardenna"), picks.stream().map(p -> p.candidate().label()).toList());
    }

    @Test
    void aCandidateNamedTwiceIsOfferedOnce() {
        List<CompassTargets.Pick> picks = CompassTargets.of(list(), List.of("Kardenna", "town:#1 spawn", "Main Street"));
        assertEquals(List.of("domain:Town:1", "street:5"), picks.stream().map(p -> p.candidate().key()).toList());
    }

    @Test
    void aRefusedListHasNoTargets() {
        DestinationList frozen = engine.listFor(CatalogueProviderTest.player().withFrozen(true));
        assertTrue(CompassTargets.of(frozen, List.of("Kardenna")).isEmpty());
        assertTrue(CompassTargets.of(null, List.of("Kardenna")).isEmpty());
    }

    @Test
    void parsingIsTheCommandsTrailingModeWord() {
        assertEquals(new CompassTargets.Parsed("Kardenna", Mode.SPAWN), CompassTargets.parse("Kardenna spawn"));
        assertEquals(new CompassTargets.Parsed("spawn", Mode.DEFAULT), CompassTargets.parse("spawn"));
        assertEquals(new CompassTargets.Parsed("Old  Quarter", Mode.DEFAULT).mode(), CompassTargets.parse("Old Quarter").mode());
        Candidate kardenna = CompassTargets.of(list(), List.of("  Kardenna   region ")).get(0).candidate();
        assertEquals("Kardenna", kardenna.label());
    }
}
