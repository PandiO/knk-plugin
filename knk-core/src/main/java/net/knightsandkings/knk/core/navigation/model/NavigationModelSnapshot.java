package net.knightsandkings.knk.core.navigation.model;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import net.knightsandkings.knk.core.navigation.compass.CompassItemRule;
import net.knightsandkings.knk.core.navigation.compass.CompassSettings;

/**
 * The navigation model on disk (destination engine DESIGN § 7.2, E1): {@code <data folder>/offline/navigation-model.json},
 * schema-versioned, written atomically (a temporary file moved over the old one, like {@code OfflineSecurityStore}).
 *
 * <p>Schema 1 holds {@code asOf}, the domains (with their place, road-access flag and region id: the road-access
 * answers, E19), the Locations, the compass settings and a {@code landmarks} section, empty until off-road phase 5.1
 * (#708, E16). No player data, so there is no erasure duty.
 *
 * <p>Reading never throws: a missing file, a corrupt one and one of a newer schema are answered as such, so the
 * caller logs it and goes on without a model (§ 7.3). Call {@link #write} off the main thread.
 */
public final class NavigationModelSnapshot {

    /** The schema this plugin writes and reads. A newer file is ignored, never fatal. */
    public static final int SCHEMA = 1;

    /** The file under the plugin's data folder. */
    public static final String RELATIVE_PATH = "offline/navigation-model.json";

    /** What {@link #read} found. */
    public record Loaded(Status status, NavigationModel model, String detail) {
        public enum Status {
            /** The model is in {@code model}. */
            LOADED,
            /** No file yet. */
            MISSING,
            /** The file could not be read or parsed; {@code detail} says why. */
            CORRUPT,
            /** The file has a schema newer than {@link #SCHEMA}; {@code detail} names it. */
            NEWER_SCHEMA
        }

        public boolean loaded() {
            return status == Status.LOADED;
        }
    }

    // ---- file format (strings for instants and enums so it doesn't depend on Jackson modules) ----

    record PointFile(Integer id, String name, double x, double y, double z, float yaw, float pitch, String world) {
    }

    record DomainFile(int id, String name, String domainType, String navigationDefault, String roadAccess,
                      String wgRegionId, String world, boolean placeKnown, PointFile spawn) {
    }

    record CompassFile(boolean itemEnabled, String material, String displayName, String nameMatch,
                       boolean siegeEnabled, boolean generalEnabled, List<String> generalTargets) {
    }

    record LandmarkFile(int id, String name, String world, double x, double y, double z, String extent,
                        String regionId, Double radius, Integer domainId, String domainName, boolean navigationEnabled,
                        Double preference) {
    }

    record SnapshotFile(int schema, String asOf, List<DomainFile> domains, List<PointFile> locations,
                        CompassFile compass, List<LandmarkFile> landmarks) {
    }

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(SerializationFeature.INDENT_OUTPUT, true);

    public NavigationModelSnapshot(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public Path file() {
        return file;
    }

    // ==================== reading ====================

    /** The model in the file, or why there is none. Never throws. */
    public Loaded read() {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return new Loaded(Loaded.Status.MISSING, null, null);
        } catch (IOException | RuntimeException e) {
            return new Loaded(Loaded.Status.CORRUPT, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        try {
            JsonNode root = mapper.readTree(bytes);
            if (root == null || !root.isObject()) {
                return new Loaded(Loaded.Status.CORRUPT, null, "not a JSON object");
            }
            JsonNode schema = root.get("schema");
            if (schema == null || !schema.canConvertToInt()) {
                return new Loaded(Loaded.Status.CORRUPT, null, "no schema number");
            }
            if (schema.intValue() > SCHEMA) {
                return new Loaded(Loaded.Status.NEWER_SCHEMA, null, "schema " + schema.intValue() + " (this plugin reads "
                    + SCHEMA + ")");
            }
            if (schema.intValue() < 1) {
                return new Loaded(Loaded.Status.CORRUPT, null, "schema " + schema.intValue());
            }
            SnapshotFile stored = mapper.treeToValue(root, SnapshotFile.class);
            return new Loaded(Loaded.Status.LOADED, toModel(stored), null);
        } catch (IOException | RuntimeException e) {
            return new Loaded(Loaded.Status.CORRUPT, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ==================== writing ====================

    /** Writes {@code model} atomically. Off the main thread. */
    public void write(NavigationModel model) throws IOException {
        Objects.requireNonNull(model, "model");
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, mapper.writeValueAsBytes(toFile(model)));
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The JSON text {@link #write} would write (tests, diagnostics). */
    public String toJson(NavigationModel model) throws IOException {
        return mapper.writeValueAsString(toFile(model));
    }

    // ==================== mapping ====================

    static SnapshotFile toFile(NavigationModel model) {
        List<DomainFile> domains = model.domains().stream()
            .map(d -> new DomainFile(d.id(), d.name(), d.domainType(), d.navigationDefault(), d.roadAccess(), d.wgRegionId(),
                d.world(), d.placeKnown(), point(d.spawn())))
            .toList();
        List<PointFile> locations = model.locations().stream().map(NavigationModelSnapshot::point).toList();
        CompassSettings c = model.compass();
        CompassFile compass = new CompassFile(c.item().enabled(), c.item().material(), c.item().displayName(),
            c.item().nameMatch().name(), c.siegeEnabled(), c.generalEnabled(), c.generalTargets());
        List<LandmarkFile> landmarks = model.landmarks().stream()
            .map(l -> new LandmarkFile(l.id(), l.name(), l.world(), l.x(), l.y(), l.z(), l.extent().name(), l.regionId(),
                l.radius(), l.domainId(), l.domainName(), l.navigationEnabled(), l.preference()))
            .toList();
        return new SnapshotFile(SCHEMA, model.asOf().toString(), domains, locations, compass, landmarks);
    }

    static NavigationModel toModel(SnapshotFile stored) {
        if (stored.asOf() == null) {
            throw new IllegalArgumentException("no asOf");
        }
        Instant asOf = Instant.parse(stored.asOf());
        List<DomainPlace> domains = new ArrayList<>();
        for (DomainFile d : stored.domains() == null ? List.<DomainFile>of() : stored.domains()) {
            if (d == null || d.name() == null || d.domainType() == null) {
                continue;
            }
            ModelPoint spawn = d.placeKnown() ? point(d.spawn()) : null;
            domains.add(new DomainPlace(d.id(), d.name(), d.domainType(), d.navigationDefault(), d.roadAccess(),
                d.wgRegionId(), d.world(), spawn, d.placeKnown()));
        }
        List<ModelPoint> locations = new ArrayList<>();
        for (PointFile p : stored.locations() == null ? List.<PointFile>of() : stored.locations()) {
            ModelPoint point = point(p);
            if (point != null) {
                locations.add(point);
            }
        }
        CompassFile c = stored.compass();
        CompassSettings compass = c == null ? null : new CompassSettings(
            new CompassItemRule(c.itemEnabled(), c.material(), c.displayName(), CompassItemRule.NameMatch.parse(c.nameMatch())),
            c.siegeEnabled(), c.generalEnabled(), c.generalTargets());
        List<Landmark> landmarks = new ArrayList<>();
        for (LandmarkFile l : stored.landmarks() == null ? List.<LandmarkFile>of() : stored.landmarks()) {
            if (l != null && l.name() != null && l.world() != null) {
                landmarks.add(new Landmark(l.id(), l.name(), l.world(), l.x(), l.y(), l.z(), Landmark.Extent.parse(l.extent()),
                    l.regionId(), l.radius(), l.domainId(), l.domainName(), l.navigationEnabled(),
                    Landmark.preference(l.preference())));
            }
        }
        return new NavigationModel(asOf, domains, locations, compass, landmarks);
    }

    private static PointFile point(ModelPoint p) {
        return p == null ? null : new PointFile(p.id(), p.name(), p.x(), p.y(), p.z(), p.yaw(), p.pitch(), p.world());
    }

    private static ModelPoint point(PointFile p) {
        if (p == null || p.world() == null || p.world().isBlank()
            || !Double.isFinite(p.x()) || !Double.isFinite(p.y()) || !Double.isFinite(p.z())) {
            return null;
        }
        return new ModelPoint(p.id(), p.name(), p.x(), p.y(), p.z(), p.yaw(), p.pitch(), p.world());
    }
}
