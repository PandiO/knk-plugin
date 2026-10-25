package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.siege.SiegeDisplayText;
import net.knightsandkings.knk.core.siege.SiegeFloor;
import net.knightsandkings.knk.paper.utils.KnkLocations;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import java.util.Locale;
import java.util.Optional;

/** Small conversions between siege domain values and Bukkit/Adventure types. */
public final class SiegeBukkit {
    private SiegeBukkit() {}

    /** A runtime-config location in a loaded world, or empty (delegates to {@link KnkLocations#toLocation}, R10). */
    public static Optional<Location> toLocation(KnkLocation location) {
        return KnkLocations.toLocation(location);
    }

    /** Moves a player for the siege; the plugin installs one that may cross domain borders ({@link #setTeleporter}). */
    @FunctionalInterface
    public interface Teleporter {
        boolean teleport(Player player, Location to);
    }

    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger(SiegeBukkit.class.getName());
    private static volatile Teleporter teleporter = (player, to) -> player.teleport(to, TeleportCause.PLUGIN);

    /**
     * Playtest 2026-10-24: a member waiting in the server hub (a domain whose AllowExit is off) was never
     * moved to the scenario hub or their spawnpoint - the domain-access rule refused the plain teleport, as
     * it refuses any teleport out of such a domain. KnKPlugin installs the same scoped transfer the hub's
     * portal and outage actions use (exempt from AllowEntry/AllowExit while the move runs, then a WorldGuard
     * session resync), so a siege teleport crosses domain borders like those do. Null restores the plain
     * teleport (tests).
     */
    public static void setTeleporter(Teleporter custom) {
        teleporter = custom != null ? custom : (player, to) -> player.teleport(to, TeleportCause.PLUGIN);
    }

    /**
     * A siege-initiated teleport (hub, match start, spawn pick, vault restore). Deliberately cause
     * {@code PLUGIN} and outside the teleport engine (docs/specs/teleport/DESIGN.md §3.1): the engine's
     * guards, {@link SiegeTeleportRestriction} among them, only judge player and staff teleports
     * (cause {@code COMMAND}), so the siege can always move its own members. A refused move (another
     * plugin cancelled the teleport) is logged, since the member then silently stays where they are.
     */
    public static boolean teleport(Player player, Location location) {
        if (player == null || location == null) return false;
        boolean moved = teleporter.teleport(player, location);
        if (!moved) {
            LOGGER.warning("[Siege] Teleport of " + player.getName() + " to " + describe(location)
                    + " was refused (cancelled by another plugin or a region rule); they stay where they are.");
        }
        return moved;
    }

    private static String describe(Location location) {
        return (location.getWorld() == null ? "?" : location.getWorld().getName()) + " " + location.getBlockX() + ","
                + location.getBlockY() + "," + location.getBlockZ();
    }

    /**
     * The capture point snapped to the floor under it (playtest 2026-09-26, {@link SiegeFloor}): the
     * capture ring and the capture distance use it, so a point captured in the air still sits on the
     * ground. Unchanged when the chunk isn't loaded or no floor is within reach. Delegates to
     * {@link KnkLocations#floorOf} (road navigation R10).
     */
    public static Location floorOf(Location point) {
        return KnkLocations.floorOf(point);
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
