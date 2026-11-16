package net.knightsandkings.knk.core.npc.nav.snapshot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * The snapshot zip (link B1, PandiO/knk-workspace#670): {@code manifest.json}, {@code route.json}, {@code roads.json},
 * {@code domains.json}, {@code settings.json}, a {@code README.txt} that says what each holds, and the region files
 * under {@code world/} with their path inside the world folder (so the 26.x layout is kept as it was found).
 */
public final class NavSnapshotZip {

    public static final String MANIFEST = "manifest.json";
    public static final String ROUTE = "route.json";
    public static final String ROADS = "roads.json";
    public static final String DOMAINS = "domains.json";
    public static final String SETTINGS = "settings.json";
    public static final String README = "README.txt";
    /** The folder in the zip that holds the region files. */
    public static final String WORLD = "world/";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private NavSnapshotZip() {
    }

    /** A read snapshot and the other files of its zip (region files by their path in the zip). */
    public record Loaded(NavSnapshot snapshot, Map<String, byte[]> files) {
        /** The region file named {@code r.<x>.<z>.mca}, wherever it is under {@link #WORLD}, or null. */
        public byte[] regionFile(String name) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                if (e.getKey().startsWith(WORLD) && (e.getKey().endsWith("/" + name))) {
                    return e.getValue();
                }
            }
            return null;
        }
    }

    /**
     * Writes the zip.
     *
     * @param regionFiles path in the zip (under {@link #WORLD}) to the file on disk
     */
    public static void write(NavSnapshot snapshot, Map<String, Path> regionFiles, OutputStream out) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            text(zip, README, readme(snapshot));
            text(zip, MANIFEST, GSON.toJson(snapshot.manifest()));
            text(zip, ROUTE, GSON.toJson(snapshot.route()));
            text(zip, ROADS, GSON.toJson(snapshot.roads()));
            text(zip, DOMAINS, GSON.toJson(snapshot.domains()));
            text(zip, SETTINGS, GSON.toJson(snapshot.settings()));
            for (Map.Entry<String, Path> file : regionFiles.entrySet()) {
                if (!file.getKey().startsWith(WORLD)) {
                    throw new IllegalArgumentException("region files go under " + WORLD + ": " + file.getKey());
                }
                zip.putNextEntry(new ZipEntry(file.getKey()));
                Files.copy(file.getValue(), zip);
                zip.closeEntry();
            }
        }
    }

    /** Reads a zip written by {@link #write}. */
    public static Loaded read(InputStream in) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (entry.getName().contains("..")) {
                    throw new IOException("unsafe path in the snapshot zip: " + entry.getName());
                }
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                zip.transferTo(bytes);
                entries.put(entry.getName(), bytes.toByteArray());
            }
        }
        NavSnapshot.Manifest manifest = json(entries, MANIFEST, NavSnapshot.Manifest.class);
        if (manifest.format() != NavSnapshot.FORMAT) {
            throw new IOException("snapshot format " + manifest.format() + ", this plugin reads " + NavSnapshot.FORMAT);
        }
        NavSnapshot snapshot = new NavSnapshot(manifest,
            json(entries, ROUTE, NavSnapshot.RouteLine.class),
            json(entries, ROADS, NavSnapshot.Roads.class),
            GSON.fromJson(string(entries, DOMAINS), new TypeToken<List<NavSnapshot.Domain>>() { }.getType()),
            GSON.fromJson(string(entries, SETTINGS), new TypeToken<List<NavSnapshot.Setting>>() { }.getType()));
        Map<String, byte[]> files = new LinkedHashMap<>();
        entries.forEach((name, bytes) -> {
            if (name.startsWith(WORLD)) {
                files.put(name, bytes);
            }
        });
        return new Loaded(snapshot, files);
    }

    /** Reads a zip from a file. */
    public static Loaded read(Path zip) throws IOException {
        try (InputStream in = Files.newInputStream(zip)) {
            return read(in);
        }
    }

    private static <T> T json(Map<String, byte[]> entries, String name, Class<T> type) throws IOException {
        T value = GSON.fromJson(string(entries, name), type);
        if (value == null) {
            throw new IOException(name + " is empty");
        }
        return value;
    }

    private static String string(Map<String, byte[]> entries, String name) throws IOException {
        byte[] bytes = entries.get(name);
        if (bytes == null) {
            throw new IOException("the snapshot zip has no " + name);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void text(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    static String readme(NavSnapshot snapshot) {
        NavSnapshot.Manifest m = snapshot.manifest();
        return String.join("\n",
            "K&K NPC navigation snapshot (PandiO/knk-workspace#670), format " + m.format(),
            "Route " + m.route() + " in world " + m.world() + ", box " + m.boxMin()[0] + " " + m.boxMin()[1] + " "
                + m.boxMin()[2] + " to " + m.boxMax()[0] + " " + m.boxMax()[1] + " " + m.boxMax()[2] + ", taken "
                + m.takenAt() + " by plugin " + m.pluginVersion(),
            "",
            MANIFEST + "  the box, world, time, plugin version, counts and notes",
            ROUTE + "     the road line the NPC navigation plans for the route (floor points), its stretches,",
            "               gate crossings and plaza junctions",
            ROADS + "     the road graph's nodes in the box and the edges that pass through it (whole)",
            DOMAINS + "   every Town, District, Structure and gate whose WorldGuard region touches the box: whole",
            "               shape, parent, priority, knk-allow-entry / knk-allow-exit, the cached AllowEntry /",
            "               AllowExit, and a gate's doors with open or closed",
            SETTINGS + "  the NPC navigation settings at the route's start and the layer each came from",
            WORLD + "         the region files (.mca) the box touches, at their path in the world folder, after a save",
            "",
            "Replay: knk-plugin's scenario helper SnapshotReplay (docs/guides/developer/plugin-scenarios.md).",
            "");
    }
}
