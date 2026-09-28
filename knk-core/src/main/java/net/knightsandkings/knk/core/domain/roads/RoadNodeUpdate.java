package net.knightsandkings.knk.core.domain.roads;

/**
 * The body of {@code PUT api/road-nodes/{id}} (DESIGN §7 review). Every field is optional:
 * {@code null} leaves that attribute as it is. Mirrors the web-api's {@code RoadNodeUpdateDto}.
 * Bukkit-free.
 *
 * <p>The API locks the node ({@code locked = true}) on every update unless {@code locked} is
 * explicitly {@code false} (Phase 1 decision 7).
 *
 * @param name      new display name (a named node is a {@code /navigate} destination), or {@code null}
 * @param clearName remove the name
 * @param kind      new kind, or {@code null}
 * @param locked    lock or unlock the node's position, or {@code null} for the API's default
 */
public record RoadNodeUpdate(String name, boolean clearName, RoadNodeKind kind, Boolean locked) {
    public RoadNodeUpdate {
        if (clearName && name != null) {
            throw new IllegalArgumentException("clearName and name are exclusive");
        }
    }

    /** Name (or rename) the node. */
    public static RoadNodeUpdate rename(String name) {
        return new RoadNodeUpdate(name, false, null, null);
    }

    /** Remove the node's name. */
    public static RoadNodeUpdate unnamed() {
        return new RoadNodeUpdate(null, true, null, null);
    }

    /** Change the node's kind. */
    public static RoadNodeUpdate kind(RoadNodeKind kind) {
        return new RoadNodeUpdate(null, false, kind, null);
    }

    /** Lock or unlock the node's position. */
    public static RoadNodeUpdate locked(boolean locked) {
        return new RoadNodeUpdate(null, false, null, locked);
    }
}
