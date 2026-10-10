package net.knightsandkings.knk.paper.navigation;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

import org.bukkit.World;
import org.bukkit.block.Block;

import net.knightsandkings.knk.core.roads.build.PassabilityRules;
import net.knightsandkings.knk.core.roads.route.TrailCentring;

/**
 * KNG-76: {@link TrailCentring}'s view of the world - a road cell is a block of a road profile's floor material
 * (what the builder's mask is made of: {@code RoadNetworkCache.roadMaterialNames()}) with room to stand above it, at
 * the trail's height or one block above or below. Columns in unloaded chunks are no road (the trail stays where it
 * is). Main thread.
 */
final class RoadSurfaceGround implements TrailCentring.Ground {

    private static final int[] STEPS = {0, 1, -1};

    private final World world;
    private final Set<String> roadMaterials;

    RoadSurfaceGround(World world, Set<String> roadMaterials) {
        this.world = Objects.requireNonNull(world, "world");
        this.roadMaterials = Objects.requireNonNull(roadMaterials, "roadMaterials");
    }

    @Override
    public OptionalInt roadFloor(int x, int z, int nearY) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return OptionalInt.empty();
        }
        for (int dy : STEPS) {
            int y = nearY + dy;
            Block floor = world.getBlockAt(x, y, z);
            if (roadMaterials.contains(floor.getType().name()) && world.getBlockAt(x, y + 1, z).isPassable()) {
                return OptionalInt.of(y);
            }
        }
        return OptionalInt.empty();
    }

    @Override
    public boolean stairOrSlab(int x, int y, int z) {
        return PassabilityRules.isStairOrSlab(world.getBlockAt(x, y, z).getType().name());
    }
}
