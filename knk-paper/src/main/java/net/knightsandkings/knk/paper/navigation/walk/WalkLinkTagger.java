package net.knightsandkings.knk.paper.navigation.walk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.bukkit.World;

import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.paper.navigation.NavigationService;

/**
 * The tags of a walk link (off-road navigation phase 2, PandiO/knk-workspace#705, DESIGN § 5.2.3): the WorldGuard regions
 * at the path's points (feet level, as the road samples regions; every {@link #REGION_SAMPLE} points and the last) and
 * the gate doors whose closed footprint holds a cell's feet or head block, each in path order without repeats. The
 * per-player access policy then judges a link like a road edge with those tags. Main thread (WorldGuard).
 */
public final class WalkLinkTagger implements NavigationService.LinkTagger {

    /** Regions are looked up at every second point of the path (a walk path's points are a block apart). */
    static final int REGION_SAMPLE = 2;

    private final BiFunction<World, double[], Set<String>> regionsAt;
    private final Function<String, GateCells> gatesOf;

    /**
     * @param regionsAt the regions at a floor point (feet level above it): {@code NavigationAccess.regionsAt}
     * @param gatesOf   the gate cells of a world now ({@code GateCellsIndex.of})
     */
    public WalkLinkTagger(BiFunction<World, double[], Set<String>> regionsAt, Function<String, GateCells> gatesOf) {
        this.regionsAt = Objects.requireNonNull(regionsAt, "regionsAt");
        this.gatesOf = Objects.requireNonNull(gatesOf, "gatesOf");
    }

    @Override
    public Tags tags(World world, List<double[]> floorPoints) {
        Set<String> regions = new LinkedHashSet<>();
        Set<Integer> doors = new LinkedHashSet<>();
        GateCells gates = world == null ? GateCells.NONE : gatesOf.apply(world.getName());
        for (int i = 0; i < floorPoints.size(); i++) {
            double[] p = floorPoints.get(i);
            int x = (int) Math.floor(p[0] + 0.5);
            int y = (int) Math.floor(p[1]);
            int z = (int) Math.floor(p[2] + 0.5);
            if (world != null && (i % REGION_SAMPLE == 0 || i == floorPoints.size() - 1)) {
                regions.addAll(regionsAt.apply(world, new double[] {x, y, z}));
            }
            for (int dy = 1; dy <= 2; dy++) {
                OptionalInt door = gates == null ? OptionalInt.empty() : gates.doorAt(x, y + dy, z);
                if (door.isPresent()) {
                    doors.add(door.getAsInt());
                }
            }
        }
        return new Tags(new ArrayList<>(regions), new ArrayList<>(doors));
    }
}
