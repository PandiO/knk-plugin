package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.siege.SiegeDisplayText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Locale;
import java.util.Optional;

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
