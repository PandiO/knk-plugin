package net.knightsandkings.knk.core.worlds;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Loads the configured worlds (KNG-114, hub DESIGN §16 decision 1). Runs once, on the main thread, at the start of the
 * plugin's enable - before gates, region repair and admission start - so everything after it sees every world.
 * It never deletes, renames or regenerates a world, and only creates one whose folder does not exist and whose entry says
 * {@code create: true}.
 */
public final class WorldLoader {

    /** The server side: Bukkit's worlds and the world container. */
    public interface Host {
        boolean isLoaded(String name);

        /**
         * Whether the world exists on disk: a world folder of that name in the world container ({@code <name>/level.dat},
         * the pre-26.1 layout) or, since Minecraft 26.1, its folder under the main world's {@code dimensions/}.
         */
        boolean folderExists(String name);

        /** Loads (or, for a new folder, creates) the world; throws when the server refuses or fails. */
        void load(WorldSpec spec) throws Exception;
    }

    private final Host host;

    public WorldLoader(Host host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    public WorldReadiness loadAll(List<WorldSpec> specs) {
        List<WorldReadiness.Status> statuses = new ArrayList<>();
        for (WorldSpec spec : specs) {
            statuses.add(loadOne(spec));
        }
        return new WorldReadiness(statuses);
    }

    private WorldReadiness.Status loadOne(WorldSpec spec) {
        if (host.isLoaded(spec.name())) {
            return new WorldReadiness.Status(spec, WorldReadiness.State.ALREADY_LOADED, null);
        }
        boolean exists = host.folderExists(spec.name());
        if (!exists && !spec.create()) {
            return new WorldReadiness.Status(spec, WorldReadiness.State.MISSING,
                    "no world folder '" + spec.name() + "' and create is off");
        }
        try {
            host.load(spec);
        } catch (Exception | LinkageError e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new WorldReadiness.Status(spec, WorldReadiness.State.FAILED, message);
        }
        if (!host.isLoaded(spec.name())) {
            return new WorldReadiness.Status(spec, WorldReadiness.State.FAILED, "the server did not load it");
        }
        return new WorldReadiness.Status(spec, exists ? WorldReadiness.State.LOADED : WorldReadiness.State.CREATED, null);
    }
}
