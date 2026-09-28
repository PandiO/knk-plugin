package net.knightsandkings.knk.core.teleport;

import java.util.Objects;

import net.knightsandkings.knk.core.domain.location.KnkLocation;

/**
 * Where {@code /spawn} takes a player (docs/specs/teleport/DESIGN.md §3.6), as resolved by
 * {@link SpawnPointResolver}.
 *
 * @param location the spot; null means the main world's own spawn point
 * @param label    what to call it in messages and log lines
 * @param source   where the spot came from
 */
public record SpawnPoint(KnkLocation location, String label, Source source) {

    public enum Source {
        /** The Location, Town, District or Structure set on the Game Settings page, looked up now. */
        REFERENCE,
        /** The coordinates saved with that reference, because looking it up failed. */
        SNAPSHOT,
        /** No server spawn is set (or it can't be used): the main world's spawn. */
        WORLD_SPAWN
    }

    public SpawnPoint {
        Objects.requireNonNull(source, "source must not be null");
        label = label != null && !label.isBlank() ? label : "spawn";
    }

    public static SpawnPoint worldSpawn() {
        return new SpawnPoint(null, "spawn", Source.WORLD_SPAWN);
    }

    public boolean isWorldSpawn() {
        return location == null;
    }
}
