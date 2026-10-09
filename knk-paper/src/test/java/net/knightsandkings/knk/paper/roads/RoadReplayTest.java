package net.knightsandkings.knk.paper.roads;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadMaterialRole;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.domain.roads.RoadTile;
import net.knightsandkings.knk.core.domain.roads.RoadTileGraph;
import net.knightsandkings.knk.core.roads.build.BuildParameters;
import net.knightsandkings.knk.core.roads.build.DistanceTransform;
import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.build.MaskBuilder;
import net.knightsandkings.knk.core.roads.build.NodeMatcher;
import net.knightsandkings.knk.core.roads.build.PassabilityRules;
import net.knightsandkings.knk.core.roads.build.ProfileSet;
import net.knightsandkings.knk.core.roads.build.RoadMask;
import net.knightsandkings.knk.core.roads.build.SkeletonGraph;
import net.knightsandkings.knk.core.roads.build.SpanGrid;
import net.knightsandkings.knk.core.roads.build.Thinning;
import net.knightsandkings.knk.core.roads.build.TileBuildResult;
import net.knightsandkings.knk.core.roads.build.TileBuilder;
import net.knightsandkings.knk.core.roads.build.TileDiff;
import net.knightsandkings.knk.core.roads.build.TileProposal;
import net.knightsandkings.knk.core.roads.survey.ProposedProfile;

/**
 * KNG-27 offline replay (finding L, 2026-10-04; committed for rev. 6 plan §5.7 decision D7): runs the real
 * {@link TileBuilder} on <b>copies</b> of the dev world's region files with the build inputs exported read-only from
 * the dev DB, so a live build can be reproduced exactly and one input changed at a time (tombstones, a config value,
 * a builder change) - and, for a curated tile, the proposal ({@link TileDiff}) the rebuild would make. Only runs when
 * {@code KNK_REPLAY_DIR} points at a folder with {@code region/} and {@code replay/} inside; see
 * {@code tools/road-replay/README.md}.
 *
 * <p>Run: {@code KNK_REPLAY_DIR=<dir> ./gradlew :knk-paper:test --offline --rerun --tests "*RoadReplayTest*"}
 * (stop the Gradle daemon first so it sees the variable; {@code --rerun} because Gradle does not see changes to
 * {@code control.txt}). Writes {@code replay/out_<file>.txt} and, with {@code map=} in the control file,
 * {@code replay/map.txt}.
 */
@EnabledIfEnvironmentVariable(named = "KNK_REPLAY_DIR", matches = ".+")
class RoadReplayTest {
    static final String DIR = withSlash(System.getenv("KNK_REPLAY_DIR"));

    private static String withSlash(String dir) {
        return dir == null ? "" : dir.endsWith("/") || dir.endsWith("\\") ? dir : dir + "/";
    }

    // ---- NBT / Anvil (only what block states need) ------------------------------------------------

    static Object readTag(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: { byte[] b = new byte[in.readInt()]; in.readFully(b); return b; }
            case 8: return in.readUTF();
            case 9: { int t = in.readByte(); int n = in.readInt(); List<Object> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(readTag(in, t)); return l; }
            case 10: { Map<String, Object> m = new HashMap<>(); while (true) { int t = in.readByte(); if (t == 0) return m; String k = in.readUTF(); m.put(k, readTag(in, t)); } }
            case 11: { int[] a = new int[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readInt(); return a; }
            case 12: { long[] a = new long[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readLong(); return a; }
            default: throw new IOException("tag " + type);
        }
    }

    /** One chunk: per section y (blockY >> 4) the palette and packed data. */
    static final class Chunk {
        final Map<Integer, String[]> palettes = new HashMap<>();
        final Map<Integer, long[]> data = new HashMap<>();

        String at(int lx, int y, int lz) {
            int sy = Math.floorDiv(y, 16);
            String[] pal = palettes.get(sy);
            if (pal == null) return "AIR";
            if (pal.length == 1) return pal[0];
            long[] d = data.get(sy);
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(pal.length - 1));
            int perLong = 64 / bits;
            int i = ((y & 15) * 16 + lz) * 16 + lx;
            long word = d[i / perLong];
            int v = (int) ((word >>> ((i % perLong) * bits)) & ((1L << bits) - 1));
            return v < pal.length ? pal[v] : "AIR";
        }
    }

    private final Map<Long, Chunk> chunks = new HashMap<>();

    @SuppressWarnings("unchecked")
    Chunk chunk(int cx, int cz) throws IOException {
        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        Chunk cached = chunks.get(key);
        if (cached != null) return cached;
        Chunk c = new Chunk();
        File f = new File(DIR + "region/r." + (cx >> 5) + "." + (cz >> 5) + ".mca");
        if (f.exists()) {
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.seek(4L * ((cx & 31) + (cz & 31) * 32));
                int offset = raf.readInt() >>> 8;
                if (offset != 0) {
                    raf.seek(offset * 4096L);
                    int len = raf.readInt();
                    int comp = raf.readByte();
                    byte[] buf = new byte[len - 1];
                    raf.readFully(buf);
                    InputStream raw = new ByteArrayInputStream(buf);
                    InputStream in = comp == 2 ? new InflaterInputStream(raw) : comp == 1 ? new GZIPInputStream(raw) : raw;
                    DataInputStream din = new DataInputStream(new java.io.BufferedInputStream(in));
                    din.readByte();
                    din.readUTF();
                    Map<String, Object> root = (Map<String, Object>) readTag(din, 10);
                    for (Object so : (List<Object>) root.getOrDefault("sections", List.of())) {
                        Map<String, Object> s = (Map<String, Object>) so;
                        Map<String, Object> bs = (Map<String, Object>) s.get("block_states");
                        if (bs == null) continue;
                        int sy = ((Number) s.get("Y")).intValue();
                        List<Object> pal = (List<Object>) bs.get("palette");
                        String[] names = new String[pal.size()];
                        for (int i = 0; i < names.length; i++) {
                            String n = (String) ((Map<String, Object>) pal.get(i)).get("Name");
                            names[i] = n.substring(n.indexOf(':') + 1).toUpperCase(Locale.ROOT);
                        }
                        c.palettes.put(sy, names);
                        if (bs.get("data") != null) c.data.put(sy, (long[]) bs.get("data"));
                    }
                }
            }
        }
        chunks.put(key, c);
        return c;
    }

    // ---- inputs ------------------------------------------------------------------------------------

    record Input(JsonNode json, int tileX, int tileZ, List<ProfileSet.Profile> profiles, List<MaskBuilder.Seed> seeds,
                 List<NodeMatcher.PreviousNode> nodes, List<NodeMatcher.PreviousEdge> edges, Map<Integer, JsonNode> nodeJson) {
    }

    static Input load(String file) throws IOException {
        JsonNode j = new ObjectMapper().readTree(new File(DIR + "replay/" + file));
        List<ProfileSet.Profile> profiles = new ArrayList<>();
        for (JsonNode p : j.get("profiles")) {
            List<ProposedProfile.Material> mats = new ArrayList<>();
            for (JsonNode m : p.get("materials")) {
                mats.add(new ProposedProfile.Material(m.get("material").asText(), RoadMaterialRole.valueOf(m.get("role").asText().toUpperCase(Locale.ROOT)),
                    m.get("ambiguous").asBoolean(), m.get("centreShare").asDouble(), m.get("edgeShare").asDouble(), m.get("samples").asInt()));
            }
            profiles.add(new ProfileSet.Profile(p.get("id").asInt(), p.get("name").asText(), p.get("enabled").asBoolean(),
                p.get("widthMin").asInt(), p.get("widthMax").asInt(), Set.of(), mats));
        }
        LinkedHashSet<MaskBuilder.Seed> seeds = new LinkedHashSet<>();
        for (JsonNode s : j.get("seeds")) seeds.add(new MaskBuilder.Seed(s.get(0).asInt(), s.get(1).asInt(), s.get(2).asInt()));
        List<NodeMatcher.PreviousNode> nodes = new ArrayList<>();
        Map<Integer, JsonNode> nodeJson = new HashMap<>();
        for (JsonNode n : j.get("nodes")) {
            nodes.add(new NodeMatcher.PreviousNode(n.get("id").asInt(), n.get("x").asInt(), n.get("y").asInt(), n.get("z").asInt(),
                RoadNodeKind.fromApiName(n.get("kind").asText()), n.get("locked").asBoolean()));
            nodeJson.put(n.get("id").asInt(), n);
        }
        List<NodeMatcher.PreviousEdge> edges = new ArrayList<>();
        for (JsonNode e : j.get("edges")) {
            edges.add(new NodeMatcher.PreviousEdge(e.get("id").asInt(), e.get("from").asInt(), e.get("to").asInt(), geometry(e.get("geometry"))));
        }
        return new Input(j, j.get("tileX").asInt(), j.get("tileZ").asInt(), profiles, new ArrayList<>(seeds), nodes, edges, nodeJson);
    }

    private static List<int[]> geometry(JsonNode points) {
        List<int[]> g = new ArrayList<>();
        for (JsonNode p : points) g.add(new int[] {p.get(0).asInt(), p.get(1).asInt(), p.get(2).asInt()});
        return g;
    }

    /**
     * The stored tile graph for {@link TileDiff}. Domains, regions and gate doors are left empty on both sides: the
     * replay does not tag them (no WorldGuard, {@link GateCells#NONE}).
     */
    static RoadTileGraph stored(Input in) {
        List<RoadNode> nodes = new ArrayList<>();
        for (JsonNode n : in.json().get("nodes")) {
            nodes.add(new RoadNode(n.get("id").asInt(), n.get("x").asInt(), n.get("y").asInt(), n.get("z").asInt(),
                RoadNodeKind.fromApiName(n.get("kind").asText()), n.path("name").isTextual() ? n.get("name").asText() : null, 0,
                n.get("locked").asBoolean(), n.path("plazaRadius").isInt() ? n.get("plazaRadius").asInt() : 0));
        }
        List<RoadEdge> edges = new ArrayList<>();
        for (JsonNode e : in.json().get("edges")) {
            List<int[]> g = geometry(e.get("geometry"));
            double length = e.path("length").isNumber() ? e.get("length").asDouble() : 0;
            edges.add(new RoadEdge(e.get("id").asInt(), e.get("from").asInt(), e.get("to").asInt(), g, length,
                e.path("avgWidth").asDouble(0), e.path("profileId").isInt() ? OptionalInt.of(e.get("profileId").asInt()) : OptionalInt.empty(),
                OptionalInt.empty(), 1.0, Set.of(), List.of(), List.of(), List.of(),
                RoadEdgeSource.fromApiName(e.path("source").asText("Detected")), false, e.path("confirmed").asBoolean(false)));
        }
        RoadTile tile = new RoadTile(0, "replay", in.tileX(), in.tileZ(), 0, java.time.OffsetDateTime.now(), 0, false, 0,
            nodes.size(), edges.size(), 1, List.of());
        return new RoadTileGraph(tile, nodes, edges);
    }

    CompactSurfaceGrid grid(Input in, MaskBuilder.Region region, Map<String, String> control) throws IOException {
        Set<String> floor = new HashSet<>();
        for (ProfileSet.Profile p : in.profiles()) {
            if (!p.enabled()) continue;
            p.materials().stream().filter(m -> m.role() != RoadMaterialRole.OVERLAY).forEach(m -> floor.add(m.material().toUpperCase(Locale.ROOT)));
        }
        List<String> overlay = Arrays.asList(control.getOrDefault("overlay-materials",
            "SNOW,*_CARPET,*_PRESSURE_PLATE,RAIL,POWERED_RAIL,LEAF_LITTER,PINK_PETALS").split(","));
        PassabilityRules rules = PassabilityRules.of(PassabilityRules::curatedCollidable, overlay);
        Predicate<String> roadFloor = floor::contains;
        CompactSurfaceGrid grid = new CompactSurfaceGrid(rules, roadFloor, GateCells.NONE, -64, 320);
        for (int cx = Math.floorDiv(region.minX(), 16); cx <= Math.floorDiv(region.maxX(), 16); cx++) {
            for (int cz = Math.floorDiv(region.minZ(), 16); cz <= Math.floorDiv(region.maxZ(), 16); cz++) {
                Chunk c = chunk(cx, cz);
                grid.capture(c::at, cx, cz);
            }
        }
        return grid;
    }

    /**
     * The server's builder config. Defaults are the dev server's values of 2026-10-04 (finding L); override them in
     * {@code control.txt} with the config.yml key names ({@code junction-cluster-radius=5} …).
     */
    static BuildParameters liveParams(Map<String, String> c) {
        return BuildParameters.defaults()
            .withTile(512, Integer.parseInt(c.getOrDefault("tile-margin", "32")))
            .withMaxCells(Integer.parseInt(c.getOrDefault("max-cells-per-tile", "250000")))
            .withGraphRules(Integer.parseInt(c.getOrDefault("junction-cluster-radius", "5")), Integer.parseInt(c.getOrDefault("min-spur-length", "15")))
            .withAmbiguousReach(Integer.parseInt(c.getOrDefault("ambiguous-reach", "2")))
            .withPlazaGrowth(Integer.parseInt(c.getOrDefault("plaza-growth", "4")))
            .withLockedNodeReach(Double.parseDouble(c.getOrDefault("locked-node-reach", "8.0")))
            .withAutoPlazas(Boolean.parseBoolean(c.getOrDefault("auto-plazas", "true")));
    }

    static Map<String, String> control() throws IOException {
        return control("control.txt");
    }

    /** A {@code key=value} file in {@code replay/} ({@code #} comments); empty when it is missing. */
    static Map<String, String> control(String name) throws IOException {
        Map<String, String> c = new HashMap<>();
        File f = new File(DIR + "replay/" + name);
        if (f.exists()) {
            for (String line : Files.readAllLines(f.toPath())) {
                int eq = line.indexOf('=');
                if (eq > 0 && !line.startsWith("#")) c.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return c;
    }

    // ---- variants ----------------------------------------------------------------------------------

    record Variant(String label, BuildParameters params, boolean tombstones, boolean locks, boolean anchors, boolean previous) {
    }

    static Map<String, Variant> variants(BuildParameters live) {
        Map<String, Variant> v = new LinkedHashMap<>();
        v.put("live", new Variant("live (as the server would rebuild now)", live, true, true, true, true));
        v.put("notomb", new Variant("no tombstones (locks, anchors, plazas kept)", live, false, true, true, true));
        v.put("raw", new Variant("raw: no tombstones, locks, anchors, plazas, previous graph", live, false, false, false, false));
        v.put("noauto", new Variant("auto-plazas off (designed plazas only)", live.withAutoPlazas(false), true, true, true, true));
        v.put("c3", new Variant("live, junction-cluster-radius 3", live.withGraphRules(3, live.minSpurLength()), true, true, true, true));
        v.put("c1", new Variant("live, junction-cluster-radius 1", live.withGraphRules(1, live.minSpurLength()), true, true, true, true));
        v.put("rawc3", new Variant("raw, cluster radius 3", live.withGraphRules(3, live.minSpurLength()), false, false, false, false));
        return v;
    }

    static TileBuildResult run(Input in, Variant v, CompactSurfaceGrid grid) {
        List<NodeMatcher.PreviousNode> nodes = new ArrayList<>();
        List<SkeletonGraph.Anchor> anchors = new ArrayList<>();
        List<SkeletonGraph.Plaza> plazas = new ArrayList<>();
        for (NodeMatcher.PreviousNode n : in.nodes()) {
            if (n.kind().isTombstone() && !v.tombstones()) continue;
            nodes.add(v.locks() ? n : new NodeMatcher.PreviousNode(n.id(), n.x(), n.y(), n.z(), n.kind(), n.kind().isTombstone()));
            if (n.kind() == RoadNodeKind.ANCHOR && v.anchors()) anchors.add(new SkeletonGraph.Anchor(n.id(), n.x(), n.y(), n.z()));
            JsonNode j = in.nodeJson().get(n.id());
            int radius = j.path("plazaRadius").isInt() ? j.get("plazaRadius").asInt() : 0;
            if (radius > 0 && v.anchors() && (n.kind() == RoadNodeKind.JUNCTION || n.kind() == RoadNodeKind.ANCHOR)) {
                plazas.add(new SkeletonGraph.Plaza(n.id(), n.x(), n.y(), n.z(), radius));
            }
        }
        NodeMatcher.PreviousGraph prev = v.previous() ? new NodeMatcher.PreviousGraph(nodes, in.edges())
            // tombstones still reach the builder through the previous graph
            : new NodeMatcher.PreviousGraph(nodes.stream().filter(n -> n.kind().isTombstone()).toList(), List.of());
        TileBuilder.TileRequest req = new TileBuilder.TileRequest("replay", in.tileX(), in.tileZ(), v.params(), in.seeds(),
            new ProfileSet(in.profiles()), GateCells.NONE, anchors, prev, plazas);
        return new TileBuilder().build(req, grid);
    }

    /** {@code focus=x0,x1,z0,z1} in the control file narrows the node/edge listing; default the whole tile. */
    static int[] focus(Map<String, String> control, MaskBuilder.Region region) {
        if (control.containsKey("focus")) {
            return Arrays.stream(control.get("focus").split(",")).mapToInt(s -> Integer.parseInt(s.trim())).toArray();
        }
        return new int[] {region.minX(), region.maxX(), region.minZ(), region.maxZ()};
    }

    static String describe(Input in, TileBuildResult r, int[] focus) {
        StringBuilder sb = new StringBuilder();
        Map<RoadNodeKind, Integer> kinds = new TreeMap<>();
        for (TileBuildResult.Node n : r.nodes()) kinds.merge(n.kind(), 1, Integer::sum);
        sb.append("  nodes ").append(r.nodes().size()).append(' ').append(kinds).append(", edges ").append(r.edges().size())
            .append(", cells ").append(r.cellCount()).append('\n');
        for (String w : r.warningTexts()) sb.append("  warn: ").append(w).append('\n');
        for (TileBuildResult.Correction c : r.corrections()) {
            sb.append(String.format("  corr: %-15s #%-6d %-6s %s at %d,%d,%d%n", c.kind(), c.nodeId(), c.applied() ? "used" : "STALE",
                c.detail(), c.x(), c.y(), c.z()));
        }
        Map<String, String> label = new HashMap<>();
        for (TileBuildResult.Node n : r.nodes()) {
            String name = n.existingId().isPresent() ? "#" + n.existingId().getAsInt() : n.key();
            JsonNode j = n.existingId().isPresent() ? in.nodeJson().get(n.existingId().getAsInt()) : null;
            if (j != null && j.path("name").isTextual()) name += "(" + j.get("name").asText() + ")";
            label.put(n.key(), name);
            if (inFocus(focus, n.x(), n.z())) {
                sb.append(String.format("  N %-28s %-9s %d,%d,%d%n", name, n.kind(), n.x(), n.y(), n.z()));
            }
        }
        for (TileBuildResult.Edge e : r.edges()) {
            boolean shown = false;
            for (int[] p : e.geometry()) shown |= inFocus(focus, p[0], p[2]);
            if (!shown) continue;
            StringBuilder g = new StringBuilder();
            for (int[] p : e.geometry()) g.append('[').append(p[0]).append(',').append(p[1]).append(',').append(p[2]).append(']');
            sb.append(String.format("  E %-8s %s -> %s  %.1f m  %s%n", e.existingId().isPresent() ? "#" + e.existingId().getAsInt() : "new",
                label.get(e.fromKey()), label.get(e.toKey()), e.length(), g));
        }
        return sb.toString();
    }

    private static boolean inFocus(int[] focus, int x, int z) {
        return x >= focus[0] && x <= focus[1] && z >= focus[2] && z <= focus[3];
    }

    // ---- tests -------------------------------------------------------------------------------------

    /**
     * {@code files=tile_2_-2.json,…} and {@code variants=live,notomb,…} from the control file. Each variant's build is
     * listed; the {@code live} variant is also compared with the stored graph, as the proposal a rebuild of the curated
     * tile would make (plan §5.7).
     */
    @Test
    void replay() throws IOException {
        Map<String, String> control = control();
        for (String file : control.getOrDefault("files", "tile_2_-2.json").split(",")) {
            if (new File(DIR + "replay/" + file.trim()).exists()) {
                replayTile(file.trim(), control);
            }
        }
    }

    void replayTile(String file, Map<String, String> control) throws IOException {
        Input in = load(file);
        MaskBuilder.Region region = MaskBuilder.Region.tile(in.tileX(), in.tileZ(), 512).grow(32);
        int[] focus = focus(control, region);
        long t0 = System.currentTimeMillis();
        CompactSurfaceGrid grid = grid(in, region, control);
        StringBuilder out = new StringBuilder("grid captured in " + (System.currentTimeMillis() - t0) + " ms\n");
        BuildParameters live = liveParams(control);
        Map<String, Variant> variants = variants(live);
        for (String key : control.getOrDefault("variants", "live").split(",")) {
            Variant v = variants.get(key.trim());
            if (v == null) {
                out.append("\n== ").append(key).append(": unknown variant, one of ").append(variants.keySet()).append('\n');
                continue;
            }
            long t = System.currentTimeMillis();
            TileBuildResult r = run(in, v, grid);
            out.append("\n== ").append(key).append(": ").append(v.label()).append(" (").append(System.currentTimeMillis() - t).append(" ms)\n");
            out.append(describe(in, r, focus));
            if ("live".equals(key.trim())) {
                out.append("\n== proposal: the live build against the stored graph (domains, regions, gate doors not compared)\n");
                List<TileProposal.Item> items = new TileDiff(TileDiff.Settings.of(live)).compute(stored(in), r);
                if (items.isEmpty()) out.append("  no changes\n");
                for (TileProposal.Item item : items) {
                    int[] f = item.focus();
                    out.append("  ").append(item.describe()).append("  at ").append(f[0]).append(',').append(f[1]).append(',').append(f[2]).append('\n');
                }
            }
        }
        Files.writeString(Path.of(DIR + "replay/out_" + file.replace(".json", "") + ".txt"), out.toString(), StandardCharsets.UTF_8);
    }

    /**
     * {@code map=x0,x1,z0,z1,y0,y1} in the control file: a top view of the mask and skeleton in that box, then the
     * skeleton chains (lettered) with the tile's designed plazas, written to {@code replay/map.txt}.
     */
    @Test
    void map() throws IOException {
        Map<String, String> control = control();
        if (!control.containsKey("map")) {
            return;
        }
        Input in = load(control.getOrDefault("files", "tile_2_-2.json").split(",")[0].trim());
        MaskBuilder.Region region = MaskBuilder.Region.tile(in.tileX(), in.tileZ(), 512).grow(32);
        CompactSurfaceGrid grid = grid(in, region, control);
        int[] box = Arrays.stream(control.get("map").split(",")).mapToInt(s -> Integer.parseInt(s.trim())).toArray();
        int x0 = box[0], x1 = box[1], z0 = box[2], z1 = box[3], y0 = box[4], y1 = box[5];
        BuildParameters params = liveParams(control);
        SpanGrid sg = new SpanGrid(grid, new ProfileSet(in.profiles()), GateCells.NONE);
        RoadMask mask = new MaskBuilder(sg, params).build(in.seeds(), region).mask();
        int[] dt = DistanceTransform.compute(mask);
        boolean[] skel = Thinning.thin(mask);
        StringBuilder sb = new StringBuilder("mask/skeleton y " + y0 + ".." + y1 + "; '#' skeleton, digit = road width (2dt-1, 9+ = '+'), '.' none\n      ");
        for (int x = x0; x <= x1; x++) sb.append(x % 10 == 0 ? (char) ('0' + Math.floorMod(x / 10, 10)) : ' ');
        sb.append('\n');
        for (int z = z0; z <= z1; z++) {
            sb.append(String.format("%5d ", z));
            for (int x = x0; x <= x1; x++) {
                char c = '.';
                for (int y = y1; y >= y0; y--) {
                    int i = mask.indexOf(x, y, z);
                    if (i == RoadMask.NONE) continue;
                    if (skel[i]) { c = '#'; break; }
                    int w = DistanceTransform.width(dt[i]);
                    c = w > 9 ? '+' : (char) ('0' + w);
                    break;
                }
                sb.append(c);
            }
            sb.append('\n');
        }
        List<SkeletonGraph.Anchor> anchors = new ArrayList<>();
        List<SkeletonGraph.Pruned> pruned = new ArrayList<>();
        List<SkeletonGraph.Pruned> prunedEdges = new ArrayList<>();
        List<SkeletonGraph.Plaza> plazas = new ArrayList<>();
        for (NodeMatcher.PreviousNode n : in.nodes()) {
            if (n.kind() == RoadNodeKind.ANCHOR) anchors.add(new SkeletonGraph.Anchor(n.id(), n.x(), n.y(), n.z()));
            if (n.kind() == RoadNodeKind.PRUNED) pruned.add(new SkeletonGraph.Pruned(n.id(), n.x(), n.y(), n.z()));
            if (n.kind() == RoadNodeKind.PRUNED_EDGE) prunedEdges.add(new SkeletonGraph.Pruned(n.id(), n.x(), n.y(), n.z()));
            JsonNode j = in.nodeJson().get(n.id());
            if (j.path("plazaRadius").isInt() && (n.kind() == RoadNodeKind.JUNCTION || n.kind() == RoadNodeKind.ANCHOR)) {
                plazas.add(new SkeletonGraph.Plaza(n.id(), n.x(), n.y(), n.z(), j.get("plazaRadius").asInt()));
            }
        }
        SkeletonGraph.Result g = new SkeletonGraph(mask, Thinning.thin(mask), dt, params, new ProfileSet(in.profiles()),
            MaskBuilder.Region.tile(in.tileX(), in.tileZ(), 512)).extract(anchors, pruned, prunedEdges, plazas);
        char[][] map = new char[z1 - z0 + 1][x1 - x0 + 1];
        for (char[] row : map) Arrays.fill(row, '.');
        for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) for (int y = y1; y >= y0; y--) {
            if (mask.indexOf(x, y, z) != RoadMask.NONE) { map[z - z0][x - x0] = ','; break; }
        }
        StringBuilder legend = new StringBuilder();
        int letter = 0;
        for (SkeletonGraph.Chain ch : g.chains()) {
            boolean shown = false;
            char c = (char) (letter < 26 ? 'a' + letter : 'A' + letter - 26);
            for (int s : ch.spans()) {
                int x = mask.x(s), z = mask.z(s), y = mask.y(s);
                if (x < x0 || x > x1 || z < z0 || z > z1 || y < y0 || y > y1) continue;
                map[z - z0][x - x0] = c;
                shown = true;
            }
            if (shown) {
                SkeletonGraph.Node a = g.nodes().get(ch.from()), b = g.nodes().get(ch.to());
                legend.append(c).append(": ").append(a.kind()).append(a.anchorId().isPresent() ? "#" + a.anchorId().getAsInt() : "")
                    .append('@').append(a.x()).append(',').append(a.y()).append(',').append(a.z()).append(" -> ").append(b.kind())
                    .append(b.anchorId().isPresent() ? "#" + b.anchorId().getAsInt() : "").append('@').append(b.x()).append(',').append(b.y())
                    .append(',').append(b.z()).append(" spans ").append(ch.spans().length).append('\n');
                letter++;
            }
        }
        for (SkeletonGraph.Node n : g.nodes()) {
            if (n.x() >= x0 && n.x() <= x1 && n.z() >= z0 && n.z() <= z1 && n.y() >= y0 && n.y() <= y1) map[n.z() - z0][n.x() - x0] = '@';
        }
        sb.append("\nchains (the tile's designed plazas); '@' node, ',' mask\n");
        for (int z = z0; z <= z1; z++) sb.append(String.format("%5d ", z)).append(new String(map[z - z0])).append('\n');
        sb.append(legend);
        Files.writeString(Path.of(DIR + "replay/map.txt"), sb.toString(), StandardCharsets.UTF_8);
    }
}
