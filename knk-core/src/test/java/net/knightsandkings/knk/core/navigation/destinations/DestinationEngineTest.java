package net.knightsandkings.knk.core.navigation.destinations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.destinations.Candidate.Mode;
import net.knightsandkings.knk.core.navigation.destinations.Candidate.Place;
import net.knightsandkings.knk.core.navigation.destinations.DestinationEngine.Resolution;
import net.knightsandkings.knk.core.navigation.destinations.DestinationEngine.Resolution.Outcome;
import net.knightsandkings.knk.core.navigation.destinations.DestinationEngine.Revalidation;
import net.knightsandkings.knk.core.navigation.destinations.Scope.Empty;
import net.knightsandkings.knk.core.navigation.destinations.Scope.Listed;

/** The engine's mechanics over made-up scopes (DESIGN §4, §3.1, §8); the real table is {@link InitialScopesTest}. */
class DestinationEngineTest {

    private final Candidate kardenna = Fixtures.town(1, "Kardenna", "world");
    private final Candidate marketTown = Fixtures.town(2, "Market", "world");
    private final Candidate marketDistrict = Fixtures.district(3, "Market", "world");
    private final Candidate keepGate = Fixtures.gate(4, "Keep Gate", "world");
    private final Candidate mill = Fixtures.location(5, "Kardenna Mill", "world");
    private final Candidate farTown = Fixtures.town(7, "Far Town", "nether");
    private final Candidate oldFort = Fixtures.town(8, "Old Fort", null);
    private final Provider catalogue = Provider.fixed("catalogue",
        List.of(marketTown, kardenna, farTown, keepGate, oldFort, marketDistrict, mill));
    private final NavigationContext ctx = Fixtures.player();

    private static Scope always(String id, int priority, Provider... providers) {
        return Scope.listing(id, priority, "the " + id, c -> true, List.of(providers));
    }

    private static Scope never(String id, int priority, Provider... providers) {
        return Scope.listing(id, priority, "the " + id, c -> false, List.of(providers));
    }

    // ==================== scopes ====================

    @Test
    void theHighestApplicableScopeDecidesWhateverTheRegistrationOrder() {
        DestinationEngine engine = new DestinationEngine(List.of(
            always("low", 10, catalogue),
            Scope.empty("high", 100, "x", c -> true, "nope"),
            never("highest", 200, catalogue)));

        assertEquals(List.of("highest", "high", "low"), engine.scopes().stream().map(Scope::id).toList());
        assertEquals("high", engine.scopeFor(ctx).id());
        DestinationList list = engine.listFor(ctx);
        assertEquals("high", list.scopeId());
        assertTrue(list.isEmpty() && list.refused());
        assertEquals("nope", list.reason());
        assertEquals("x", list.displayName());
    }

    @Test
    void onATieTheScopeRegisteredFirstDecides() {
        DestinationEngine engine = new DestinationEngine(List.of(
            Scope.empty("first", 110, "x", c -> true, "first's reason"),
            Scope.empty("second", 110, "x", c -> true, "second's reason")));
        assertEquals("first's reason", engine.listFor(ctx).reason());

        DestinationEngine swapped = new DestinationEngine(List.of(
            Scope.empty("second", 110, "x", c -> true, "second's reason"),
            Scope.empty("first", 110, "x", c -> true, "first's reason")));
        assertEquals("second's reason", swapped.listFor(ctx).reason());
    }

    @Test
    void aTableWithoutADefaultIsAProgrammingError() {
        DestinationEngine engine = new DestinationEngine(List.of(never("a", 1)));
        assertThrows(IllegalStateException.class, () -> engine.listFor(ctx));
    }

    @Test
    void aScopeYieldsForTheContextItIsAskedAbout() {
        Scope scope = new Scope("mood", 1, "x", c -> true,
            c -> c.frozen() ? new Empty("cold") : Listed.of(List.of(catalogue)));
        DestinationEngine engine = new DestinationEngine(List.of(scope));
        assertFalse(engine.listFor(ctx).isEmpty());
        assertEquals("cold", engine.listFor(ctx.withFrozen(true)).reason());
    }

    @Test
    void filteredAndOrderedLeaveAnEmptyYieldAlone() {
        Scope empty = Scope.empty("e", 1, "x", c -> true, "r").filtered(c -> false).ordered(Comparator.comparing(Candidate::key));
        assertInstanceOf(Empty.class, empty.yield(ctx));
        assertEquals("r", ((Empty) empty.yield(ctx)).reason());
        assertEquals("Scope e (1)", empty.toString());
    }

    // ==================== listing ====================

    @Test
    void candidatesAreOrderedByPriorityThenLabelAndSplitByWorld() {
        DestinationEngine engine = new DestinationEngine(List.of(always("w", 1, catalogue)));
        DestinationList list = engine.listFor(ctx.withModelVersion(9));

        assertEquals(List.of(kardenna, mill, keepGate, marketDistrict, marketTown, oldFort), list.candidates(),
            "label order ignoring case; the same label by key; the region-only domain in every world");
        assertEquals(List.of(farTown), list.otherWorld());
        assertFalse(list.refused());
        assertNull(list.reason());
        assertEquals(9, list.modelVersion());
        assertEquals("w", list.scopeId());
        assertTrue(list.contains("location:5"));
        assertFalse(list.contains("domain:Town:7"), "another world is not navigable");
        assertEquals(List.of(), engine.listFor(ctx.withWorld("end")).otherWorld().stream()
            .filter(c -> c.key().equals("domain:Town:8")).toList(), "no world means any world");
    }

    @Test
    void aCandidatesPriorityComesBeforeItsLabel() {
        Provider match = Provider.fixed("m", List.of(
            Fixtures.teamSpawn(3, "Team spawn", 2), Fixtures.objective(8, "North Wall", "capture", 1),
            Fixtures.objective(7, "Keep Gate", "defend, under attack", 0)));
        DestinationEngine engine = new DestinationEngine(List.of(always("m", 1, match)));
        assertEquals(List.of("objective:7", "objective:8", "spawn:3"),
            engine.listFor(ctx).candidates().stream().map(Candidate::key).toList());
    }

    @Test
    void theScopesOwnOrderingComesFirst() {
        Scope byKeyDescending = always("w", 1, catalogue).ordered(Comparator.comparing(Candidate::key).reversed());
        DestinationEngine engine = new DestinationEngine(List.of(byKeyDescending));
        assertEquals(List.of("location:5", "domain:Town:8", "domain:Town:2", "domain:Town:1", "domain:Structure:4",
            "domain:District:3"), engine.listFor(ctx).candidates().stream().map(Candidate::key).toList());
    }

    @Test
    void theScopesFilterDropsCandidates() {
        DestinationEngine engine = new DestinationEngine(List.of(always("w", 1, catalogue).filtered(c -> c.id() > 3)));
        assertEquals(List.of(mill, keepGate, oldFort), engine.listFor(ctx).candidates());
    }

    @Test
    void providersAreUnitedAndTheFirstToOfferAKeyKeepsIt() {
        Candidate renamed = kardenna.withLabel("Kardenna (renamed)");
        Provider second = Provider.fixed("second", List.of(renamed, Fixtures.street(6, "Keepstreet", "world")));
        DestinationEngine engine = new DestinationEngine(List.of(always("w", 1, catalogue, second)));
        List<Candidate> listed = engine.listFor(ctx).candidates();
        assertTrue(listed.contains(kardenna) && !listed.contains(renamed), "the catalogue's Kardenna wins");
        assertTrue(listed.stream().anyMatch(c -> c.key().equals("street:6")));

        DestinationEngine reversed = new DestinationEngine(List.of(always("w", 1, second, catalogue)));
        assertTrue(reversed.listFor(ctx).candidates().contains(renamed));
    }

    @Test
    void aProviderThatThrowsIsSkippedAndTheRestIsServed() {
        Provider broken = Provider.of("broken", c -> { throw new IllegalStateException("API down"); });
        Provider nulls = Provider.of("nulls", c -> null);
        DestinationEngine engine = new DestinationEngine(List.of(always("w", 1, broken, nulls, catalogue)));
        DestinationList list = engine.listFor(ctx);
        assertEquals(6, list.candidates().size());
        assertFalse(list.degraded(), "a failed provider is logged, not announced (the model's own state is phase 2)");
    }

    @Test
    void theReasonWhenEmptyAppliesOnlyWhenNothingIsNavigable() {
        Scope hub = Scope.listing("hub", 1, "the hub", c -> true, List.of(Provider.fixed("hub", List.of(farTown))), "in a siege");
        DestinationEngine engine = new DestinationEngine(List.of(hub));
        DestinationList away = engine.listFor(ctx);
        assertTrue(away.refused(), "only a place in another world: nothing navigable");
        assertEquals("in a siege", away.reason());
        assertEquals(List.of(farTown), away.otherWorld());
        DestinationList here = engine.listFor(ctx.withWorld("nether"));
        assertFalse(here.refused());
        assertEquals(List.of(farTown), here.candidates());

        Scope plain = always("w", 1, Provider.fixed("none", List.of()));
        DestinationList empty = new DestinationEngine(List.of(plain)).listFor(ctx);
        assertTrue(empty.isEmpty());
        assertFalse(empty.refused(), "an empty catalogue has no reason");
    }

    @Test
    void aListCanBeMarkedDegraded() {
        DestinationList list = DestinationList.empty("w", "the world", null, 1).degraded("Some places aren't available.");
        assertTrue(list.degraded());
        assertEquals("Some places aren't available.", list.degradedText());
        assertFalse(list.refused());
    }

    // ==================== resolving ====================

    private DestinationEngine world() {
        return new DestinationEngine(List.of(always("w", 1, catalogue)));
    }

    @Test
    void aFoundNameMintsTheHandle() {
        Resolution found = world().resolve(ctx.withModelVersion(5), "town:#1", Mode.REGION);
        assertTrue(found.found());
        assertSame(kardenna, found.candidate());
        AllowedDestination handle = found.destination();
        assertNotNull(handle);
        assertEquals(Fixtures.PLAYER, handle.player());
        assertEquals("w", handle.scopeId());
        assertEquals("domain:Town:1", handle.candidateKey());
        assertEquals(Mode.REGION, handle.mode());
        assertEquals(5, handle.modelVersion());
        assertSame(found.list().candidates().get(0), kardenna);
        assertEquals(Mode.DEFAULT, world().resolve(ctx, "Kardenna", null).destination().mode(), "no mode word: the default");
    }

    @Test
    void sharedNamesAreAmbiguousAndUnknownNamesGetSuggestions() {
        Resolution ambiguous = world().resolve(ctx, "market", Mode.DEFAULT);
        assertTrue(ambiguous.ambiguous());
        assertNull(ambiguous.destination());
        assertEquals(List.of("district:Market", "town:Market"), ambiguous.choiceNames());

        Resolution unknown = world().resolve(ctx, "kar", Mode.DEFAULT);
        assertEquals(Outcome.NOT_FOUND, unknown.outcome());
        assertEquals(List.of("Kardenna", "Kardenna Mill"), unknown.suggestionNames());

        Resolution nothing = world().resolve(ctx, "zzz", Mode.DEFAULT);
        assertEquals(Outcome.NOT_FOUND, nothing.outcome());
        assertEquals(List.of(), nothing.suggestions());
    }

    @Test
    void aPlaceInAnotherWorldStillResolvesToItsOwnOutcome() {
        Resolution away = world().resolve(ctx, "Far Town", Mode.DEFAULT);
        assertEquals(Outcome.OTHER_WORLD, away.outcome());
        assertSame(farTown, away.candidate());
        assertNull(away.destination());
        assertEquals(Outcome.FOUND, world().resolve(ctx.withWorld("Nether"), "far town", Mode.DEFAULT).outcome());
        assertEquals(Outcome.FOUND, world().resolve(ctx.withWorld("end"), "Old Fort", Mode.DEFAULT).outcome(),
            "a region-only domain is tried in the player's world");
    }

    @Test
    void aDomainWithoutWhatWasAskedHasNoLocation() {
        Candidate spawnless = kardenna.withPlace(new Place.Domain(null, "town_1", Mode.SPAWN));
        Candidate bare = marketTown.withPlace(new Place.Domain(null, null, Mode.SPAWN)).withLabel("Bare");
        DestinationEngine engine = new DestinationEngine(List.of(always("w", 1, Provider.fixed("c", List.of(spawnless, bare, mill)))));

        assertEquals(Outcome.NO_LOCATION, engine.resolve(ctx, "Kardenna", Mode.SPAWN).outcome());
        assertSame(spawnless, engine.resolve(ctx, "Kardenna", Mode.SPAWN).candidate());
        assertEquals(Outcome.FOUND, engine.resolve(ctx, "Kardenna", Mode.REGION).outcome());
        assertEquals(Outcome.FOUND, engine.resolve(ctx, "Kardenna", Mode.DEFAULT).outcome(), "falls back to the region");
        assertEquals(Outcome.NO_LOCATION, engine.resolve(ctx, "Bare", Mode.DEFAULT).outcome());
        assertEquals(Outcome.FOUND, engine.resolve(ctx, "Kardenna Mill", Mode.REGION).outcome(),
            "a mode word means nothing for a Location, as today");
    }

    @Test
    void aRefusedListResolvesAndCompletesToNothing() {
        DestinationEngine engine = new DestinationEngine(List.of(Scope.empty("e", 1, "x", c -> true, "no way")));
        Resolution refused = engine.resolve(ctx, "Kardenna", Mode.DEFAULT);
        assertEquals(Outcome.NOT_ALLOWED, refused.outcome());
        assertEquals("no way", refused.reason());
        assertNull(refused.destination());
        assertEquals(List.of(), engine.complete(ctx, List.of("")));
        assertFalse(engine.isComplete(ctx, List.of("Kardenna")));
    }

    @Test
    void completionWorksInsideTheListOnly() {
        assertEquals(List.of("Kardenna", "Keep", "district:Market", "town:Market", "Old"), world().complete(ctx, List.of("")),
            "no 'Far' - another world is hidden from completion (E11)");
        assertEquals(List.of("Gate"), world().complete(ctx, List.of("keep", "")));
        assertEquals(List.of("gate:Keep"), world().complete(ctx, List.of("gate:")));
        assertTrue(world().isComplete(ctx, List.of("Keep", "Gate")));
        assertFalse(world().isComplete(ctx, List.of("Keep")));
        assertFalse(world().isComplete(ctx, List.of("Market")), "ambiguous is not complete");
    }

    // ==================== re-validation ====================

    @Test
    void aSessionStaysWhileItsKeyIsListedEvenWhenTheCandidateChanged() {
        Candidate before = Fixtures.objective(7, "Keep Gate", "capture", 1);
        Candidate after = before.withDetail("defend, under attack").withPriority(0).withLabel("Keep Gate (yours)");
        AtomicReference<List<Candidate>> listed = new AtomicReference<>(List.of(before, Fixtures.teamSpawn(3, "Team spawn", 2)));
        Provider match = Provider.of("m", c -> listed.get());
        DestinationEngine engine = new DestinationEngine(List.of(always("m", 1, match)));
        AllowedDestination handle = engine.resolve(ctx, "Keep Gate", Mode.DEFAULT).destination();

        listed.set(List.of(Fixtures.teamSpawn(3, "Team spawn", 2), after));
        Revalidation kept = engine.revalidate(ctx.withModelVersion(2), handle);
        assertTrue(kept.allowed());
        assertSame(after, kept.candidate(), "the candidate as listed now");
        assertNull(kept.reason());

        listed.set(List.of(Fixtures.teamSpawn(3, "Team spawn", 2)));
        Revalidation dropped = engine.revalidate(ctx, handle);
        assertFalse(dropped.allowed());
        assertEquals("Keep Gate is no longer part of the m.", dropped.reason());
        assertNull(dropped.candidate());
    }

    @Test
    void aRefusingScopeEndsTheSessionWithItsReason() {
        Scope frozen = Scope.empty("frozen", 100, "x", NavigationContext::frozen, "You can't navigate while frozen.");
        DestinationEngine engine = new DestinationEngine(List.of(frozen, always("w", 1, catalogue)));
        AllowedDestination handle = engine.resolve(ctx, "Kardenna", Mode.DEFAULT).destination();
        Revalidation ended = engine.revalidate(ctx.withFrozen(true), handle);
        assertFalse(ended.allowed());
        assertEquals("You can't navigate while frozen.", ended.reason());
        assertTrue(engine.revalidate(ctx, handle).allowed());
    }

    @Test
    void aHandleOfAnotherPlayerIsAProgrammingError() {
        DestinationEngine engine = world();
        AllowedDestination handle = engine.resolve(ctx, "Kardenna", Mode.DEFAULT).destination();
        NavigationContext other = NavigationContext.of(UUID.randomUUID(), "world");
        assertThrows(IllegalArgumentException.class, () -> engine.revalidate(other, handle));
        assertThrows(NullPointerException.class, () -> engine.revalidate(ctx, null));
    }
}
