package net.knightsandkings.knk.core.npc.nav.group;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings.Overrides;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings.Scope;
import net.knightsandkings.knk.core.npc.nav.group.Formation.Slot;
import net.knightsandkings.knk.core.npc.nav.group.Formation.Type;

/** Formation slots (decision Q4): column, ranks, loose cluster, escort ring, and the column every one folds into. */
class FormationTest {

    @Test
    void aColumnIsSingleFileBehindTheLeader() {
        Formation column = Formation.column(2);

        assertEquals(List.of(new Slot(2, 0), new Slot(4, 0), new Slot(6, 0)), column.slots(3));
        assertEquals(0, column.halfWidth(3));
        assertEquals(6, column.depth(3));
        assertEquals(0, column.front(3));
    }

    @Test
    void ranksOfTwoArePairsEitherSideOfThePath() {
        Formation pairs = new Formation(Type.RANKS, 2, 2);

        assertEquals(List.of(new Slot(2, -1), new Slot(2, 1), new Slot(4, -1), new Slot(4, 1), new Slot(6, -1)),
            pairs.slots(5));
        assertEquals(1, pairs.halfWidth(5));
        assertEquals(6, pairs.depth(5));
    }

    @Test
    void widerRanksAreCentredOnThePath() {
        Formation ranks = new Formation(Type.RANKS, 1.5, 3);

        assertEquals(List.of(new Slot(1.5, -1.5), new Slot(1.5, 0), new Slot(1.5, 1.5), new Slot(3, -1.5)),
            ranks.slots(4));
        assertEquals(1.5, ranks.halfWidth(4));
    }

    @Test
    void aClusterIsALooseCrowdBehindTheLeaderAndTheSameEveryTime() {
        Formation cluster = new Formation(Type.CLUSTER, 2, 2);

        List<Slot> slots = cluster.slots(12);
        assertEquals(slots, cluster.slots(12), "a slot never jumps about");
        assertEquals(slots.subList(0, 7), cluster.slots(7), "a slot does not move when the group grows");
        Set<Slot> distinct = new HashSet<>(slots);
        assertEquals(12, distinct.size());
        boolean offGrid = false;
        for (Slot slot : slots) {
            assertTrue(slot.back() > 0, "behind the leader: " + slot);
            assertTrue(Math.abs(slot.side()) <= 2 * 1.25 + 1e-9, "within the cluster's width: " + slot);
            offGrid |= Math.abs(slot.side() % 2) > 0.05;
        }
        assertTrue(offGrid, "nudged off the grid");
        for (int i = 0; i < slots.size(); i++) {
            for (int j = i + 1; j < slots.size(); j++) {
                double apart = Math.hypot(slots.get(i).back() - slots.get(j).back(), slots.get(i).side() - slots.get(j).side());
                assertTrue(apart >= 0.9, "slots " + i + " and " + j + " are " + apart + " apart");
            }
        }
    }

    @Test
    void anEscortRingIsAnOctagonAroundTheLeader() {
        Formation ring = new Formation(Type.ESCORT_RING, 2, 2);
        double radius = Formation.RING_RADIUS * 2;

        List<Slot> slots = ring.slots(8);
        for (Slot slot : slots) {
            assertEquals(radius, Math.hypot(slot.back(), slot.side()), 1e-9);
        }
        assertEquals(8, new HashSet<>(slots).size());
        assertEquals(new Slot(-radius, 0), round(slots.get(0)), "the first escort walks straight ahead");
        assertEquals(new Slot(radius, 0), round(slots.get(1)), "the second straight behind");
        assertEquals(new Slot(0, radius), round(slots.get(2)), "then the right");
        assertEquals(new Slot(0, -radius), round(slots.get(3)), "and the left");
        assertEquals(radius, ring.halfWidth(8), 1e-9);
        assertEquals(radius, ring.depth(8), 1e-9);
        assertEquals(radius, ring.front(8), 1e-9);
    }

    @Test
    void aLargerEscortFillsWiderRings() {
        Formation ring = new Formation(Type.ESCORT_RING, 2, 2);
        double radius = Formation.RING_RADIUS * 2;

        List<Slot> slots = ring.slots(8 + 16 + 1);
        assertEquals(2 * radius, Math.hypot(slots.get(8).back(), slots.get(8).side()), 1e-9, "the ninth is on the second ring");
        assertEquals(2 * radius, Math.hypot(slots.get(23).back(), slots.get(23).side()), 1e-9);
        assertEquals(3 * radius, Math.hypot(slots.get(24).back(), slots.get(24).side()), 1e-9);
        assertEquals(25, new HashSet<>(slots).size());
    }

    private static Slot round(Slot slot) {
        return new Slot(Math.round(slot.back() * 1e6) / 1e6 + 0.0, Math.round(slot.side() * 1e6) / 1e6 + 0.0);
    }

    @Test
    void everyFormationFoldsIntoTheSameColumn() {
        for (Type type : Type.values()) {
            Formation formation = new Formation(type, 2, 3);
            assertEquals(Formation.column(2).slots(6), formation.columnSlots(6), type.name());
            assertEquals(6, formation.slots(6).size(), type.name());
            assertEquals(List.of(), formation.slots(0), type.name());
        }
    }

    @Test
    void typesHaveConfigNames() {
        assertEquals("escort-ring", Type.ESCORT_RING.configName());
        assertSame(Type.ESCORT_RING, Type.byName("Escort_Ring"));
        assertSame(Type.COLUMN, Type.byName(" column "));
        assertNull(Type.byName("wedge"));
        assertNull(Type.byName(null));
        for (Type type : Type.values()) {
            // every type is a value of the group-formation setting
            assertEquals(type.configName(), NavigationSettings.GROUP_FORMATION.parser().apply(type.configName()));
        }
    }

    @Test
    void badNumbersAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Formation(Type.COLUMN, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> new Formation(Type.RANKS, 2, 0));
    }

    @Test
    void aGroupsSettingsComeFromTheLayers() {
        GroupSettings defaults = GroupSettings.defaults();
        assertSame(Type.COLUMN, defaults.formation().type());
        assertEquals(1.5, defaults.formation().spacing());
        assertEquals(1.0, defaults.formation().lateralSpacing());
        assertEquals(3, defaults.widthHysteresisBlocks());
        assertEquals(false, defaults.followersFinishTrip());
        assertEquals(4.0, defaults.rejoinBlocks());
        assertEquals(24.0, defaults.regroupBlocks());
        assertEquals(1.3, defaults.catchUpSpeed());
        assertEquals(16.0, defaults.hopBlocks());
        assertEquals(40, defaults.noProgressTicks());

        NavigationSettings settings = NavigationSettings.builder()
            .global(Overrides.of(NavigationSettings.GROUP_SPACING, 1.5).with(NavigationSettings.GROUP_RANKS_WIDTH, 4))
            .build();
        GroupSettings soldiers = GroupSettings.of(settings, new Scope("w", List.of(), null,
            Overrides.of(NavigationSettings.GROUP_FORMATION, "ranks")));
        assertSame(Type.RANKS, soldiers.formation().type(), "the assignment's formation");
        assertEquals(1.5, soldiers.formation().spacing());
        assertEquals(4, soldiers.formation().ranksWidth());
        assertSame(Type.CLUSTER, soldiers.withFormation(new Formation(Type.CLUSTER, 2, 2)).formation().type());
    }

    // ==================== widths (implementation plan 4b.3 items 1 to 3, NAV-22) ====================

    private static Formation of(Type type, int ranksWidth) {
        return new Formation(type, 1.5, 1.0, ranksWidth); // the decided defaults: rows 1.5 apart, shoulder to shoulder
    }

    @Test
    void everyFormationListsItsWidthsWidestFirstAndEndsWithTheColumn() {
        assertEquals(List.of(new Formation.Width(1, false)), of(Type.COLUMN, 2).widths());
        assertEquals(List.of(new Formation.Width(4, false), new Formation.Width(3, false), new Formation.Width(2, false),
            new Formation.Width(1, false)), of(Type.RANKS, 4).widths());
        assertEquals(List.of(new Formation.Width(2, false), new Formation.Width(1, false)), of(Type.RANKS, 2).widths());
        assertEquals(List.of(new Formation.Width(3, false), new Formation.Width(2, false), new Formation.Width(1, false)),
            of(Type.CLUSTER, 2).widths());
        assertEquals(List.of(new Formation.Width(0, true), new Formation.Width(3, false), new Formation.Width(2, false),
            new Formation.Width(1, false)), of(Type.ESCORT_RING, 2).widths());
        assertEquals(3, of(Type.ESCORT_RING, 2).columnLevel());
        assertEquals("3 abreast", new Formation.Width(3, false).describe());
        assertEquals("a column", new Formation.Width(1, false).describe());
        assertEquals("the escort ring", new Formation.Width(0, true).describe());
    }

    @Test
    void ranksStepDownShoulderToShoulderWithRowsOneAndAHalfApart() {
        Formation ranks = of(Type.RANKS, 3);

        assertEquals(List.of(new Slot(1.5, -1), new Slot(1.5, 0), new Slot(1.5, 1), new Slot(3, -1), new Slot(3, 0)),
            ranks.slots(5, 0));
        assertEquals(List.of(new Slot(1.5, -0.5), new Slot(1.5, 0.5), new Slot(3, -0.5), new Slot(3, 0.5), new Slot(4.5, -0.5)),
            ranks.slots(5, 1));
        assertEquals(ranks.columnSlots(5), ranks.slots(5, 2));
        assertEquals(List.of(new Slot(1.5, 0), new Slot(3, 0)), ranks.slots(2, 2));
        assertEquals(1.0, ranks.halfWidth(5, 0), "3 abreast need a road 3 wide");
        assertEquals(0.5, ranks.halfWidth(5, 1), "2 abreast one 2 wide");
        assertEquals(0.0, ranks.halfWidth(5, 2));
        assertEquals(3.0, ranks.depth(5, 0));
        assertEquals(4.5, ranks.depth(5, 1));
        assertEquals(7.5, ranks.depth(5, 2));
        assertEquals(0.0, ranks.front(5, 1));
        assertEquals(ranks.slots(5, 0), ranks.slots(5), "the widest width is the formation's own");
    }

    @Test
    void theClusterNudgeNeverMakesAWidthWider() {
        Formation cluster = of(Type.CLUSTER, 2);
        for (int n = 1; n <= 30; n++) {
            assertTrue(cluster.halfWidth(n, 0) <= 1.0 + 1e-9, n + " followers, 3 abreast: " + cluster.halfWidth(n, 0));
            assertTrue(cluster.halfWidth(n, 1) <= 0.5 + 1e-9, n + " followers, 2 abreast: " + cluster.halfWidth(n, 1));
            for (Slot slot : cluster.slots(n, 0)) {
                assertTrue(slot.back() > 0, "behind the leader: " + slot);
            }
        }
        boolean nudged = false;
        for (Slot slot : cluster.slots(12, 0)) {
            nudged |= Math.abs(slot.side() - Math.round(slot.side())) > 0.02;
        }
        assertTrue(nudged, "still a crowd, not a grid");
        assertEquals(cluster.columnSlots(4), cluster.slots(4, 2));
    }

    @Test
    void theEscortRingIsOneAndAQuarterLateralSpacingsAroundTheLeader() {
        Formation ring = of(Type.ESCORT_RING, 2);
        for (Slot slot : ring.slots(8, 0)) {
            assertEquals(1.25, Math.hypot(slot.back(), slot.side()), 1e-9);
        }
        assertEquals(1.25, ring.halfWidth(8, 0), 1e-9, "eight escorts fit a 3-wide road");
        assertEquals(1.25, ring.halfWidth(9, 0), 1e-9, "the ninth walks straight ahead on the second ring");
        assertEquals(2.5, ring.halfWidth(11, 0), 1e-9, "the second ring's sides need a wider road");
    }

    @Test
    void theNarrowedEscortRingKeepsTheLeaderInTheMiddleOfTheSecondRow() {
        Formation ring = of(Type.ESCORT_RING, 2);

        // 3 abreast: a row of three ahead, one either side of the leader, rows of three behind
        assertEquals(List.of(new Slot(-1.5, 0), new Slot(-1.5, -1), new Slot(-1.5, 1), new Slot(0, 1), new Slot(0, -1),
            new Slot(1.5, 0), new Slot(1.5, -1), new Slot(1.5, 1), new Slot(3, 0)), ring.slots(9, 1));
        assertEquals(1.0, ring.halfWidth(9, 1));
        assertEquals(1.5, ring.front(9, 1));
        // 2 abreast: a pair ahead, the leader, pairs behind
        assertEquals(List.of(new Slot(-1.5, -0.5), new Slot(-1.5, 0.5), new Slot(1.5, -0.5), new Slot(1.5, 0.5),
            new Slot(3, -0.5)), ring.slots(5, 2));
        assertEquals(0.5, ring.halfWidth(5, 2));
        // the column: one escort ahead, the rest behind
        assertEquals(List.of(new Slot(-1.5, 0), new Slot(1.5, 0), new Slot(3, 0)), ring.slots(3, 3));
        assertEquals(0.0, ring.halfWidth(3, 3));
        assertEquals(1.5, ring.front(3, 3));
        assertEquals(List.of(new Slot(-1.5, 0)), ring.slots(1, 1), "a single escort walks ahead");
    }
}
