package net.knightsandkings.knk.paper.roads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import net.knightsandkings.knk.core.roads.build.PassabilityRules;
import org.junit.jupiter.api.Test;

/** The sampling gates of DESIGN §5.3: flying, riding, swimming and standing still take no sample. */
class RoadSurveySessionTest {

    private final CompactSurfaceGridTest.FakeWorld world = new CompactSurfaceGridTest.FakeWorld(60);
    private final CrossSectionSampler sampler = new CrossSectionSampler(PassabilityRules.of(PassabilityRules::curatedCollidable), -64, 320, 7);
    private final RoadSurveySession session = new RoadSurveySession(UUID.randomUUID(), "world", null, null, sampler);

    private RoadSurveySession.Live walk(long tick, double x, boolean onGround, boolean flying, boolean vehicle, boolean swimming) {
        return session.tick(tick, x, 61.0, 10.5, -90f, onGround, flying, false, vehicle, swimming, false, world);
    }

    @Test
    void walkingOnTheGroundSamplesEveryNewBlock() {
        for (int x = 0; x < 30; x++) {
            world.set(x, 60, 10, "STONE_BRICKS");
        }
        RoadSurveySession.Live first = walk(0, 1.5, true, false, false, false);
        assertEquals(SurveySamplingGate.Verdict.STANDING_STILL, first.verdict(), "the first tick has no movement yet");
        assertEquals(0, session.sampleCount());

        walk(4, 2.5, true, false, false, false);
        walk(8, 3.5, true, false, false, false);
        walk(12, 3.7, true, false, false, false); // same block, moved a little
        assertEquals(2, session.sampleCount(), "one sample per new floor block");
        assertEquals(2, session.breadcrumb().size());
        assertTrue(session.breadcrumb().get(0).onRoad());
        assertEquals(60, session.breadcrumb().get(0).y(), "breadcrumbs are floor blocks");
    }

    @Test
    void flyingRidingSwimmingAndStandingStillAreIgnored() {
        for (int x = 0; x < 30; x++) {
            world.set(x, 60, 10, "STONE_BRICKS");
        }
        walk(0, 1.5, true, false, false, false);
        assertEquals(SurveySamplingGate.Verdict.FLYING, walk(4, 3.5, false, true, false, false).verdict());
        assertEquals(SurveySamplingGate.Verdict.RIDING, walk(8, 5.5, true, false, true, false).verdict());
        assertEquals(SurveySamplingGate.Verdict.SWIMMING, walk(12, 7.5, true, false, false, true).verdict());
        assertEquals(SurveySamplingGate.Verdict.STANDING_STILL, walk(16, 7.55, true, false, false, false).verdict());
        assertEquals(SurveySamplingGate.Verdict.NOT_ON_GROUND, walk(20, 9.5, false, false, false, false).verdict());
        assertEquals(0, session.sampleCount());

        assertEquals(SurveySamplingGate.Verdict.SAMPLE, walk(24, 11.5, true, false, false, false).verdict());
        assertEquals(1, session.sampleCount());
    }

    @Test
    void theLiveEstimateAppearsAfterFiveSamples() {
        for (int x = 0; x < 30; x++) {
            for (int z = 9; z <= 11; z++) {
                world.set(x, 60, z, "STONE_BRICKS");
            }
        }
        RoadSurveySession.Live live = null;
        for (int i = 0; i < 8; i++) {
            live = walk(i * 4L, 1.5 + i, true, false, false, false);
        }
        assertEquals(7, session.sampleCount());
        assertTrue(live.topMaterials().contains("stone bricks"), live.topMaterials());
        assertTrue(live.width().endsWith("wide"), live.width());
        assertEquals(7, session.stats().samples());
    }
}
