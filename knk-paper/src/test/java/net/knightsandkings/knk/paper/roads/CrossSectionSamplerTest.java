package net.knightsandkings.knk.paper.roads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import net.knightsandkings.knk.core.roads.build.PassabilityRules;
import net.knightsandkings.knk.core.roads.survey.SurveySample;
import org.junit.jupiter.api.Test;

/** DESIGN §5.3 sampling: floor through overlays, cross-section stopping at walls, |Δy| ≤ 1 per step. */
class CrossSectionSamplerTest {

    private final CompactSurfaceGridTest.FakeWorld world = new CompactSurfaceGridTest.FakeWorld(60);
    private final CrossSectionSampler sampler = new CrossSectionSampler(PassabilityRules.of(PassabilityRules::curatedCollidable), -64, 320, 7);

    /** A 5-wide road along x at z = 8..12 (bricks, andesite kerbs), a wall at z = 14, a one-block step at z = 6. */
    private void road() {
        for (int x = 0; x < 30; x++) {
            world.set(x, 60, 8, "POLISHED_ANDESITE");
            for (int z = 9; z <= 11; z++) {
                world.set(x, 60, z, "STONE_BRICKS");
            }
            world.set(x, 60, 12, "POLISHED_ANDESITE");
            world.set(x, 61, 14, "STONE"); // a wall (one block high, two needed for headroom → not standable)
            world.set(x, 62, 14, "STONE");
            world.set(x, 61, 6, "GRASS_BLOCK"); // a step up: standable at y = 61
        }
    }

    @Test
    void floorUnderTheFeetLooksThroughOverlays() {
        road();
        world.set(5, 61, 10, "SNOW");
        // standing on the snow layer: feet block 61 holds the overlay, floor is 60
        Optional<CrossSectionSampler.Floor> floor = sampler.floorUnder(world, 5, 61, 10);
        assertTrue(floor.isPresent());
        assertEquals(60, floor.get().y());
        assertEquals("STONE_BRICKS", floor.get().material());
        assertEquals("SNOW", floor.get().overlay());

        // standing on plain bricks: feet block 61 is air
        floor = sampler.floorUnder(world, 6, 61, 10);
        assertEquals(new CrossSectionSampler.Floor(60, "STONE_BRICKS", null), floor.orElseThrow());

        // mid-air: nothing within two blocks
        assertTrue(sampler.floorUnder(world, 6, 65, 10).isEmpty());
    }

    @Test
    void theFloorBlockForAPlayersFeetIsTheBlockTheyStandOn() {
        road();
        world.set(5, 61, 10, "SNOW");
        world.set(7, 60, 10, "DIRT_PATH");
        world.set(8, 60, 10, "STONE_BRICK_SLAB");

        assertEquals(60, sampler.floorY(world, 6, 61.0, 10), "on full bricks (feet 61.0) the bricks, not the block under them");
        assertEquals(60, sampler.floorY(world, 7, 60.9375, 10), "on a dirt path (15/16 high)");
        assertEquals(60, sampler.floorY(world, 8, 60.5, 10), "on a bottom slab");
        assertEquals(60, sampler.floorY(world, 5, 61.125, 10), "on a snow layer: the block under it");
        assertEquals(69, sampler.floorY(world, 6, 70.4, 10), "mid-air (a ladder): the block under the feet block");
    }

    @Test
    void crossSectionRunsPerpendicularAndStopsAtWalls() {
        road();
        CrossSectionSampler.Floor centre = sampler.floorUnder(world, 10, 61, 10).orElseThrow();
        // walking along +x → lateral is (−0, 1) → offsets run along z
        SurveySample sample = sampler.sample(world, 10, 60, 10, centre, SurveySamplingGate.lateral(new double[] {1, 0}));

        assertEquals("STONE_BRICKS", sample.floor());
        assertEquals("STONE_BRICKS", sample.materialAt(1));
        assertEquals("POLISHED_ANDESITE", sample.materialAt(2));
        assertEquals("GRASS_BLOCK", sample.materialAt(3));
        assertNull(sample.materialAt(4), "the wall at z = 14 stops the scan");
        assertNull(sample.materialAt(5));
        assertEquals("POLISHED_ANDESITE", sample.materialAt(-2));
        assertEquals("GRASS_BLOCK", sample.materialAt(-3), "z = 7 grass");
        assertEquals("GRASS_BLOCK", sample.materialAt(-4), "z = 6: one block up is still reachable");
        assertEquals("GRASS_BLOCK", sample.materialAt(-5), "z = 5 back down");
        assertEquals(SurveySample.WIDTH, sample.offsets().size());
    }

    @Test
    void aTwoBlockDropEndsTheSection() {
        road();
        for (int z = 0; z <= 7; z++) {
            world.set(10, 60, z, "AIR"); // a cliff on the −z side: ground two blocks lower
            world.set(10, 59, z, "AIR");
            world.set(10, 58, z, "GRASS_BLOCK");
        }
        CrossSectionSampler.Floor centre = sampler.floorUnder(world, 10, 61, 10).orElseThrow();
        SurveySample sample = sampler.sample(world, 10, 60, 10, centre, new double[] {0, 1});
        assertEquals("POLISHED_ANDESITE", sample.materialAt(-2));
        assertNull(sample.materialAt(-3), "a two-block drop is not walkable within |Δy| ≤ 1");
    }
}
