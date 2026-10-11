package net.knightsandkings.knk.core.domain.location;

/** Where {@code /knk location tp <id>} sends a staff member (KNG-80): any Location's world and position. */
public record LocationTeleportTarget(int id, String name, String world, double x, double y, double z, float yaw, float pitch) {
}
