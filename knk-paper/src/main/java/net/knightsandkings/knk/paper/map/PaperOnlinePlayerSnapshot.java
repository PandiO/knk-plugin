package net.knightsandkings.knk.paper.map;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.map.OnlinePlayerPosition;
import net.knightsandkings.knk.core.map.OnlinePlayerSnapshotPort;

/**
 * The overview map's live player snapshot on Paper (PandiO/knk-workspace#738, OVERVIEW_MAP_DESIGN § 7.2, § 8, § 9.1):
 * one pass over the online players on the main thread, reading position, yaw, AFK, active mode and running minigame
 * match. Every lookup is a cached in-memory read (no API call, no world access beyond the player's own location); the
 * send happens off the main thread ({@link MapReporting#startPlayerReports}).
 */
public final class PaperOnlinePlayerSnapshot implements OnlinePlayerSnapshotPort {

    /** A player's running minigame match: the short key ({@code "siege"}) and the API's match id (null while unknown). */
    public record MinigameMatch(String minigame, Long matchId) {
    }

    private final Supplier<? extends Collection<? extends Player>> online;
    private final Predicate<UUID> afk;
    private final Function<Player, ActiveMode> mode;
    private final Function<UUID, MinigameMatch> minigame;

    /**
     * @param online   the online players ({@code Bukkit::getOnlinePlayers})
     * @param afk      whether a player is AFK (false when player statistics are off)
     * @param mode     a player's active mode (NONE when the mode service is not there)
     * @param minigame a player's running match, or null outside one
     */
    public PaperOnlinePlayerSnapshot(Supplier<? extends Collection<? extends Player>> online, Predicate<UUID> afk,
                                     Function<Player, ActiveMode> mode, Function<UUID, MinigameMatch> minigame) {
        this.online = online;
        this.afk = afk;
        this.mode = mode;
        this.minigame = minigame;
    }

    @Override
    public List<OnlinePlayerPosition> snapshot() {
        Collection<? extends Player> players = online.get();
        if (players == null || players.isEmpty()) {
            return List.of();
        }
        List<OnlinePlayerPosition> result = new ArrayList<>(players.size());
        for (Player player : players) {
            Location location = player.getLocation();
            World world = location.getWorld();
            if (world == null) {
                continue;
            }
            UUID uuid = player.getUniqueId();
            MinigameMatch match = minigame == null ? null : minigame.apply(uuid);
            result.add(new OnlinePlayerPosition(uuid, world.getName(), location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), afk != null && afk.test(uuid), mode == null ? ActiveMode.NONE : mode.apply(player),
                    match == null ? null : match.minigame(), match == null ? null : match.matchId()));
        }
        return result;
    }
}
