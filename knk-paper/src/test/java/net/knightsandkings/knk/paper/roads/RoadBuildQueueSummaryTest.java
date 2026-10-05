package net.knightsandkings.knk.paper.roads;

import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.roads.build.TileBuildResult;
import net.knightsandkings.knk.core.roads.build.TileBuildResult.Correction;
import net.knightsandkings.knk.core.roads.build.TileProposal;
import net.knightsandkings.knk.core.roads.build.TileBuildResult.CorrectionKind;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The build summary's corrections line (smoke test 2026-10-04, finding L) and a curated tile's proposal summary (plan §5.7). */
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

    private static List<String> lines(RoadBuildJob.Outcome outcome) {
        return RoadBuildQueue.summary(outcome).stream().map(c -> PlainTextComponentSerializer.plainText().serialize(c)).toList();
    }

    private static TileProposal.Item added(int n) {
        return new TileProposal.Item(n, TileProposal.Kind.EDGE_ADDED, 0,
            new TileProposal.End(3588, 0, 64, 0, RoadNodeKind.JUNCTION), new TileProposal.End(0, 10, 64, 0, RoadNodeKind.ENDPOINT),
            List.of(new int[] {0, 64, 0}, new int[] {10, 64, 0}), List.of(), 10, 3, OptionalInt.empty(), List.of(), List.of(),
            List.of(), null, null, "");
    }

    private static RoadBuildJob.Outcome curated(List<TileProposal.Item> items, int hidden) {
        TileBuildResult build = new TileBuildResult(6, 900, 1, List.of(), List.of(), List.of());
        TileProposal proposal = new TileProposal(5, 6, "Pandi", 900, 1, List.of(), items, List.of());
        return new RoadBuildJob.Outcome(new TileKey("world", 2, -2), true, null, build, null, 40, 900, List.of(), 12_000,
            new RoadProposals.Created(proposal, hidden));
    }

    @Test
    void aCuratedTilesBuildSummarisesTheProposalWithReviewButtons() {
        List<String> lines = lines(curated(List.of(added(1), added(2)), 3));

        assertEquals("[Road] Tile 2,-2 is curated - rebuilt in 12 s as a proposal of 2 change(s): 2 added, 3 hidden by the rejected list."
            + " Nothing changed yet.", lines.get(0));
        assertTrue(lines.get(1).contains("[review] [accept all] [reject all]"), lines.get(1));
        assertTrue(lines.get(2).startsWith(" 1 added edge 10 m (#3588 → new endpoint)"), lines.get(2));
        assertEquals(4, lines.size());
    }

    @Test
    void aCuratedTileWithoutChangesSaysSo() {
        assertEquals("[Road] Tile 2,-2 rebuilt in 12 s: no changes against the curated graph; it now counts as built with v6",
            lines(curated(List.of(), 0)).get(0));
    }

    @Test
    void noLineWithoutCorrections() {
        assertTrue(RoadBuildQueue.correctionsLine(List.of()).isEmpty());
        assertEquals(" corrections: 0 prune(s), 1 anchor(s), 0 designed plaza(s)",
            plain(RoadBuildQueue.correctionsLine(List.of(new Correction(4, CorrectionKind.ANCHOR, true, "split the road", 1, 64, 1)))));
    }
}
