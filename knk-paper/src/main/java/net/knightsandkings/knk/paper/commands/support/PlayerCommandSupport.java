package net.knightsandkings.knk.paper.commands.support;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.paper.permissions.KnkPermissible;

/**
 * Shared plumbing for the player utility commands ported from v2 in KNG-9 ({@code /fly},
 * {@code /heal}, {@code /feed}, {@code /enderchest}, {@code /inventory}): permission checks through
 * {@link KnkPermissible}, online-player lookup and tab completion.
 * <p>
 * Permission checks use {@link KnkPermissible#hasPermissionAsync}, not the sync cache-only
 * {@link KnkPermissible#hasPermission}: a command can wait one round trip, and the sync check fails
 * closed on a cold cache, so a granted player would be refused the first time. The console always
 * passes (it has no knk user; same as every other console-safe command here). Ops pass through
 * KnkPermissible's own op bypass.
 */
public final class PlayerCommandSupport {

    private static final Logger LOGGER = Logger.getLogger(PlayerCommandSupport.class.getName());

    private final KnkPermissible knkPermissible;
    private final Executor mainThread;
    private final Function<String, Player> onlinePlayerByName;
    private final Supplier<Collection<? extends Player>> onlinePlayers;

    public PlayerCommandSupport(KnkPermissible knkPermissible, Executor mainThread,
                                Function<String, Player> onlinePlayerByName,
                                Supplier<Collection<? extends Player>> onlinePlayers) {
        this.knkPermissible = Objects.requireNonNull(knkPermissible, "knkPermissible must not be null");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread must not be null");
        this.onlinePlayerByName = Objects.requireNonNull(onlinePlayerByName, "onlinePlayerByName must not be null");
        this.onlinePlayers = Objects.requireNonNull(onlinePlayers, "onlinePlayers must not be null");
    }

    /**
     * Runs {@code onAllowed} on the main thread if {@code sender} holds {@code node}; otherwise tells
     * the sender they lack permission.
     */
    public void whenAllowed(CommandSender sender, String node, Runnable onAllowed) {
        if (!(sender instanceof Player player)) {
            onAllowed.run();
            return;
        }
        knkPermissible.hasPermissionAsync(player, node)
                .exceptionally(ex -> {
                    LOGGER.log(Level.WARNING, "Permission check failed for " + player.getName() + ", node " + node, ex);
                    return false;
                })
                .thenAccept(allowed -> mainThread.execute(() -> {
                    if (Boolean.TRUE.equals(allowed)) {
                        onAllowed.run();
                    } else {
                        sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
                    }
                }));
    }

    /**
     * Like {@link #whenAllowed}, but holding any one of {@code nodes} is enough (e.g. a new node and
     * the legacy node it replaces).
     */
    public void whenAnyAllowed(CommandSender sender, List<String> nodes, Runnable onAllowed) {
        if (!(sender instanceof Player player)) {
            onAllowed.run();
            return;
        }
        CompletableFuture<Boolean> any = CompletableFuture.completedFuture(false);
        for (String node : nodes) {
            CompletableFuture<Boolean> check = knkPermissible.hasPermissionAsync(player, node)
                    .exceptionally(ex -> {
                        LOGGER.log(Level.WARNING, "Permission check failed for " + player.getName() + ", node " + node, ex);
                        return false;
                    });
            any = any.thenCombine(check, (a, b) -> Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b));
        }
        any.thenAccept(allowed -> mainThread.execute(() -> {
            if (Boolean.TRUE.equals(allowed)) {
                onAllowed.run();
            } else {
                sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
            }
        }));
    }

    /** The sender as a player, or null after telling a non-player sender the command is player-only. */
    public Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(ChatColor.RED + "Only players can use this command.");
        return null;
    }

    /** The online player with exactly this name (case-insensitive), or null. */
    public Player onlinePlayer(String name) {
        return onlinePlayerByName.apply(name);
    }

    /**
     * The online player with this name, or null after telling the sender they're not online.
     * {@code offlineHint} is appended when set (e.g. why an offline target isn't supported).
     */
    public Player requireOnlinePlayer(CommandSender sender, String name, String offlineHint) {
        Player target = onlinePlayer(name);
        if (target == null) {
            String message = ChatColor.RED + "No online player named '" + name + "'.";
            if (offlineHint != null && !offlineHint.isBlank()) {
                message += " " + ChatColor.GRAY + offlineHint;
            }
            sender.sendMessage(message);
        }
        return target;
    }

    public Collection<? extends Player> onlinePlayers() {
        return onlinePlayers.get();
    }

    public Executor mainThread() {
        return mainThread;
    }

    /** Online player names starting with {@code prefix} (case-insensitive), plus any {@code extra} options. */
    public List<String> completePlayers(String prefix, String... extra) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        Stream<String> names = onlinePlayers().stream().map(Player::getName);
        return Stream.concat(Arrays.stream(extra), names)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public static boolean isSelf(CommandSender sender, Player target) {
        return sender instanceof Player player && player.getUniqueId().equals(target.getUniqueId());
    }
}
