package net.knightsandkings.knk.core.navigation.destinations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.navigation.destinations.Candidate.Mode;
import net.knightsandkings.knk.core.navigation.destinations.Candidate.Place;
import net.knightsandkings.knk.core.navigation.destinations.DestinationEngine.Resolution;
import net.knightsandkings.knk.core.navigation.destinations.DestinationEngine.Resolution.Outcome;
import net.knightsandkings.knk.core.navigation.destinations.NavigationContext.Siege;

/**
 * What the landmark provider of off-road phase 5.1 (#708) needs from the engine (DESIGN § 3.2, E16; off-road O27,
 * § 6): a provider built the way § 3.2 describes, added to the World scope's provider list with no change to the
 * engine or the scope table, is listed in the world, hidden by every exclusive scope, listed for the bypass, and a
 * landmark in another world is hidden from completion but resolves to the other-world answer. The landmark type here
 * is a stand-in for the off-road entity (§ 6.3), not that entity.
 */
class LandmarkLikeProviderTest {

    /** The fields of off-road § 6.3 the provider reads. */
    enum ExtentKind { NONE, REGION, RADIUS }

    record Landmark(int id, String name, String world, double x, double y, double z, ExtentKind extent, String regionId,
                    Double radius, String domainName, boolean navigationEnabled) {
    }

    /** The provider as § 3.2 describes it: key, label, type word, id, detail, source and place. */
    static final class LandmarkProvider implements Provider {
        private final List<Landmark> landmarks;

        LandmarkProvider(List<Landmark> landmarks) {
            this.landmarks = landmarks;
        }

        @Override
        public String id() {
            return "landmarks";
        }

        @Override
        public List<Candidate> candidates(NavigationContext ctx) {
            List<Candidate> out = new ArrayList<>();
            for (Landmark l : landmarks) {
                if (!l.navigationEnabled()) {
                    continue;
                }
                Place place = switch (l.extent()) {
                    case REGION -> new Place.Region(l.world(), l.regionId());
                    case RADIUS -> new Place.Point(l.world(), l.x(), l.y(), l.z(), l.radius());
                    case NONE -> new Place.Point(l.world(), l.x(), l.y(), l.z());
                };
                out.add(new Candidate("landmark:" + l.id(), l.name(), "landmark", List.of(), l.id(),
                    l.domainName() == null ? null : "in " + l.domainName(), 0, id(), place));
            }
            return out;
        }
    }

    private final Landmark throneRoom = new Landmark(41, "Throne Room", "world", 12, 70, 30, ExtentKind.RADIUS, null, 4.0,
        "Kardenna", true);
    private final Landmark well = new Landmark(42, "Old Well", "world", 5, 64, 5, ExtentKind.NONE, null, null, null, true);
    private final Landmark hall = new Landmark(43, "Great Hall", "world", 0, 64, 0, ExtentKind.REGION, "hall_43", null,
        "Kardenna", true);
    private final Landmark hidden = new Landmark(44, "Secret Door", "world", 1, 64, 1, ExtentKind.NONE, null, null, null,
        false);
    private final Landmark netherGate = new Landmark(45, "Nether Gate", "nether", 0, 64, 0, ExtentKind.NONE, null, null,
        null, true);
    /** Shares its name with a town of the catalogue. */
    private final Landmark marketCross = new Landmark(46, "Kardenna", "world", 7, 64, 7, ExtentKind.NONE, null, null, null,
        true);

    private final List<Landmark> landmarks = new ArrayList<>(List.of(throneRoom, well, hall, hidden, netherGate));
    private final Provider catalogue = Provider.fixed("catalogue", List.of(Fixtures.town(1, "Kardenna", "world"),
        Fixtures.street(6, "Keepstreet", "world")));
    private final Provider siegeMatch = Provider.of("siege-match",
        ctx -> ctx.siege().matchRunning() ? List.of(Fixtures.objective(7, "Keep Gate", "capture", 0)) : List.of());
    /** The plugin's wiring with {@code navigation.landmarks} on: one more World provider, nothing else. */
    private final DestinationEngine engine = new DestinationEngine(
        InitialScopes.create(List.of(catalogue, new LandmarkProvider(landmarks)), siegeMatch));
    private final NavigationContext player = Fixtures.player();
    private final NavigationContext inMatch = player.withSiege(Siege.match(1, 1));

    private static List<String> landmarkKeys(DestinationList list) {
        return list.candidates().stream().map(Candidate::key).filter(k -> k.startsWith("landmark:")).toList();
    }

    @Test
    void theWorldScopeListsLandmarksAsCandidates() {
        DestinationList list = engine.listFor(player);
        assertEquals(InitialScopes.WORLD, list.scopeId());
        assertEquals(List.of("landmark:43", "landmark:42", "landmark:41"), landmarkKeys(list),
            "ordered by label with the catalogue; a landmark with NavigationEnabled = false is not offered");
        Candidate throne = list.find("landmark:41").orElseThrow();
        assertEquals("Throne Room", throne.label());
        assertEquals("landmark", throne.typeWord());
        assertEquals(41, throne.id());
        assertEquals("in Kardenna", throne.detail());
        assertEquals("landmarks", throne.source());
        assertNull(list.find("landmark:42").orElseThrow().detail(), "no domain: no detail");
        assertEquals(List.of("landmark:45"), list.otherWorld().stream().map(Candidate::key).toList());
    }

    @Test
    void thePlaceFollowsTheExtent() {
        DestinationList list = engine.listFor(player);
        Place.Point radius = assertInstanceOf(Place.Point.class, list.find("landmark:41").orElseThrow().place());
        assertEquals(4.0, radius.arrivalRadius(), "a Radius extent: arrived within its radius");
        Place.Point none = assertInstanceOf(Place.Point.class, list.find("landmark:42").orElseThrow().place());
        assertFalse(none.hasArrivalRadius(), "None: within arrive-distance");
        Place.Region region = assertInstanceOf(Place.Region.class, list.find("landmark:43").orElseThrow().place());
        assertEquals("hall_43", region.regionId());
        assertEquals("world", region.world());
    }

    @Test
    void landmarksResolveByNameTypeWordAndId() {
        Resolution byName = engine.resolve(player, "Throne Room", Mode.DEFAULT);
        assertEquals(Outcome.FOUND, byName.outcome());
        assertEquals("landmark:41", byName.destination().candidateKey());
        assertEquals(InitialScopes.WORLD, byName.destination().scopeId());
        assertEquals("landmark:42", engine.resolve(player, "landmark:Old Well", Mode.DEFAULT).destination().candidateKey());
        assertEquals("landmark:43", engine.resolve(player, "landmark:#43", Mode.DEFAULT).destination().candidateKey());
        assertTrue(engine.complete(player, List.of("landmark:")).contains("landmark:Throne"));
        assertEquals(Outcome.NOT_FOUND, engine.resolve(player, "Secret Door", Mode.DEFAULT).outcome());

        landmarks.add(marketCross);
        Resolution same = engine.resolve(player, "Kardenna", Mode.DEFAULT);
        assertEquals(Outcome.AMBIGUOUS, same.outcome(), "a landmark and a town of the same name");
        assertEquals(List.of("landmark:Kardenna", "town:Kardenna"), same.choiceNames().stream().sorted().toList());
        assertEquals("landmark:46", engine.resolve(player, "landmark:Kardenna", Mode.DEFAULT).destination().candidateKey());
    }

    @Test
    void exclusiveScopesHideLandmarks() {
        assertEquals(List.of(), landmarkKeys(engine.listFor(inMatch)), "a running match lists only the match");
        assertEquals(Outcome.NOT_FOUND, engine.resolve(inMatch, "Throne Room", Mode.DEFAULT).outcome());
        assertFalse(engine.complete(inMatch, List.of("")).contains("Throne"));
        assertEquals(InitialScopes.COMPASS_OFF_REASON,
            engine.listFor(inMatch.withSiege(inMatch.siege().withCompassEnabled(false))).reason());
        assertEquals(InitialScopes.IN_SIEGE_REASON, engine.listFor(player.withSiege(Siege.lobby())).reason());
        assertEquals(InitialScopes.LOADING_REASON, engine.listFor(player.withLoading(true)).reason());
        assertEquals(InitialScopes.FROZEN_REASON, engine.listFor(player.withFrozen(true)).reason());
        assertEquals(InitialScopes.NO_PERMISSION_REASON,
            engine.listFor(NavigationContext.of(Fixtures.PLAYER, Fixtures.WORLD)).reason());
    }

    @Test
    void theBypassListsLandmarksEvenInAMatch() {
        for (NavigationContext bypass : List.of(inMatch.withMode(ActiveMode.OWNER), inMatch.withMode(ActiveMode.STAFF),
            inMatch.withPermissions(InitialScopes.BYPASS_NODE))) {
            DestinationList list = engine.listFor(bypass);
            assertEquals(InitialScopes.BYPASS, list.scopeId());
            assertEquals(List.of("landmark:43", "landmark:42", "landmark:41"), landmarkKeys(list));
            assertTrue(list.contains("objective:7"), "and the match provider");
        }
    }

    @Test
    void aLandmarkInAnotherWorldIsNotCompletedButResolvesApart() {
        assertFalse(engine.complete(player, List.of("")).contains("Nether"));
        Resolution away = engine.resolve(player, "Nether Gate", Mode.DEFAULT);
        assertEquals(Outcome.OTHER_WORLD, away.outcome());
        assertEquals("landmark:45", away.candidate().key());
        assertNull(away.destination());
        assertEquals("landmark:45", engine.resolve(player.withWorld("nether"), "Nether Gate", Mode.DEFAULT)
            .destination().candidateKey(), "navigable in its own world");
    }

    @Test
    void aRenamedLandmarkKeepsItsSessionAndADisabledOneEndsIt() {
        AllowedDestination handle = engine.resolve(player, "Throne Room", Mode.DEFAULT).destination();
        landmarks.set(landmarks.indexOf(throneRoom), new Landmark(41, "King's Hall", "world", 12, 70, 30, ExtentKind.RADIUS,
            null, 4.0, "Kardenna", true));
        DestinationEngine.Revalidation renamed = engine.revalidate(player, handle);
        assertTrue(renamed.allowed(), "the key is the id, stable across renames");
        assertEquals("King's Hall", renamed.candidate().label());

        landmarks.set(0, new Landmark(41, "King's Hall", "world", 12, 70, 30, ExtentKind.RADIUS, null, 4.0, "Kardenna",
            false));
        DestinationEngine.Revalidation disabled = engine.revalidate(player, handle);
        assertFalse(disabled.allowed());
        assertEquals(Reason.NO_LONGER_LISTED, disabled.reason().code());
    }
}
