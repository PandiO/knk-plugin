package net.knightsandkings.knk.core.worlds;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What became of each configured world at startup (KNG-114). The server is ready when every required world is loaded;
 * until then players may not join (operators may, to fix it).
 */
public record WorldReadiness(List<Status> worlds) {

    public enum State {
        /** The server had already loaded it (the {@code level-name} world, or loaded by another plugin). */
        ALREADY_LOADED,
        /** Loaded from its existing folder. */
        LOADED,
        /** Its folder did not exist; KnK created it. */
        CREATED,
        /** Its folder does not exist and {@code create} is off. */
        MISSING,
        /** Loading or creating it failed. */
        FAILED;

        public boolean available() {
            return this == ALREADY_LOADED || this == LOADED || this == CREATED;
        }
    }

    public record Status(WorldSpec spec, State state, String detail) {
        public String name() {
            return spec.name();
        }

        /** {@code hub: created (normal, void)} / {@code hub: missing - folder not found and create is off}. */
        public String describe() {
            String text = spec.name() + ": " + state.name().toLowerCase(Locale.ROOT).replace('_', ' ');
            if (state.available()) {
                text += " (" + spec.environment().name().toLowerCase(Locale.ROOT)
                        + (state == State.CREATED ? ", " + spec.generator().name().toLowerCase(Locale.ROOT) : "") + ")";
            }
            return detail == null || detail.isBlank() ? text : text + " - " + detail;
        }
    }

    public WorldReadiness {
        worlds = List.copyOf(worlds);
    }

    public static WorldReadiness none() {
        return new WorldReadiness(List.of());
    }

    /** Whether every required world is available. */
    public boolean ready() {
        return unavailableRequired().isEmpty();
    }

    public List<Status> unavailableRequired() {
        return worlds.stream().filter(s -> s.spec().required() && !s.state().available()).toList();
    }

    /** Whether {@code worldName} is one of the configured worlds (case-insensitive, as Bukkit world lookups are). */
    public Optional<Status> find(String worldName) {
        return worlds.stream().filter(s -> s.name().equalsIgnoreCase(worldName)).findFirst();
    }
}
