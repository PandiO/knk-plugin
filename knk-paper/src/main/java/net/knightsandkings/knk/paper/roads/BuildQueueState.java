package net.knightsandkings.knk.paper.roads;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.knightsandkings.knk.core.domain.roads.RoadTile;

/**
 * The pure state of {@code RoadBuildQueue} (DESIGN §9 "resumable background queue"): the ordered tiles
 * still to build, when the queue was started, who asked, and a label. Persisted to
 * {@code roads/build-queue.json} on every change so a restart resumes it; on resume, tiles whose
 * {@code BuiltAt} in the API is newer than the queue start are skipped (their build finished before the
 * restart - progress lives in the API, not here).
 */
public final class BuildQueueState {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Deque<TileKey> pending = new ArrayDeque<>();
    private OffsetDateTime startedAt;
    private UUID requester;
    private String label;
    private int done;
    private int total;

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    public int pendingCount() {
        return pending.size();
    }

    public int doneCount() {
        return done;
    }

    public int totalCount() {
        return total;
    }

    public Optional<UUID> requester() {
        return Optional.ofNullable(requester);
    }

    public String label() {
        return label == null ? "" : label;
    }

    public OffsetDateTime startedAt() {
        return startedAt;
    }

    public List<TileKey> pending() {
        return new ArrayList<>(pending);
    }

    /** Appends tiles (duplicates of pending ones dropped); a fresh queue records start, requester and label. */
    public int enqueue(Collection<TileKey> tiles, UUID requester, String label, OffsetDateTime now) {
        int added = 0;
        if (pending.isEmpty()) {
            this.startedAt = now;
            this.requester = requester;
            this.label = label;
            this.done = 0;
            this.total = 0;
        }
        for (TileKey tile : tiles) {
            if (!pending.contains(tile)) {
                pending.addLast(tile);
                added++;
            }
        }
        total += added;
        return added;
    }

    /** The next tile to build, removed from the queue. */
    public Optional<TileKey> next() {
        return Optional.ofNullable(pending.pollFirst());
    }

    public void completed() {
        done++;
    }

    public void clear() {
        pending.clear();
        startedAt = null;
        requester = null;
        label = null;
        done = 0;
        total = 0;
    }

    /**
     * Resume rule: drops every pending tile whose API row says it was built after the queue started.
     * @param builtAt the API's BuiltAt per tile (absent = never built)
     * @return how many tiles were skipped
     */
    public int skipBuiltSince(Function<TileKey, Optional<OffsetDateTime>> builtAt) {
        if (startedAt == null) {
            return 0;
        }
        int skipped = 0;
        for (TileKey tile : new ArrayList<>(pending)) {
            Optional<OffsetDateTime> built = builtAt.apply(tile);
            if (built.isPresent() && built.get().isAfter(startedAt)) {
                pending.remove(tile);
                skipped++;
                done++;
            }
        }
        return skipped;
    }

    /** As {@link #skipBuiltSince(Function)} from the API's tile list. */
    public int skipBuiltSince(Collection<RoadTile> tiles) {
        Map<TileKey, OffsetDateTime> byKey = new java.util.HashMap<>();
        for (RoadTile tile : tiles) {
            if (tile.builtAt() != null) {
                byKey.put(TileKey.of(tile), tile.builtAt());
            }
        }
        return skipBuiltSince(key -> Optional.ofNullable(byKey.get(key)));
    }

    // ===== persistence =====

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("startedAt", startedAt == null ? null : startedAt.toString());
        root.addProperty("requester", requester == null ? null : requester.toString());
        root.addProperty("label", label);
        root.addProperty("done", done);
        root.addProperty("total", total);
        JsonArray tiles = new JsonArray();
        for (TileKey tile : pending) {
            JsonObject o = new JsonObject();
            o.addProperty("world", tile.world());
            o.addProperty("x", tile.tileX());
            o.addProperty("z", tile.tileZ());
            tiles.add(o);
        }
        root.add("pending", tiles);
        return GSON.toJson(root);
    }

    public static BuildQueueState fromJson(String json) {
        BuildQueueState state = new BuildQueueState();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.has("startedAt") && !root.get("startedAt").isJsonNull()) {
            state.startedAt = OffsetDateTime.parse(root.get("startedAt").getAsString());
        }
        if (root.has("requester") && !root.get("requester").isJsonNull()) {
            state.requester = UUID.fromString(root.get("requester").getAsString());
        }
        if (root.has("label") && !root.get("label").isJsonNull()) {
            state.label = root.get("label").getAsString();
        }
        state.done = root.has("done") ? root.get("done").getAsInt() : 0;
        state.total = root.has("total") ? root.get("total").getAsInt() : 0;
        for (JsonElement el : root.getAsJsonArray("pending")) {
            JsonObject o = el.getAsJsonObject();
            state.pending.addLast(new TileKey(o.get("world").getAsString(), o.get("x").getAsInt(), o.get("z").getAsInt()));
        }
        return state;
    }

    public void save(Path file) throws IOException {
        if (pending.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, toJson(), StandardCharsets.UTF_8);
    }

    public static Optional<BuildQueueState> load(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(fromJson(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
