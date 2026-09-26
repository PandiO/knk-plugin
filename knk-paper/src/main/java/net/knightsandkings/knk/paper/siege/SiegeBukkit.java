package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.siege.SiegeDisplayText;
import net.knightsandkings.knk.core.siege.SiegeFloor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;

/** Small conversions between siege domain values and Bukkit/Adventure types. */
public final class SiegeBukkit {
    private SiegeBukkit() {}

    /** A runtime-config location in a loaded world, or empty (missing coordinates or world not loaded). */
    public static Optional<Location> toLocation(KnkLocation location) {
        if (location == null || location.x() == null || location.y() == null || location.z() == null) {
            return Optional.empty();
        }
        World world = location.world() != null ? Bukkit.getWorld(location.world()) : null;
        if (world == null) return Optional.empty();
        return Optional.of(new Location(world, location.x(), location.y(), location.z(),
                location.yaw() != null ? location.yaw() : 0f,
                location.pitch() != null ? location.pitch() : 0f));
    }

    /**
     * The capture point snapped to the floor under it (playtest 2026-09-26, {@link SiegeFloor}): the
     * capture ring and the capture distance use it, so a point captured in the air still sits on the
     * ground. Unchanged when the chunk isn't loaded or no floor is within reach.
     */
    public static Location floorOf(Location point) {
        World world = point.getWorld();
        if (world == null) return point;
        int x = point.getBlockX();
        int z = point.getBlockZ();
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return point;
        OptionalDouble floor = SiegeFloor.floorY(point.getY(),
                by -> by >= world.getMinHeight() && by < world.getMaxHeight() && !world.getBlockAt(x, by, z).isPassable(),
                by -> world.getBlockAt(x, by, z).getBoundingBox().getMaxY());
        if (floor.isEmpty()) return point;
        Location snapped = point.clone();
        snapped.setY(floor.getAsDouble());
        return snapped;
    }

    /** A Bukkit ChatColor name (e.g. {@code DARK_RED}) as an Adventure colour; white when unknown. */
    public static NamedTextColor color(String chatColor) {
        if (chatColor == null) return NamedTextColor.WHITE;
        NamedTextColor color = NamedTextColor.NAMES.value(chatColor.trim().toLowerCase(Locale.ROOT));
        return color != null ? color : NamedTextColor.WHITE;
    }

    public static NamedTextColor color(KnkSiegeTeam team) {
        return color(team == null ? null : team.chatColor());
    }

    public static String teamName(KnkSiegeTeam team) {
        if (team == null) return "?";
        return SiegeDisplayText.clean(team.name(), "Team " + team.id());
    }

    /** The team name in its colour. */
    public static Component teamComponent(KnkSiegeTeam team) {
        return Component.text(teamName(team), color(team));
    }
}
