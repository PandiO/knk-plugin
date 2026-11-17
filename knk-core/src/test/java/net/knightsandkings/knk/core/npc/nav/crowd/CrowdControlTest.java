package net.knightsandkings.knk.core.npc.nav.crowd;

import static net.knightsandkings.knk.core.npc.nav.crowd.CrowdWorld.feet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.crowd.CrowdControl.Decision;
import net.knightsandkings.knk.core.npc.nav.crowd.CrowdControl.Kind;

/**
 * Narrow places taken in turns (implementation plan 4c.4 item 4.2, link M4): what a narrow place is, the turn order (an
 * NPC already in it, then first come), oncoming NPCs in a 1-wide stretch (the second to enter steps aside), queue spots,
 * a leader's turn kept for its followers, the deadlock guard and the width cache.
 */
class CrowdControlTest {

    private final Occupancy occupancy = new Occupancy();
    private final CrowdControl crowd = new CrowdControl(occupancy);
    private final Map<Integer, NavPoint> where = new LinkedHashMap<>();
    private long tick;

    /** The straight way from {@code from} to {@code to}, a point every block (feet), without {@code from}. */
    private static List<NavPoint> way(NavPoint from, NavPoint to) {
        List<NavPoint> out = new ArrayList<>();
        double len = from.horizontalDistance(to);
        int n = (int) Math.ceil(len);
        for (int i = 1; i <= n; i++) {
            double t = Math.min(1, i / len);
            out.add(feet(from.x() + (to.x() - from.x()) * t, from.z() + (to.z() - from.z()) * t));
        }
        return out;
    }

    /** Puts the NPCs where they stand into the occupancy and runs the housekeeping, as the service's update does. */
    private void update() {
        tick += Occupancy.UPDATE_TICKS;
        occupancy.begin();
        where.forEach(occupancy::add);
        occupancy.finish(0);
        crowd.housekeeping(tick);
    }

    private Decision decide(CrowdWorld world, int npc, NavPoint to) {
        NavPoint at = where.get(npc);
        return crowd.decide(world, npc, at, way(at, to), tick);
    }

    @Test
    void aOneBlockDoorwayIsANarrowPlaceAThreeWideStreetIsNot() {
        CrowdWorld door = CrowdWorld.doorway(10, 5);
        CrowdControl.Run run = crowd.findRun(door, feet(6.5, 5.5), way(feet(6.5, 5.5), feet(14.5, 5.5)));
        assertNotNull(run);
        assertEquals(3.5, run.entryDist(), 0.01, "the doorway's cell starts 3.5 blocks ahead");
        assertEquals(1, run.cells().size(), "one cell");
        assertEquals(1.0, run.ax(), 1e-9, "its way through it heads east");

        CrowdWorld street = new CrowdWorld().walls(0, 3, 20, 3).walls(0, 7, 20, 7); // 3 wide: z 4..6
        assertNull(crowd.findRun(street, feet(2.5, 5.5), way(feet(2.5, 5.5), feet(14.5, 5.5))), "3 wide is two lanes");
        CrowdWorld twoWide = new CrowdWorld().walls(0, 3, 20, 3).walls(0, 6, 20, 6); // z 4..5
        assertNull(crowd.findRun(twoWide, feet(2.5, 5.0), way(feet(2.5, 5.0), feet(14.5, 5.0))),
            "2 wide still lets two pass");
    }

    @Test
    void farAwayNoTicketWithinApproachTheFirstToComeGoesTheOtherWaits() {
        CrowdWorld world = CrowdWorld.doorway(10, 5);
        where.put(1, feet(3.5, 5.5)); // 6.5 blocks before the door: not arrived yet
        where.put(2, feet(14.5, 5.5));
        update();
        assertEquals(Kind.GO, decide(world, 1, feet(16.5, 5.5)).kind());
        assertFalse(crowd.isWaiting(1) || crowd.hasTurn(1), "not arrived: no ticket");

        where.put(1, feet(7.5, 5.5)); // 2.5 before the door: arrives first
        update();
        assertEquals(Kind.GO, decide(world, 1, feet(16.5, 5.5)).kind(), "the first to come goes");
        assertTrue(crowd.hasTurn(1));
        where.put(2, feet(13.5, 5.5)); // arrives from the other side later
        Decision second = decide(world, 2, feet(4.5, 5.5));
        assertEquals(Kind.WAIT, second.kind(), "one direction at a time");
        assertEquals(0, second.queueIndex());
        assertNotNull(second.spot(), "a queue spot on its side");
        assertTrue(second.spot().x() >= 11.9, "on the east side: " + second.spot());

        where.put(1, feet(10.5, 5.5)); // in the doorway
        update();
        assertEquals(Kind.WAIT, crowd.decideHeld(world, 2, where.get(2), tick).kind(), "still in it");
        where.put(1, feet(12.5, 5.5)); // past it (half length 0.5 + 0.8)
        update();
        assertFalse(crowd.hasTurn(1), "its turn ended when it passed");
        assertEquals(Kind.GO, crowd.decideHeld(world, 2, where.get(2), tick).kind(), "then the other");
        assertTrue(crowd.longestWaitTicks() > 0);
    }

    @Test
    void anNpcAlreadyInItGoesFirstEvenBeforeOneThatCameEarlier() {
        CrowdWorld world = CrowdWorld.doorway(10, 5);
        where.put(1, feet(7.5, 5.5));
        where.put(3, feet(6.5, 5.5));
        update();
        assertEquals(Kind.GO, decide(world, 1, feet(16.5, 5.5)).kind());
        assertEquals(Kind.WAIT, decide(world, 3, feet(16.5, 5.5)).kind(), "behind 1 until it is a block in");
        // 2 started inside the doorway, westward: it is in it, so it goes first... but 1 holds the turn already
        where.put(2, feet(10.5, 5.5));
        Decision inside = decide(world, 2, feet(4.5, 5.5));
        assertEquals(Kind.YIELD, inside.kind(), "1 entered first: 2 steps back out");
        // without anyone holding a turn, the one in it goes first although 3 came earlier
        crowd.forget(1);
        crowd.forget(2);
        Decision again = decide(world, 2, feet(4.5, 5.5));
        assertEquals(Kind.GO, again.kind(), "in it: it goes first");
        assertTrue(crowd.hasTurn(2));
        assertEquals(Kind.WAIT, crowd.decideHeld(world, 3, where.get(3), tick).kind(), "3 waits for it");
    }

    @Test
    void oncomingSinglesInAOneWideStretchTheSecondToEnterStepsAsideAndBothGetThrough() {
        CrowdWorld world = CrowdWorld.passage(10, 20, 5); // 1 wide from x 10 to 20 along z 5
        where.put(1, feet(11.5, 5.5)); // eastward, in the passage
        where.put(2, feet(18.5, 5.5)); // westward, in the passage too
        update();
        assertEquals(Kind.GO, decide(world, 1, feet(24.5, 5.5)).kind(), "the first seen in it has its turn");
        Decision second = decide(world, 2, feet(6.5, 5.5));
        assertEquals(Kind.YIELD, second.kind(), "the second to enter steps aside");
        assertEquals(1, crowd.yields());
        assertNotNull(second.spot(), "out of the passage on its own side");
        assertTrue(second.spot().x() > 20.9, "beyond the passage's east end: " + second.spot());

        where.put(2, second.spot()); // it stepped back out
        for (double x = 13.5; x <= 22.5; x += 3) {
            where.put(1, feet(x, 5.5));
            update();
            if (crowd.hasTurn(1)) {
                assertEquals(Kind.WAIT, crowd.decideHeld(world, 2, where.get(2), tick).kind(), "until 1 has passed");
            }
        }
        where.put(1, feet(24.5, 5.5));
        update();
        assertEquals(Kind.GO, crowd.decideHeld(world, 2, where.get(2), tick).kind(), "then 2 goes through");
        assertEquals(1, crowd.yields(), "counted once");
    }

    @Test
    void theSameDirectionQueuesFirstComeABlockApartToTheSide() {
        CrowdWorld world = CrowdWorld.doorway(10, 5);
        where.put(9, feet(13.5, 5.5)); // westward, holds the turn
        update();
        assertEquals(Kind.GO, decide(world, 9, feet(4.5, 5.5)).kind());
        where.put(1, feet(8.0, 5.5));
        update();
        Decision a = decide(world, 1, feet(16.5, 5.5));
        where.put(2, feet(7.0, 5.5));
        update();
        Decision b = decide(world, 2, feet(16.5, 5.5));
        assertEquals(Kind.WAIT, a.kind());
        assertEquals(Kind.WAIT, b.kind());
        assertEquals(0, a.queueIndex(), "the first to come is first in the queue");
        assertEquals(1, b.queueIndex());
        assertEquals(1.0, a.spot().horizontalDistance(b.spot()), 0.01, "a block apart");
        assertEquals(1.0, Math.abs(a.spot().z() - 5.5), 0.01, "a block to the side of the line");
        assertEquals(2, crowd.longestQueue());

        where.put(9, feet(7.0, 2.5)); // 9 is through (and out of the way)
        update();
        assertEquals(Kind.GO, crowd.decideHeld(world, 1, where.get(1), tick).kind());
        assertEquals(Kind.WAIT, crowd.decideHeld(world, 2, where.get(2), tick).kind(),
            "the next waits until the one ahead is a block into the place");
        where.put(1, feet(11.1, 5.5));
        update();
        assertEquals(Kind.GO, crowd.decideHeld(world, 2, where.get(2), tick).kind());
    }

    @Test
    void aLeaderKeepsItsTurnUntilItsFollowersAreThrough() {
        CrowdWorld world = CrowdWorld.doorway(10, 5);
        crowd.setGroupMembers(id -> id == 1 ? new int[] {5} : new int[0]);
        where.put(1, feet(8.5, 5.5));
        where.put(5, feet(6.5, 5.5));
        update();
        assertEquals(Kind.GO, decide(world, 1, feet(16.5, 5.5)).kind());
        where.put(1, feet(14.5, 5.5)); // the leader is through
        where.put(5, feet(9.0, 5.5)); // its follower just before the door
        where.put(2, feet(12.5, 5.5));
        update();
        assertTrue(crowd.hasTurn(1), "kept for its follower");
        assertEquals(Kind.GO, decide(world, 1, feet(18.5, 5.5)).kind());
        assertTrue(crowd.hasTurn(1), "the leader's own look does not end it either");
        assertEquals(Kind.WAIT, decide(world, 2, feet(4.5, 5.5)).kind(), "nobody cuts into the column");
        where.put(5, feet(12.5, 5.5));
        update();
        assertFalse(crowd.hasTurn(1));
        assertEquals(Kind.GO, crowd.decideHeld(world, 2, where.get(2), tick).kind());
    }

    @Test
    void aQueueNeverHoldsAnNpcLongerThanThirtySeconds() {
        CrowdWorld world = CrowdWorld.doorway(10, 5);
        where.put(1, feet(10.5, 5.5)); // stands in the doorway for ever
        where.put(2, feet(13.5, 5.5));
        update();
        assertEquals(Kind.GO, decide(world, 1, feet(4.5, 5.5)).kind());
        crowd.setGroupMembers(id -> new int[0]);
        assertEquals(Kind.WAIT, decide(world, 2, feet(4.5, 5.5)).kind(), "the same way, but 1 is not a block in yet");
        Decision d = null;
        for (int i = 0; i < CrowdControl.MAX_WAIT_TICKS / Occupancy.UPDATE_TICKS + 1; i++) {
            update();
            d = crowd.decideHeld(world, 2, where.get(2), tick);
            if (d.kind() == Kind.GO) {
                break;
            }
        }
        assertEquals(Kind.GO, d.kind());
        assertTrue(d.overdue(), "let go after 30 s");
        assertEquals(1, crowd.overdue());
        // link B3: why it was let go
        assertEquals(1, crowd.releases().size());
        CrowdControl.Release r = crowd.releases().get(0);
        assertEquals(2, r.npc());
        assertEquals(0, r.earlier());
        assertEquals(1, r.holders().size());
        assertEquals(1, r.holders().get(0).npc(), "1 held the turn");
        assertTrue(r.holders().get(0).blocks(), "and alone stopped 2");
        assertFalse(r.holders().get(0).passed());
        crowd.resetCounts();
        assertTrue(crowd.releases().isEmpty(), "reset with the counts");
    }

    @Test
    void aQueueThatMovesIsNoDeadlockTheGuardCountsFromTheLastEntry() {
        CrowdWorld world = CrowdWorld.doorway(10, 5);
        crowd.setGroupMembers(id -> new int[0]);
        where.put(1, feet(8.5, 5.5)); // eastward, takes the turn and stays before the doorway (a slow climber)
        update();
        assertEquals(Kind.GO, decide(world, 1, feet(16.5, 5.5)).kind());
        where.put(1, feet(9.0, 5.5));
        where.put(3, feet(7.5, 5.5)); // first in the queue
        update();
        assertEquals(Kind.WAIT, decide(world, 3, feet(16.5, 5.5)).kind(), "1 is not a block into the doorway");
        where.put(2, feet(6.8, 5.5)); // second
        update();
        assertEquals(Kind.WAIT, decide(world, 2, feet(16.5, 5.5)).kind());
        long waitTicks = 25 * 20;
        for (long t = 0; t < waitTicks; t += Occupancy.UPDATE_TICKS) {
            update();
            assertEquals(Kind.WAIT, crowd.decideHeld(world, 3, where.get(3), tick).kind());
            assertEquals(Kind.WAIT, crowd.decideHeld(world, 2, where.get(2), tick).kind());
        }
        where.put(1, feet(12.5, 5.5)); // 1 is through after 25 s: 3 goes, the queue moved
        update();
        assertEquals(Kind.GO, crowd.decideHeld(world, 3, where.get(3), tick).kind());
        assertFalse(crowd.decideHeld(world, 3, where.get(3), tick).overdue());
        // 3 stays before the doorway another 25 s: 2 has now waited 50 s, but its queue moved 25 s ago
        for (long t = 0; t < waitTicks; t += Occupancy.UPDATE_TICKS) {
            update();
            assertEquals(Kind.WAIT, crowd.decideHeld(world, 2, where.get(2), tick).kind(), "the queue moved: no deadlock");
        }
        assertEquals(0, crowd.overdue());
        // and once it has not moved for 30 s, the guard still lets 2 go
        Decision d = null;
        for (int i = 0; i < (CrowdControl.MAX_WAIT_TICKS - waitTicks) / Occupancy.UPDATE_TICKS + 2; i++) {
            update();
            d = crowd.decideHeld(world, 2, where.get(2), tick);
            if (d.kind() == Kind.GO) {
                break;
            }
        }
        assertEquals(Kind.GO, d.kind());
        assertTrue(d.overdue());
        assertEquals(1, crowd.overdue());
        assertTrue(crowd.longestWaitTicks() >= 55 * 20, "the wait itself is still counted from its arrival");
    }

    // ==================== link B3: turns judged in 3D ====================

    private static NavPoint at(double x, double y, double z) {
        return new NavPoint(CrowdWorld.W, x, y, z);
    }

    @Test
    void aHolderOnTheFloorAboveOrUnderThePlaceHasLeftItsTurn() {
        CrowdWorld world = CrowdWorld.doorway(10, 5);
        where.put(1, feet(11.5, 5.5)); // westward, a step before the doorway
        update();
        assertEquals(Kind.GO, decide(world, 1, feet(4.5, 5.5)).kind());
        assertTrue(crowd.hasTurn(1));
        where.put(2, feet(7.5, 5.5)); // eastward, arrives
        update();
        assertEquals(Kind.WAIT, decide(world, 2, feet(16.5, 5.5)).kind(), "one direction at a time");

        where.put(1, at(10.5, 68, 5.5)); // over the doorway, on the floor above (as on a stacked flight)
        update();
        assertFalse(crowd.hasTurn(1), "it left the place's floor: its turn ended");
        assertEquals(Kind.GO, crowd.decideHeld(world, 2, where.get(2), tick).kind(), "the other goes at once");
        assertEquals(0, crowd.overdue());
    }

    @Test
    void aHolderThatFellUnderThePlaceHasLeftItsTurnToo() {
        CrowdWorld world = CrowdWorld.doorway(10, 5);
        where.put(1, feet(10.5, 5.5)); // in the doorway
        update();
        assertEquals(Kind.GO, decide(world, 1, feet(4.5, 5.5)).kind());
        where.put(1, at(10.5, 61, 5.5)); // fell under it
        update();
        assertFalse(crowd.hasTurn(1));
    }

    @Test
    void aStepOrAJumpInThePlaceKeepsTheTurn() {
        CrowdWorld world = CrowdWorld.doorway(10, 5);
        where.put(1, feet(10.5, 5.5));
        update();
        assertEquals(Kind.GO, decide(world, 1, feet(4.5, 5.5)).kind());
        where.put(2, feet(7.5, 5.5));
        for (double y : new double[] {65.5, 66.0, 67.25, 64.0}) {
            where.put(1, at(10.5, y, 5.5));
            update();
            assertTrue(crowd.hasTurn(1), "feet at " + y + " are still the doorway's floor");
            assertEquals(Kind.WAIT, decide(world, 2, feet(16.5, 5.5)).kind(), "the oncoming one still waits");
        }
    }

    @Test
    void noQueueFormsBehindAHolderStandingOnTheFloorAbove() {
        CrowdWorld world = CrowdWorld.doorway(10, 5);
        crowd.setGroupMembers(id -> id == 1 ? new int[] {5} : new int[0]);
        where.put(1, feet(8.5, 5.5)); // eastward leader
        where.put(5, feet(6.5, 5.5)); // its follower
        update();
        assertEquals(Kind.GO, decide(world, 1, feet(16.5, 5.5)).kind());
        // the leader climbed on: it stands over the doorway's entry a floor up; its follower is in the doorway
        where.put(1, at(9.0, 69, 5.5));
        where.put(5, feet(10.5, 5.5));
        where.put(2, feet(7.0, 5.5)); // a single the same way
        update();
        assertTrue(crowd.hasTurn(1), "its follower in the doorway keeps the leader's turn");
        assertEquals(Kind.GO, decide(world, 2, feet(16.5, 5.5)).kind(),
            "the leader a floor up is not 'just inside the entry': the single goes, a block behind the follower");
        where.put(3, feet(13.5, 5.5)); // oncoming
        update();
        assertEquals(Kind.WAIT, decide(world, 3, feet(4.5, 5.5)).kind(),
            "but nobody of the other direction cuts into the column while a follower is in it");
    }

    @Test
    void aFollowerOnTheFloorAboveDoesNotKeepItsLeadersTurn() {
        CrowdWorld world = CrowdWorld.doorway(10, 5);
        crowd.setGroupMembers(id -> id == 1 ? new int[] {5} : new int[0]);
        where.put(1, feet(8.5, 5.5));
        where.put(5, feet(6.5, 5.5));
        update();
        assertEquals(Kind.GO, decide(world, 1, feet(16.5, 5.5)).kind());
        where.put(1, feet(14.5, 5.5)); // the leader is through
        where.put(5, at(10.0, 69, 5.5)); // its follower is over the doorway on the floor above, not in it
        update();
        assertFalse(crowd.hasTurn(1), "a follower a floor up keeps no turn here");
    }

    @Test
    void aFlightsPlaceRisesWithItsStepsAndAFlightAboveIsAnotherLevel() {
        // a 1-wide flight going east, one step up per block: feet cells x 10..13 at y 65..68
        CrowdControl.Place flight = new CrowdControl.Place(1, CrowdWorld.W, 1, 0);
        java.util.Set<Long> cells = new java.util.HashSet<>();
        for (int i = 0; i < 4; i++) {
            cells.add(Occupancy.pack(10 + i, 65 + i, 5));
        }
        flight.add(cells, 65);
        assertEquals(1.0, flight.slope, 1e-9, "a step up per block");
        assertEquals(0, flight.levelGap(at(11.5, 66, 5.5)), 1e-9, "on its step");
        assertEquals(0, flight.levelGap(at(11.5, 66.5, 5.5)), 1e-9, "half a step up: its own cell");
        assertFalse(flight.offLevel(at(12.5, 68.2, 5.5)), "a step above its step still counts");
        assertTrue(flight.offLevel(at(11.5, 69, 5.5)), "the flight stacked above (feet 3 up)");
        assertTrue(flight.offLevel(at(11.5, 63, 5.5)), "the flight under it");
        assertTrue(flight.offLevel(at(11.5, 74, 5.5)), "the stair house's next flight up (8 above)");
        // before the flight: the flat landing and the flight's own steps carried on both count
        assertFalse(flight.offLevel(at(7.5, 65, 5.5)), "on the landing before it");
        assertFalse(flight.offLevel(at(7.5, 62, 5.5)), "on the steps leading up to it");
        assertTrue(flight.offLevel(at(7.5, 68, 5.5)), "a floor up, over the landing");
        // after it: the landing at the top
        assertFalse(flight.offLevel(at(15.0, 68, 5.5)), "on the top landing");
        assertTrue(flight.offLevel(at(15.0, 64.5, 5.5)), "under the top landing");
        // a flat doorway stays flat
        CrowdControl.Place flat = new CrowdControl.Place(2, CrowdWorld.W, 1, 0);
        flat.add(java.util.Set.of(Occupancy.pack(10, 65, 5)), 65);
        assertEquals(0, flat.slope, 1e-9);
        assertTrue(flat.offLevel(at(6.5, 68, 5.5)), "flat: no slope carried on before it");
    }

    @Test
    void npcsOnTheSameWayReadTheWorldOnce() {
        CrowdWorld world = CrowdWorld.doorway(10, 5);
        where.put(1, feet(7.5, 5.5));
        where.put(2, feet(7.5, 5.5));
        update();
        decide(world, 1, feet(16.5, 5.5));
        long looks = crowd.reads();
        decide(world, 2, feet(16.5, 5.5));
        assertEquals(looks, crowd.reads(), "the second NPC's widths come from the cache");
        assertTrue(looks <= 2 * 13, "at most two looks per sample of the 6 blocks ahead: " + looks);
        crowd.forgetWidths();
        decide(world, 2, feet(16.5, 5.5));
        assertTrue(crowd.reads() > looks, "a gate change clears the cache");
    }

    @Test
    void theOccupancyFindsWhoStandsWhereAndCountsSharedCells() {
        occupancy.begin();
        occupancy.add(1, feet(3.2, 4.7));
        occupancy.add(2, feet(3.9, 4.1));
        occupancy.add(3, feet(-3.5, -4.5));
        occupancy.finish(0);
        assertTrue(occupancy.occupied(CrowdWorld.W, 3, 65, 4, 1), "2 stands in 1's cell");
        assertTrue(occupancy.occupied(CrowdWorld.W, 3, 65, 4, 99), "anyone there");
        assertEquals(3, occupancy.occupant(CrowdWorld.W, -4, 65, -5, 1), "negative coordinates");
        assertFalse(occupancy.occupied(CrowdWorld.W, -4, 66, -5, 1), "another height");
        assertEquals(1, occupancy.sharedCells());
        assertEquals(2, occupancy.mostInOneCell());
        assertEquals(1, occupancy.rebuildsWithSharing());
        assertEquals(2, occupancy.at(CrowdWorld.W, 3, 65, 4).length);
        assertEquals(feet(-3.5, -4.5), occupancy.positionOf(3));
        assertEquals(0, occupancy.rebuildsWithOverlap(), "0.92 apart: side by side, no overlap");
        assertEquals(0.92, occupancy.closest(), 0.01);

        occupancy.begin();
        occupancy.add(1, feet(3.2, 4.7));
        occupancy.add(2, feet(3.4, 4.6));
        occupancy.finish(0);
        assertEquals(1, occupancy.rebuildsWithOverlap(), "0.22 apart: the bodies overlap");
        occupancy.resetCounts();
        assertEquals(0, occupancy.rebuilds());
        assertTrue(Double.isNaN(occupancy.closest()));
    }

    @Test
    void theHashCostsAFewMicrosecondsPerNpcAtMost() {
        int n = 1000;
        List<NavPoint> spots = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            spots.add(feet(i % 40 + 0.5, i / 40 + 0.5));
        }
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 30; round++) {
            long start = System.nanoTime();
            occupancy.begin();
            for (int i = 0; i < n; i++) {
                occupancy.add(i, spots.get(i));
            }
            occupancy.finish(0);
            best = Math.min(best, System.nanoTime() - start);
        }
        double perNpcPerTick = best / 1e6 / n / Occupancy.UPDATE_TICKS;
        assertEquals(n, occupancy.size());
        assertTrue(perNpcPerTick < 0.003, "the rebuild every 5 ticks costs " + perNpcPerTick + " ms per NPC per tick");
        for (int i = 0; i < n; i++) {
            assertEquals(i, occupancy.occupant(CrowdWorld.W, i % 40, 65, i / 40, -1));
        }
    }

    @Test
    void theHeadingOfAWayIsOneOfEight() {
        assertEquals(0, CrowdControl.heading8(1, 0));
        assertEquals(2, CrowdControl.heading8(0, 1));
        assertEquals(4, CrowdControl.heading8(-1, 0));
        assertEquals(6, CrowdControl.heading8(0, -1));
        assertEquals(1, CrowdControl.heading8(1, 0.9));
    }

    @Test
    void theStatsLineReadsAsThePlanSays() {
        assertEquals("crowd: waits 3, yields 1, longest queue 2", CrowdControl.statsLine(3, 1, 2));
    }
}
