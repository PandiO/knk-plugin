package net.knightsandkings.knk.paper.commands.support;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Vanish-safe online-player lookup for commands that take another player's name (/msg, /r; also
 * meant for /tpa and /pay - docs/specs/private-messages/DESIGN.md §3.3.2).
 * <p>
 * Staff/owner mode hides a player per viewer ({@code ModeService} {@code hidePlayer}), so
 * {@link Player#canSee(Player)} is the "may this sender know the target is online" oracle. A
 * hidden player is treated exactly like an offline or unknown one: {@link #find} returns null and
 * every caller prints the same {@link #notFoundMessage(String)} line, so the reply never reveals
 * that a vanished player - or even an account by that name - exists. Tab completion only offers
 * players the viewer can see. The console (any non-player sender) sees everyone.
 */
public final class VisiblePlayers {

    private final Function<String, Player> byExactName;
    private final Function<UUID, Player> byUuid;
    private final Supplier<Collection<? extends Player>> onlinePlayers;

    public VisiblePlayers(Function<String, Player> byExactName, Function<UUID, Player> byUuid,
                          Supplier<Collection<? extends Player>> onlinePlayers) {
        this.byExactName = Objects.requireNonNull(byExactName, "byExactName must not be null");
        this.byUuid = Objects.requireNonNull(byUuid, "byUuid must not be null");
        this.onlinePlayers = Objects.requireNonNull(onlinePlayers, "onlinePlayers must not be null");
    }

    /** Backed by {@link Bukkit#getPlayerExact}, {@link Bukkit#getPlayer(UUID)} and {@link Bukkit#getOnlinePlayers}. */
    public static VisiblePlayers bukkit() {
        return new VisiblePlayers(Bukkit::getPlayerExact, Bukkit::getPlayer, Bukkit::getOnlinePlayers);
    }

    /** The same line for unknown, offline and hidden players. */
    public static String notFoundMessage(String name) {
        return "No online player found named '" + name + "'.";
    }

    public static void sendNotFound(CommandSender sender, String name) {
        sender.sendMessage(ChatColor.RED + notFoundMessage(name));
    }

    /** Whether {@code viewer} may know {@code target} is online; true when either side is not a player. */
    public static boolean canSee(CommandSender viewer, CommandSender target) {
        if (!(viewer instanceof Player viewerPlayer) || !(target instanceof Player targetPlayer)) {
            return true;
        }
        return viewerPlayer.getUniqueId().equals(targetPlayer.getUniqueId()) || viewerPlayer.canSee(targetPlayer);
    }

    /** The online player named exactly {@code name} (case-insensitive) whom {@code viewer} can see, else null. */
    public Player find(CommandSender viewer, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return visibleOrNull(viewer, byExactName.apply(name));
    }

    /** The online player with this UUID whom {@code viewer} can see, else null. */
    public Player find(CommandSender viewer, UUID uuid) {
        return uuid == null ? null : visibleOrNull(viewer, byUuid.apply(uuid));
    }

    /** Like {@link #find(CommandSender, String)}, but tells the sender when nobody is found. */
    public Player require(CommandSender viewer, String name) {
        Player target = find(viewer, name);
        if (target == null) {
            sendNotFound(viewer, name);
        }
        return target;
    }

    /**
     * The online player with this UUID regardless of visibility, or null - only for callers that
     * decide themselves what to reveal (e.g. /r to a vanished staff member who messaged you).
     */
    public Player onlineIgnoringVisibility(UUID uuid) {
        Player player = uuid == null ? null : byUuid.apply(uuid);
        return player != null && player.isOnline() ? player : null;
    }

    /** Names of players {@code viewer} can see starting with {@code prefix} (case-insensitive), the viewer included. */
    public List<String> complete(CommandSender viewer, String prefix) {
        return complete(viewer, prefix, true);
    }

    /** Same as {@link #complete(CommandSender, String)} without the viewer's own name. */
    public List<String> completeOthers(CommandSender viewer, String prefix) {
        return complete(viewer, prefix, false);
    }

    private List<String> complete(CommandSender viewer, String prefix, boolean includeSelf) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return onlinePlayers.get().stream()
                .filter(player -> includeSelf || !isSelf(viewer, player))
                .filter(player -> canSee(viewer, player))
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private static Player visibleOrNull(CommandSender viewer, Player target) {
        if (target == null || !target.isOnline()) {
            return null;
        }
        return canSee(viewer, target) ? target : null;
    }

    private static boolean isSelf(CommandSender viewer, Player player) {
        return viewer instanceof Player self && self.getUniqueId().equals(player.getUniqueId());
    }
}
