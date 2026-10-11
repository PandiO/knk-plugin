package net.knightsandkings.knk.paper.roads;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import net.knightsandkings.knk.core.roads.build.EdgeTagging;
import net.knightsandkings.knk.core.roads.route.TrailCentring;

/**
 * KNG-110 (P4): the road cells across an edge at a sample, for {@link LiveEdgeTags}. The road surface answers only in
 * loaded chunks, and most of the network lies in unloaded ones: a cross-section is remembered once its chunks were
 * loaded, and a missing one loads its chunks in the background, so the next pass knows the road's width there. Without
 * that, a stretch's tags would change as players load and unload its chunks, and re-route them each time. Main thread.
 */
final class CrossSections {

    /** A sample and the way across the road there (the normal in thousandths). */
    private record Key(String world, int x, int y, int z, int nx, int nz) {
    }

    private final LiveEdgeTags.Probe probe;
    private final Map<Key, List<TrailCentring.Cell>> known = new HashMap<>();
    private final Set<Key> loading = new HashSet<>();

    CrossSections(LiveEdgeTags.Probe probe) {
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    /**
     * The road cells across the edge at {@code samples[i]} ({@link TrailCentring#across}, the trail's road-cell rule),
     * across the way the samples around it go. Empty when not known yet (its chunks are loading) or not on a road.
     */
    List<TrailCentring.Cell> at(String world, TrailCentring.Ground ground, List<EdgeTagging.Sample> samples, int i) {
        EdgeTagging.Sample s = samples.get(i);
        EdgeTagging.Sample before = samples.get(Math.max(0, i - 1));
        EdgeTagging.Sample after = samples.get(Math.min(samples.size() - 1, i + 1));
        double dx = after.x() - before.x();
        double dz = after.z() - before.z();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-9) {
            return List.of();
        }
        double[] normal = {-dz / len, dx / len};
        double[] p = {s.x() + 0.5, s.y(), s.z() + 0.5};
        Key key = new Key(world, s.x(), s.y(), s.z(), (int) Math.round(normal[0] * 1000), (int) Math.round(normal[1] * 1000));
        if (loaded(world, s.x(), s.z())) {
            List<TrailCentring.Cell> cells = TrailCentring.across(p, normal, ground);
            known.put(key, cells);
            return cells;
        }
        List<TrailCentring.Cell> cells = known.get(key);
        if (cells != null) {
            return cells;
        }
        if (loading.add(key)) {
            load(world, s.x(), s.z(), () -> {
                loading.remove(key);
                if (loaded(world, s.x(), s.z())) {
                    known.put(key, TrailCentring.across(p, normal, ground));
                }
            });
        }
        return List.of();
    }

    /** Remembered cross-sections, for the status line. */
    int size() {
        return known.size();
    }

    private boolean loaded(String world, int x, int z) {
        int r = TrailCentring.MAX_HALF_WIDTH;
        for (int cx = (x - r) >> 4; cx <= (x + r) >> 4; cx++) {
            for (int cz = (z - r) >> 4; cz <= (z + r) >> 4; cz++) {
                if (!probe.loaded(world, cx << 4, cz << 4)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Loads every unloaded chunk within reach of {@code (x, z)}; {@code then} once they all are. */
    private void load(String world, int x, int z, Runnable then) {
        int r = TrailCentring.MAX_HALF_WIDTH;
        List<int[]> missing = new ArrayList<>();
        for (int cx = (x - r) >> 4; cx <= (x + r) >> 4; cx++) {
            for (int cz = (z - r) >> 4; cz <= (z + r) >> 4; cz++) {
                if (!probe.loaded(world, cx << 4, cz << 4)) {
                    missing.add(new int[] {cx << 4, cz << 4});
                }
            }
        }
        AtomicInteger left = new AtomicInteger(missing.size());
        for (int[] chunk : missing) {
            probe.load(world, chunk[0], chunk[1], () -> {
                if (left.decrementAndGet() == 0) {
                    then.run();
                }
            });
        }
    }
}
