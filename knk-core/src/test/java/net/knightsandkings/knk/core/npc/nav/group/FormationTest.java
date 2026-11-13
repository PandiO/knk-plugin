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
        assertEquals(2.0, defaults.formation().spacing());
        assertEquals(4.0, defaults.rejoinBlocks());
        assertEquals(24.0, defaults.regroupBlocks());
        assertEquals(1.3, defaults.catchUpSpeed());
        assertEquals(16.0, defaults.hopBlocks());
        assertEquals(60, defaults.noProgressTicks());

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
}
