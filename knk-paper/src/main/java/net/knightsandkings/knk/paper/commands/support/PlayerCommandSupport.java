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

import net.knightsandkings.knk.core.domain.permissions.PermissionDecision;
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
    private final Supplier<? extends Collection<String>> knownPlayerNames;

    public PlayerCommandSupport(KnkPermissible knkPermissible, Executor mainThread,
                                Function<String, Player> onlinePlayerByName,
                                Supplier<Collection<? extends Player>> onlinePlayers) {
        this(knkPermissible, mainThread, onlinePlayerByName, onlinePlayers, List::of);
    }

    public PlayerCommandSupport(KnkPermissible knkPermissible, Executor mainThread,
                                Function<String, Player> onlinePlayerByName,
                                Supplier<Collection<? extends Player>> onlinePlayers,
                                Supplier<? extends Collection<String>> knownPlayerNames) {
        this.knkPermissible = Objects.requireNonNull(knkPermissible, "knkPermissible must not be null");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread must not be null");
        this.onlinePlayerByName = Objects.requireNonNull(onlinePlayerByName, "onlinePlayerByName must not be null");
        this.onlinePlayers = Objects.requireNonNull(onlinePlayers, "onlinePlayers must not be null");
        this.knownPlayerNames = Objects.requireNonNull(knownPlayerNames, "knownPlayerNames must not be null");
    }

    /** Told instead of "no permission" when the permission service couldn't be asked. */
    public static final String UNAVAILABLE_MESSAGE =
            ChatColor.RED + "Your permissions can't be checked right now (the KnK service is unreachable) - try again in a moment.";

    /**
     * Runs {@code onAllowed} on the main thread if {@code sender} holds {@code node}; otherwise tells
     * the sender they lack permission - or, when the check couldn't be made (API unreachable),
     * that it couldn't be checked ({@link KnkPermissible#checkAsync}).
     */
    public void whenAllowed(CommandSender sender, String node, Runnable onAllowed) {
        if (!(sender instanceof Player player)) {
            onAllowed.run();
            return;
        }
        knkPermissible.checkAsync(player, node)
                .exceptionally(ex -> {
                    LOGGER.log(Level.WARNING, "Permission check failed for " + player.getName() + ", node " + node, ex);
                    return PermissionDecision.UNAVAILABLE;
                })
                .thenAccept(decision -> mainThread.execute(() -> {
                    if (decision == PermissionDecision.ALLOWED) {
                        onAllowed.run();
                    } else if (decision == PermissionDecision.DENIED) {
                        sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
                    } else {
                        sender.sendMessage(UNAVAILABLE_MESSAGE);
                    }
                }));
    }

    /**
     * Immediate answer for tab completion (KNG-107): the cached in-house answer
     * ({@link KnkPermissible#hasPermission}; ops pass, the console passes). Never waits on
     * knk-web-api: on a "no" it asks in the background, so a cold or expired cache shows the real
     * answer from the next keystroke on (a cached "no" is answered from the cache, no API call).
     * Running the command still checks for real ({@link #whenAllowed}).
     */
    public boolean holds(CommandSender sender, String node) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        if (knkPermissible.hasPermission(player, node)) {
            return true;
        }
        try {
            knkPermissible.checkAsync(player, node);
        } catch (RuntimeException ex) {
            LOGGER.log(Level.FINE, "Background permission check failed for " + player.getName() + ", node " + node, ex);
        }
        return false;
    }

    /** {@link #holds} for any one of {@code nodes}. */
    public boolean holdsAny(CommandSender sender, Collection<String> nodes) {
        boolean any = false;
        for (String node : nodes) {
            any |= holds(sender, node);
        }
        return any;
    }

    /** Whether {@code player} holds {@code node}, quietly: false when it couldn't be checked. Any thread. */
    public CompletableFuture<Boolean> hasAsync(Player player, String node) {
        try {
            return knkPermissible.checkAsync(player, node)
                    .thenApply(decision -> decision == PermissionDecision.ALLOWED)
                    .exceptionally(ex -> false);
        } catch (RuntimeException ex) {
            return CompletableFuture.completedFuture(false);
        }
    }

    /**
     * Like {@link #whenAllowed}, but holding any one of {@code nodes} is enough (e.g. a new node and
     * the legacy node it replaces). Refused as "can't be checked" only when no node was allowed and
     * at least one check couldn't be made.
     */
    public void whenAnyAllowed(CommandSender sender, List<String> nodes, Runnable onAllowed) {
        if (!(sender instanceof Player player)) {
            onAllowed.run();
            return;
        }
        CompletableFuture<PermissionDecision> any = CompletableFuture.completedFuture(PermissionDecision.DENIED);
        for (String node : nodes) {
            CompletableFuture<PermissionDecision> check = knkPermissible.checkAsync(player, node)
                    .exceptionally(ex -> {
                        LOGGER.log(Level.WARNING, "Permission check failed for " + player.getName() + ", node " + node, ex);
                        return PermissionDecision.UNAVAILABLE;
                    });
            any = any.thenCombine(check, PlayerCommandSupport::either);
        }
        any.thenAccept(decision -> mainThread.execute(() -> {
            if (decision == PermissionDecision.ALLOWED) {
                onAllowed.run();
            } else if (decision == PermissionDecision.DENIED) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
            } else {
                sender.sendMessage(UNAVAILABLE_MESSAGE);
            }
        }));
    }

    /** ALLOWED if either is; else UNAVAILABLE if either couldn't be checked; else DENIED. */
    private static PermissionDecision either(PermissionDecision a, PermissionDecision b) {
        if (a == PermissionDecision.ALLOWED || b == PermissionDecision.ALLOWED) {
            return PermissionDecision.ALLOWED;
        }
        if (a == PermissionDecision.UNAVAILABLE || b == PermissionDecision.UNAVAILABLE) {
            return PermissionDecision.UNAVAILABLE;
        }
        return PermissionDecision.DENIED;
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

    /** Vanish-safe online player names starting with {@code prefix}, plus any fixed options. */
    /** The {@code words} starting with {@code prefix} (case-insensitive), for completions without player names. */
    public static List<String> completeWords(String prefix, Collection<String> words) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return words.stream().filter(word -> word.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    public List<String> completePlayers(CommandSender sender, String prefix, String... extra) {
        return completePlayers(sender, prefix, false, extra);
    }

    /**
     * Vanish-safe online players plus cached account names for commands that support offline targets.
     */
    public List<String> completeKnownPlayers(CommandSender sender, String prefix, String... extra) {
        return completePlayers(sender, prefix, true, extra);
    }

    private List<String> completePlayers(CommandSender sender, String prefix, boolean includeKnownOffline,
                                         String... extra) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        Collection<? extends Player> online = onlinePlayers();
        Stream<String> visibleOnline = online.stream()
                .filter(player -> VisiblePlayers.canSee(sender, player))
                .map(Player::getName);
        Stream<String> knownOffline = includeKnownOffline
                ? knownPlayerNames.get().stream()
                        .filter(name -> online.stream().noneMatch(player -> player.getName().equalsIgnoreCase(name)))
                : Stream.empty();
        return Stream.of(Arrays.stream(extra), visibleOnline, knownOffline).flatMap(stream -> stream)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lower))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public static boolean isSelf(CommandSender sender, Player target) {
        return sender instanceof Player player && player.getUniqueId().equals(target.getUniqueId());
    }
}
