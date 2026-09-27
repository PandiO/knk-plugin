package net.knightsandkings.knk.core.roads.survey;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static net.knightsandkings.knk.core.roads.survey.CrossSections.GRASS_BLOCK;
import static net.knightsandkings.knk.core.roads.survey.CrossSections.GRAVEL;
import static net.knightsandkings.knk.core.roads.survey.CrossSections.SNOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurveySampleTest {

    private static List<String> pathSection() {
        List<String> offsets = new ArrayList<>(Collections.nCopies(SurveySample.WIDTH, GRASS_BLOCK));
        offsets.set(SurveySample.CENTRE_INDEX, GRAVEL);
        offsets.set(0, null); // a wall at -7
        return offsets;
    }

    @Test
    void crossSectionConstantsDescribeOffsetsMinusSevenToPlusSeven() {
        assertEquals(-7, SurveySample.MIN_OFFSET);
        assertEquals(7, SurveySample.MAX_OFFSET);
        assertEquals(15, SurveySample.WIDTH);
        assertEquals(7, SurveySample.CENTRE_INDEX);
    }

    @Test
    void factoryTakesTheFloorFromTheCentreCell() {
        SurveySample sample = SurveySample.of(10, 64, -3, true, SNOW, pathSection());

        assertEquals(GRAVEL, sample.floor());
        assertEquals(SNOW, sample.overlay());
        assertTrue(sample.hasOverlay());
        assertEquals(10, sample.x());
        assertEquals(64, sample.y());
        assertEquals(-3, sample.z());
        assertTrue(sample.onGround());
    }

    @Test
    void materialAtMapsOffsetsOntoTheList() {
        SurveySample sample = SurveySample.of(0, 64, 0, true, null, pathSection());

        assertEquals(GRAVEL, sample.materialAt(0));
        assertEquals(GRASS_BLOCK, sample.materialAt(1));
        assertEquals(GRASS_BLOCK, sample.materialAt(7));
        assertNull(sample.materialAt(-7), "a wall is a null cell");
        assertFalse(sample.hasOverlay());
        assertThrows(IllegalArgumentException.class, () -> sample.materialAt(8));
        assertThrows(IllegalArgumentException.class, () -> sample.materialAt(-8));
    }

    @Test
    void offsetsAreCopiedAndUnmodifiable() {
        List<String> offsets = pathSection();
        SurveySample sample = SurveySample.of(0, 64, 0, true, null, offsets);
        offsets.set(1, "TNT");

        assertEquals(GRASS_BLOCK, sample.materialAt(-6), "later edits of the source list are not seen");
        assertThrows(UnsupportedOperationException.class, () -> sample.offsets().set(1, "TNT"));
    }

    @Test
    void wrongCrossSectionSizeIsRejected() {
        List<String> tooShort = Arrays.asList(GRAVEL, GRAVEL, GRAVEL);
        assertThrows(IllegalArgumentException.class, () -> SurveySample.of(0, 64, 0, true, null, tooShort));
        assertThrows(IllegalArgumentException.class, () -> new SurveySample(0, 64, 0, true, GRAVEL, null, tooShort));
    }

    @Test
    void floorMustBeTheCentreCellAndNeverNull() {
        List<String> section = pathSection();
        assertThrows(IllegalArgumentException.class,
            () -> new SurveySample(0, 64, 0, true, GRASS_BLOCK, null, section), "floor differs from offset 0");
        assertThrows(NullPointerException.class, () -> new SurveySample(0, 64, 0, true, null, null, section));

        List<String> centreless = new ArrayList<>(section);
        centreless.set(SurveySample.CENTRE_INDEX, null);
        assertThrows(IllegalArgumentException.class, () -> SurveySample.of(0, 64, 0, true, null, centreless));
    }

    @Test
    void recordEqualityCoversTheCrossSection() {
        SurveySample a = SurveySample.of(1, 64, 1, true, null, pathSection());
        SurveySample b = SurveySample.of(1, 64, 1, true, null, pathSection());
        List<String> other = pathSection();
        other.set(SurveySample.CENTRE_INDEX + 1, GRAVEL);
        SurveySample c = SurveySample.of(1, 64, 1, true, null, other);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(c));
    }
}
