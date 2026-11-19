package net.knightsandkings.knk.paper.navigation;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.npc.nav.snapshot.NavSnapshot;
import net.knightsandkings.knk.core.npc.nav.snapshot.NavSnapshotZip;
import net.knightsandkings.knk.core.npc.nav.snapshot.SnapshotBox;
import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.build.PassabilityRules;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.walk.MovementProfile;
import net.knightsandkings.knk.core.util.BlockKey;
import net.knightsandkings.knk.paper.navigation.walk.CapturedWalkTerrain;
import net.knightsandkings.knk.paper.navigation.walk.WalkBox;
import net.knightsandkings.knk.paper.navigation.walk.WalkChunk;
import net.knightsandkings.knk.paper.navigation.walk.WalkChunkExtractor;
import net.knightsandkings.knk.paper.roads.SpanExtractor;
import net.knightsandkings.knk.paper.scenario.AnvilRegion;

/**
 * One of the developer's dev-world NPC navigation snapshots ({@code scenario-snapshots/*.zip}, taken 2026-11-17) as a
 * navigation test fixture: its blocks (read back into walk captures as the server captures chunks, as
 * {@code WalkDevWorldFixesTest} does) and its road graph ({@code roads.json}: the nodes in the box and the edges that pass
 * through it; a node outside the box is put at its edge's end).
 */
final class DevWorldSnapshot {

    static final String WORLD = "world_KNK-DEV";

    final Map<Long, String> states;
    final RoadNetworkSnapshot network;

    private DevWorldSnapshot(Map<Long, String> states, RoadNetworkSnapshot network) {
        this.states = states;
        this.network = network;
    }

    static DevWorldSnapshot load(String zip) throws Exception {
        NavSnapshotZip.Loaded loaded;
        try (InputStream in = DevWorldSnapshot.class.getResourceAsStream(zip)) {
            loaded = NavSnapshotZip.read(in);
        }
        var m = loaded.snapshot().manifest();
        SnapshotBox box = new SnapshotBox(m.boxMin(), m.boxMax());
        Map<Long, String> states = new HashMap<>();
        for (Map.Entry<String, byte[]> file : loaded.files().entrySet()) {
            AnvilRegion.blocks(file.getKey(), file.getValue(), box,
                (x, y, z, state) -> states.put(BlockKey.pack(x, y, z), state));
        }
        return new DevWorldSnapshot(states, network(loaded.snapshot()));
    }

    private static RoadNetworkSnapshot network(NavSnapshot snapshot) {
        RoadNetworkSnapshot.Builder b = RoadNetworkSnapshot.builder(WORLD);
        Set<Integer> nodes = new HashSet<>();
        for (NavSnapshot.Node n : snapshot.roads().nodes()) {
            b.addNode(new RoadNode(n.id(), n.x(), n.y(), n.z(), RoadNodeKind.valueOf(n.kind()), n.name(), 0, false,
                n.plazaRadius()));
            nodes.add(n.id());
        }
        for (NavSnapshot.Edge e : snapshot.roads().edges()) {
            List<int[]> g = e.geometry();
            if (nodes.add(e.fromNode())) {
                b.addNode(new RoadNode(e.fromNode(), g.get(0)[0], g.get(0)[1], g.get(0)[2], RoadNodeKind.ENDPOINT, null, 0));
            }
            if (nodes.add(e.toNode())) {
                int[] p = g.get(g.size() - 1);
                b.addNode(new RoadNode(e.toNode(), p[0], p[1], p[2], RoadNodeKind.ENDPOINT, null, 0));
            }
            b.addEdge(new RoadEdge(e.id(), e.fromNode(), e.toNode(), g, e.length(), e.width(), OptionalInt.empty(),
                OptionalInt.empty(), 1.0, Set.of(), List.of(), List.of(), List.of(), RoadEdgeSource.DETECTED, false));
        }
        return b.build();
    }

    private static String material(String state) {
        if (state == null) {
            return "AIR";
        }
        String s = state.startsWith("minecraft:") ? state.substring("minecraft:".length()) : state;
        int bracket = s.indexOf('[');
        return (bracket < 0 ? s : s.substring(0, bracket)).toUpperCase(Locale.ROOT);
    }

    /** The walk capture of {@code box}, open trapdoors read from the block states (#771 item 2). */
    CapturedWalkTerrain capture(WalkBox box) {
        WalkChunkExtractor extractor = new WalkChunkExtractor(PassabilityRules.defaults(),
            MovementProfile.PLAYER.climbables(), GateCells.NONE, -64, 320);
        List<WalkChunk> chunks = new ArrayList<>();
        for (int cx = box.minChunkX(); cx <= box.maxChunkX(); cx++) {
            for (int cz = box.minChunkZ(); cz <= box.maxChunkZ(); cz++) {
                int bx = cx << 4;
                int bz = cz << 4;
                chunks.add(extractor.extract(new SpanExtractor.BlockSource() {
                    @Override
                    public String materialAt(int lx, int y, int lz) {
                        return material(states.get(BlockKey.pack(bx + lx, y, bz + lz)));
                    }

                    @Override
                    public boolean isOpen(int lx, int y, int lz) {
                        String state = states.get(BlockKey.pack(bx + lx, y, bz + lz));
                        return state != null && state.contains("open=true");
                    }
                }, cx, cz, box.minSection(), box.maxSection(), s -> false, 0L));
            }
        }
        return new CapturedWalkTerrain(chunks, GateCells.NONE, -64, 320);
    }
}
