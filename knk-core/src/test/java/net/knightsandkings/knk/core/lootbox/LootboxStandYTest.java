package net.knightsandkings.knk.core.lootbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * PandiO/knk-workspace#487: a lootbox spawn area's region keeps the Y range it was drawn with, so the spawner looks for
 * a spot inside it, not only at the column's surface.
 */
class LootboxStandYTest {

    @Test
    void aSurfaceInsideTheRegionIsTheSpot() {
        // A region drawn from Y 64 to 68 on open ground at Y 63: the box stands at 64, as on a full-height region.
        assertEquals(OptionalInt.of(64), LootboxSpawnPlanner.standY(64, 64, 68, y -> y == 64));
    }

    @Test
    void aSurfaceInsideTheRegionThatIsNoGood_isNoSpot_evenWithGroundBelow() {
        // Water at the surface never sends a box into a cave below it.
        assertEquals(OptionalInt.empty(), LootboxSpawnPlanner.standY(66, 60, 68, y -> y == 62));
    }

    @Test
    void underARoof_theHighestStandableYInsideTheRegion() {
        // The column's highest block is a roof at Y 79; the floor inside the region is at 64.
        List<Integer> asked = new ArrayList<>();
        OptionalInt y = LootboxSpawnPlanner.standY(80, 64, 68, candidate -> {
            asked.add(candidate);
            return candidate == 64;
        });

        assertEquals(OptionalInt.of(64), y);
        assertEquals(List.of(68, 67, 66, 65, 64), asked, "from the region's top down, never above it");
    }

    @Test
    void underARoofWithoutAFloorInTheRegion_isNoSpot() {
        assertEquals(OptionalInt.empty(), LootboxSpawnPlanner.standY(80, 64, 68, y -> false));
    }

    @Test
    void aRegionAboveTheSurface_isNoSpot() {
        assertEquals(OptionalInt.empty(), LootboxSpawnPlanner.standY(60, 64, 68, y -> true));
    }

    @Test
    void aFullHeightRegion_behavesAsBefore() {
        // Before #487 every area spanned -64..319 and only the surface counted.
        assertEquals(OptionalInt.of(72), LootboxSpawnPlanner.standY(72, -64, 319, y -> y == 72 || y == 30));
        assertEquals(OptionalInt.empty(), LootboxSpawnPlanner.standY(72, -64, 319, y -> y == 30));
    }

    @Test
    void theSpotIsAlwaysInsideTheRegion() {
        Set<Integer> standable = Set.of(50, 63, 64, 66, 69, 90);
        for (int surface = 40; surface <= 100; surface++) {
            OptionalInt y = LootboxSpawnPlanner.standY(surface, 64, 68, standable::contains);
            y.ifPresent(found -> assertTrue(found >= 64 && found <= 68, "y " + found));
        }
    }
}
