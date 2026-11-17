package net.knightsandkings.knk.paper.scenario;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;

import net.knightsandkings.knk.core.npc.nav.AccessRules;
import net.knightsandkings.knk.core.npc.nav.AccessSubject;
import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner;
import net.knightsandkings.knk.core.npc.nav.snapshot.NavSnapshot;
import net.knightsandkings.knk.core.npc.nav.snapshot.NavSnapshotZip;
import net.knightsandkings.knk.core.npc.nav.snapshot.SnapshotBox;

/**
 * Replays a snapshot of {@code /knk npc nav snapshot} in a scenario (link B1, PandiO/knk-workspace#670; chain report
 * decisions 33 and 35.2): places the real blocks of the route's box from its region files, builds its domains'
 * WorldGuard regions (whole shapes, parents, priorities, the entry and exit flags) under scenario ids, answers the
 * access rules the way the plugin's caches did when the snapshot was taken (the fake API data: AllowEntry per domain,
 * each gate door open or closed), and gives the stored route line, its gates, plazas and settings for the scenario to
 * plan on ({@code ScenarioNpcs.start(scene, replay.loop(scene), replay.settings(), replay.accessRules(scene),
 * replay.gates(scene))}).
 * <p>
 * The snapshot's box is put in the scene with its lowest corner at {@link #frame()} (default: the box's own lowest
 * corner at the scene's 0 0 0): scene position = real - frame. Blocks outside the scene's box are counted, not placed.
 * Regions live outside the box: remove them in a cleanup step ({@link #removeRegions}).
 */
public final class SnapshotReplay {

    /** Where committed snapshots live in the plugin jar (beside {@link SceneSchematic#RESOURCE_FOLDER}). */
    public static final String RESOURCE_FOLDER = "scenario-snapshots/";

    private final NavSnapshotZip.Loaded loaded;
    private final int[] frame;
    private final List<String> builtRegions = new ArrayList<>();

    private SnapshotReplay(NavSnapshotZip.Loaded loaded, int[] frame) {
        this.loaded = loaded;
        this.frame = frame.clone();
    }

    /** A replay with the box's lowest corner at the scene's 0 0 0. */
    public static SnapshotReplay of(NavSnapshotZip.Loaded loaded) {
        return new SnapshotReplay(loaded, loaded.snapshot().manifest().boxMin());
    }

    /** The same snapshot put in the scene with the real block {@code frame} at the scene's 0 0 0. */
    public SnapshotReplay at(int x, int y, int z) {
        return new SnapshotReplay(loaded, new int[] {x, y, z});
    }

    /** Reads a zip from the plugin jar's {@value #RESOURCE_FOLDER}. */
    public static SnapshotReplay fromResource(String name) {
        try (InputStream in = SnapshotReplay.class.getClassLoader().getResourceAsStream(RESOURCE_FOLDER + name)) {
            if (in == null) {
                throw new IllegalArgumentException("no snapshot " + RESOURCE_FOLDER + name + " in the plugin jar");
            }
            return of(NavSnapshotZip.read(in));
        } catch (IOException e) {
            throw new IllegalStateException("snapshot " + name + " could not be read: " + e.getMessage(), e);
        }
    }

    /** Reads a zip from a file (one the developer uploaded, in a local run). */
    public static SnapshotReplay fromFile(Path zip) throws IOException {
        return of(NavSnapshotZip.read(zip));
    }

    public NavSnapshot snapshot() {
        return loaded.snapshot();
    }

    /** The real block at the scene's 0 0 0. */
    public int[] frame() {
        return frame.clone();
    }

    // ==================== blocks ====================

    /** What {@link #placeBlocks} did. */
    public record Placed(int placed, int outsideTheScene, int unknownStates, int chunks, int skippedChunks,
                         List<String> problems) {
        public String describe() {
            return placed + " blocks from " + chunks + " chunks" + (outsideTheScene > 0 ? ", " + outsideTheScene
                + " outside the scene" : "") + (unknownStates > 0 ? ", " + unknownStates + " unknown block states" : "")
                + (skippedChunks > 0 ? ", " + skippedChunks + " chunks skipped" : "");
        }
    }

    /**
     * Places the box's blocks (everything but air) in the scene, without physics. The scene is clear to air before a
     * scenario starts, so air needs no placing.
     */
    public Placed placeBlocks(ScenarioScene scene) {
        NavSnapshot.Manifest m = snapshot().manifest();
        SnapshotBox box = new SnapshotBox(m.boxMin(), m.boxMax());
        int[] placed = {0, 0, 0};
        int chunks = 0;
        int skipped = 0;
        List<String> problems = new ArrayList<>();
        Map<String, BlockData> states = new HashMap<>();
        for (Map.Entry<String, byte[]> file : loaded.files().entrySet()) {
            if (!file.getKey().endsWith(".mca")) {
                continue;
            }
            AnvilRegion.Read read = AnvilRegion.blocks(file.getKey(), file.getValue(), box, (x, y, z, state) -> {
                int sx = x - frame[0];
                int sy = y - frame[1];
                int sz = z - frame[2];
                if (sx < 0 || sy < 0 || sz < 0 || sx >= scene.box().x() || sy >= scene.box().y() || sz >= scene.box().z()) {
                    placed[1]++;
                    return;
                }
                BlockData data = states.computeIfAbsent(state, SnapshotReplay::blockData);
                if (data == null) {
                    placed[2]++;
                    return;
                }
                scene.block(sx, sy, sz).setBlockData(data, false);
                placed[0]++;
            });
            chunks += read.chunks();
            skipped += read.skipped();
            problems.addAll(read.problems());
        }
        return new Placed(placed[0], placed[1], placed[2], chunks, skipped, problems);
    }

    private static BlockData blockData(String state) {
        try {
            return Bukkit.createBlockData(state);
        } catch (IllegalArgumentException e) {
            return null; // a block of a newer or older game version
        }
    }

    // ==================== the route ====================

    /** A real point in the scene's world (real - frame + the scene's origin). */
    public NavPoint inScene(ScenarioScene scene, double[] real) {
        return new NavPoint(scene.world().getName(), scene.worldX(0) + real[0] - frame[0],
            scene.worldY(0) + real[1] - frame[1], scene.worldZ(0) + real[2] - frame[2]);
    }

    /** A scene point as real coordinates (the inverse of {@link #inScene}). */
    public double[] real(ScenarioScene scene, NavPoint p) {
        return new double[] {p.x() - scene.worldX(0) + frame[0], p.y() - scene.worldY(0) + frame[1],
            p.z() - scene.worldZ(0) + frame[2]};
    }

    /** The stored route line in the scene (floor points). */
    public List<NavPoint> line(ScenarioScene scene) {
        List<NavPoint> out = new ArrayList<>();
        for (double[] p : snapshot().route().line()) {
            out.add(inScene(scene, p));
        }
        return out;
    }

    /** The stored line there and back, the loop a scenario's fake road walks. */
    public List<NavPoint> loop(ScenarioScene scene) {
        List<NavPoint> there = line(scene);
        List<NavPoint> loop = new ArrayList<>(there);
        for (int i = there.size() - 2; i >= 0; i--) {
            loop.add(there.get(i));
        }
        return loop;
    }

    /** The route's start and end (floor points) in the scene. */
    public NavPoint from(ScenarioScene scene) {
        return inScene(scene, snapshot().route().from());
    }

    public NavPoint to(ScenarioScene scene) {
        return inScene(scene, snapshot().route().to());
    }

    /** The gate crossings on the stored line, in the scene. */
    public List<RoadPlanner.GateCrossing> gates(ScenarioScene scene) {
        List<RoadPlanner.GateCrossing> out = new ArrayList<>();
        for (NavSnapshot.GateCrossing g : snapshot().route().gates()) {
            out.add(new RoadPlanner.GateCrossing(g.doorId(), inScene(scene, g.at())));
        }
        return out;
    }

    /** The plaza junctions on the stored line, in the scene. */
    public List<RoadPlanner.PlazaCrossing> plazas(ScenarioScene scene) {
        List<RoadPlanner.PlazaCrossing> out = new ArrayList<>();
        for (NavSnapshot.Plaza p : snapshot().route().plazas()) {
            out.add(new RoadPlanner.PlazaCrossing(inScene(scene, p.centre()), p.radius()));
        }
        return out;
    }

    /**
     * The settings as they were at the route's start, as one global layer over the code defaults (the layers they
     * came from are in the snapshot). Values this plugin no longer knows go to {@code rejected}.
     */
    public NavigationSettings settings(List<String> rejected) {
        return settings(rejected, java.util.function.UnaryOperator.identity());
    }

    /** As {@link #settings(List)}, with {@code change} applied to the stored values (another configuration to compare). */
    public NavigationSettings settings(List<String> rejected,
                                       java.util.function.UnaryOperator<NavigationSettings.Overrides> change) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (NavSnapshot.Setting s : snapshot().settings()) {
            if (s.value() != null && !"null".equals(s.value())) {
                raw.put(s.key(), s.value());
            }
        }
        return NavigationSettings.builder()
            .global(change.apply(NavigationSettings.Overrides.parse(raw, "snapshot " + snapshot().manifest().route(),
                rejected)))
            .build();
    }

    // ==================== domains and their rules ====================

    /** The scenario's id for a snapshot region (WorldGuard ids are lower case). */
    public String regionId(ScenarioScene scene, String realId) {
        return scene.uniqueName("snap_" + realId).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Builds every domain's WorldGuard region in the scene's world under {@link #regionId}, with its whole shape moved
     * by the frame, its priority, its parent (when the parent is in the snapshot too) and the entry and exit flags.
     * Returns the ids; {@link #removeRegions} takes them away again.
     */
    public List<String> buildRegions(ScenarioScene scene) {
        return SnapshotRegions.build(this, scene, builtRegions);
    }

    /** Removes the regions {@link #buildRegions} made (a cleanup step). */
    public void removeRegions(ScenarioScene scene) {
        SnapshotRegions.remove(scene, builtRegions);
        builtRegions.clear();
    }

    /**
     * The access rules as the plugin's caches answered them when the snapshot was taken (the scenario's fake API
     * data): a region whose domain had AllowEntry false may not be entered (unless the subject bypasses domain rules
     * or stands in it), and a gate door that was not open blocks (unless the subject is a gate admin). Regions are
     * known by their scenario ids ({@link #regionId}).
     */
    public AccessRules accessRules(ScenarioScene scene) {
        Map<String, NavSnapshot.Domain> byScenarioId = new HashMap<>();
        Map<Integer, Boolean> doorOpen = new HashMap<>();
        for (NavSnapshot.Domain d : snapshot().domains()) {
            byScenarioId.put(regionId(scene, d.regionId()), d);
            for (NavSnapshot.GateDoor door : d.gateDoors()) {
                doorOpen.put(door.doorId(), door.open());
            }
        }
        return rules(byScenarioId, doorOpen);
    }

    /** The rules over domains by region id and doors by id (package-private for the unit test). */
    static AccessRules rules(Map<String, NavSnapshot.Domain> domains, Map<Integer, Boolean> doorOpen) {
        return new AccessRules() {
            @Override
            public Predicate<String> mayEnter(AccessSubject subject) {
                return regionId -> {
                    if (subject.bypassesDomainRules() || subject.currentRegions().contains(regionId)) {
                        return true;
                    }
                    AccessSubject.RegionOverride own = subject.regionOverride(regionId);
                    if (own == AccessSubject.RegionOverride.DENY) {
                        return false;
                    }
                    if (own == AccessSubject.RegionOverride.ALLOW) {
                        return true;
                    }
                    NavSnapshot.Domain d = domains.get(regionId);
                    return d == null || d.allowEntry() == null || d.allowEntry();
                };
            }

            @Override
            public boolean gateBlocks(AccessSubject subject, int doorId) {
                return !subject.gateAdmin() && !doorOpen.getOrDefault(doorId, true);
            }

            @Override
            public Optional<net.knightsandkings.knk.core.gates.target.GateBox> doorBox(int doorId) {
                return Optional.empty();
            }
        };
    }
}
