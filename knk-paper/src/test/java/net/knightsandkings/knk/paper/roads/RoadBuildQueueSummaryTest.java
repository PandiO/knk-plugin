package net.knightsandkings.knk.paper.roads;

import net.knightsandkings.knk.core.roads.build.TileBuildResult.Correction;
import net.knightsandkings.knk.core.roads.build.TileBuildResult.CorrectionKind;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The build summary's corrections line (smoke test 2026-10-04, finding L). */
class RoadBuildQueueSummaryTest {

    private static String plain(Optional<Component> line) {
        return PlainTextComponentSerializer.plainText().serialize(line.orElseThrow());
    }

    @Test
    void countsUsedAndStalePrunesAnchorsAndPlazas() {
        List<Correction> corrections = List.of(
            new Correction(1, CorrectionKind.PRUNED_DEAD_END, true, "left out the dead end 0.0 blocks away", 0, 64, 0),
            new Correction(2, CorrectionKind.PRUNED_EDGE, false, "no chain within 3.0 blocks", 5, 64, 5),
            new Correction(3, CorrectionKind.PRUNED_EDGE, true, "left out the chain 1.0 blocks away", 9, 64, 9),
            new Correction(4, CorrectionKind.ANCHOR, true, "split the road", 1, 64, 1),
            new Correction(5, CorrectionKind.ANCHOR, false, "no centreline within 3 blocks", 2, 64, 2),
            new Correction(6, CorrectionKind.PLAZA, true, "footprint of 120 road cells", 3, 64, 3));
        assertEquals(" corrections: 3 prune(s) (2 used, 1 stale), 2 anchor(s) (1 off the road), 1 designed plaza(s)",
            plain(RoadBuildQueue.correctionsLine(corrections)));
    }

    @Test
    void noLineWithoutCorrections() {
        assertTrue(RoadBuildQueue.correctionsLine(List.of()).isEmpty());
        assertEquals(" corrections: 0 prune(s), 1 anchor(s), 0 designed plaza(s)",
            plain(RoadBuildQueue.correctionsLine(List.of(new Correction(4, CorrectionKind.ANCHOR, true, "split the road", 1, 64, 1)))));
    }
}
