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
 * @param x         move the node: floor block x (rev. 5); {@code x}, {@code y}, {@code z} all or none
 * @param y         move the node: floor block y
 * @param z         move the node: floor block z
 * @param plazaRadius make the node the centre of a designed plaza of this radius (DESIGN §5.6 step 4),
 *                  or {@code null}
 * @param clearPlaza  clear the node's plaza
 */
public record RoadNodeUpdate(String name, boolean clearName, RoadNodeKind kind, Boolean locked,
                             Integer x, Integer y, Integer z, Integer plazaRadius, boolean clearPlaza) {
    public RoadNodeUpdate {
        if (clearName && name != null) {
            throw new IllegalArgumentException("clearName and name are exclusive");
        }
        if ((x == null) != (y == null) || (y == null) != (z == null)) {
            throw new IllegalArgumentException("x, y and z go together");
        }
        if (clearPlaza && plazaRadius != null) {
            throw new IllegalArgumentException("clearPlaza and plazaRadius are exclusive");
        }
    }

    /** An update without a move or a plaza change. */
    public RoadNodeUpdate(String name, boolean clearName, RoadNodeKind kind, Boolean locked) {
        this(name, clearName, kind, locked, null, null, null, null, false);
    }

    /** Move the node to this floor block (it is locked there). */
    public static RoadNodeUpdate moveTo(int x, int y, int z) {
        return new RoadNodeUpdate(null, false, null, null, x, y, z, null, false);
    }

    /** Make the node the centre of a designed plaza of this radius (it is locked). */
    public static RoadNodeUpdate plaza(int radius) {
        return new RoadNodeUpdate(null, false, null, null, null, null, null, radius, false);
    }

    /** Clear the node's plaza. */
    public static RoadNodeUpdate noPlaza() {
        return new RoadNodeUpdate(null, false, null, null, null, null, null, null, true);
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
