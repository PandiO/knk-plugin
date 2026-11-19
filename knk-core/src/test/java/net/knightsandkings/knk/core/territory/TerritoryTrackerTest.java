package net.knightsandkings.knk.core.territory;

import static net.knightsandkings.knk.core.territory.TerritoryFixtures.WORLD;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.build;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.cuboid;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.district;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.placed;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.polygon;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/** The lookup on chunk crossings, per block column in split chunks (territory DESIGN § 1, entry and exit). */
class TerritoryTrackerTest {

    private final UUID player = UUID.randomUUID();
    private final AtomicReference<TerritoryCache> cache = new AtomicReference<>(
            build(List.of(), placed(district(10, "d", 1, 40), cuboid("d", 0, 0, 15, 15))));
    private final TerritoryTracker tracker = new TerritoryTracker(cache::get);

    @Test
    void theFirstPosition_setsTheStart_withoutAChange() {
        assertNull(tracker.moved(player, WORLD, 5, 5));
        assertEquals(TerritoryHolder.town(1), tracker.current(player).owner());
    }

    @Test
    void leavingAndEntering_areChanges_movesInsideTheAreaAreNot() {
        tracker.moved(player, WORLD, 5, 5);
        assertNull(tracker.moved(player, WORLD, 30, 5), "still Phoenix");

        TerritoryTracker.Change exit = tracker.moved(player, WORLD, 80, 5);
        assertNotNull(exit);
        assertEquals(TerritoryHolder.town(1), exit.from().owner());
        assertTrue(exit.to().isWilderness());

        TerritoryTracker.Change entry = tracker.moved(player, WORLD, 50, 5);
        assertNotNull(entry);
        assertEquals(TerritoryHolder.town(1), entry.to().owner());
    }

    @Test
    void insideASplitChunk_everyColumnIsLookedUp() {
        tracker.moved(player, WORLD, 50, 8);  // chunk 3 is split (border at x 55/56)
        assertNull(tracker.moved(player, WORLD, 55, 8));
        TerritoryTracker.Change exit = tracker.moved(player, WORLD, 56, 8);
        assertNotNull(exit, "one block further, same chunk");
        assertTrue(exit.to().isWilderness());
    }

    @Test
    void aRebuiltCache_isLookedUpAgain_evenInTheSameChunk() {
        tracker.moved(player, WORLD, 30, 5);
        cache.set(build(List.of(), placed(district(10, "d", 2, 40), polygon("d", 0, 0, 15, 0, 15, 15, 0, 15))));

        TerritoryTracker.Change change = tracker.moved(player, WORLD, 30, 5);
        assertNotNull(change);
        assertEquals(TerritoryHolder.town(2), change.to().owner());
    }

    @Test
    void forget_startsOver() {
        tracker.moved(player, WORLD, 5, 5);
        tracker.forget(player);
        assertNull(tracker.moved(player, WORLD, 500, 5));
        assertEquals(1, tracker.tracked());
    }
}
