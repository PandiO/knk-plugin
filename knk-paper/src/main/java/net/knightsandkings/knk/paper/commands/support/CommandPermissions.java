package net.knightsandkings.knk.paper.commands.support;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.permissions.PermissionDecision;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;

/**
 * Permission checks for commands whose node can be granted in-house (KNG-24): a sender may use the
 * node when Bukkit grants it (ops through {@code default: op}, or a {@code default: true} node) or
 * when the knk permission model does ({@link KnkPermissible} - group grants, wildcards such as
 * {@code knk.*} / {@code knk.admin.*}; ops always pass). The console always passes.
 * <p>
 * Bukkit alone never sees in-house grants, which is why a {@code permission:} entry in plugin.yml
 * or a bare {@code sender.hasPermission(node)} refused non-op staff (KNG-24). Two shapes:
 * <ul>
 *   <li>{@link #whenAllowed} for running a command: waits for a real answer
 *       ({@link KnkPermissible#checkAsync}), so a granted player isn't refused on a cold cache, and
 *       says "can't be checked right now" instead of "no permission" when the API is down;</li>
 *   <li>{@link #has} where an immediate answer is needed (help listings, tab completion, the
 *       command list sent to the client, broadcast audiences): the cache-only check, which fails
 *       closed until the cache is warm. {@link #warm} fills the cache for nodes an executor is about
 *       to check this way.</li>
 * </ul>
 */
public final class CommandPermissions {

    private static final Logger LOGGER = Logger.getLogger(CommandPermissions.class.getName());

    /** Told when a player lacks the node. */
    public static final String NO_PERMISSION_MESSAGE = ChatColor.RED + "You don't have permission to use this command.";

    private final BiPredicate<Player, String> cachedCheck;
    private final BiFunction<Player, String, CompletableFuture<PermissionDecision>> asyncCheck;
    private final Executor mainThread;

    CommandPermissions(BiPredicate<Player, String> cachedCheck,
                       BiFunction<Player, String, CompletableFuture<PermissionDecision>> asyncCheck,
                       Executor mainThread) {
        this.cachedCheck = cachedCheck;
        this.asyncCheck = asyncCheck;
        this.mainThread = mainThread;
    }

    /** In-house checks through {@code knkPermissible}; Bukkit-only when it is null (no API configured). */
    public static CommandPermissions of(KnkPermissible knkPermissible, Executor mainThread) {
        if (knkPermissible == null) {
            return bukkitOnly();
        }
        return new CommandPermissions(knkPermissible::hasPermission, knkPermissible::checkAsync, mainThread);
    }

    /** Plain Bukkit checks - the behaviour before KNG-24; for tests and when the API isn't configured. */
    public static CommandPermissions bukkitOnly() {
        return new CommandPermissions(null, null, Runnable::run);
    }

    /** Immediate answer: Bukkit grant, or a cached in-house grant. Null node = no gate. */
    public boolean has(CommandSender sender, String node) {
        if (node == null || !(sender instanceof Player player) || sender.hasPermission(node)) {
            return true;
        }
        return cachedCheck != null && cachedCheck.test(player, node);
    }

    /**
     * {@link #has} for tab completion (KNG-107): on a "no" for a player it also asks the in-house
     * model in the background (never waited on), so a cold or expired cache shows the real answer
     * from the next keystroke on. A cached "no" is answered from the cache without an API call.
     */
    public boolean hasForCompletion(CommandSender sender, String node) {
        if (has(sender, node)) {
            return true;
        }
        if (asyncCheck != null && sender instanceof Player player) {
            decide(player, node);
        }
        return false;
    }

    /**
     * Runs {@code onAllowed} if the sender may use {@code node} - straight away for the console, a
     * Bukkit grant or a null node, otherwise on the main thread once the in-house check answers.
     * Refusals are told to the sender ({@link #NO_PERMISSION_MESSAGE}, or
     * {@link PlayerCommandSupport#UNAVAILABLE_MESSAGE} when the check couldn't be made).
     */
    public void whenAllowed(CommandSender sender, String node, Runnable onAllowed) {
        if (node == null || !(sender instanceof Player player) || sender.hasPermission(node)) {
            onAllowed.run();
            return;
        }
        if (asyncCheck == null) {
            sender.sendMessage(NO_PERMISSION_MESSAGE);
            return;
        }
        decide(player, node).thenAccept(decision -> mainThread.execute(() -> {
            if (decision == PermissionDecision.ALLOWED) {
                onAllowed.run();
            } else if (decision == PermissionDecision.DENIED) {
                sender.sendMessage(NO_PERMISSION_MESSAGE);
            } else {
                sender.sendMessage(PlayerCommandSupport.UNAVAILABLE_MESSAGE);
            }
        }));
    }

    /**
     * Asks the in-house model about every node (filling the cache {@link #has} reads), then runs
     * {@code then} on the main thread whatever the answers. For executors that check several nodes
     * synchronously (e.g. {@code /knk user}'s per-property nodes), so a granted player isn't refused
     * just because nothing was cached yet. Runs {@code then} straight away for the console.
     */
    public void warm(CommandSender sender, Collection<String> nodes, Runnable then) {
        if (!(sender instanceof Player player) || asyncCheck == null || nodes.isEmpty()) {
            then.run();
            return;
        }
        List<CompletableFuture<PermissionDecision>> checks = nodes.stream()
                .filter(node -> !sender.hasPermission(node))
                .map(node -> decide(player, node))
                .toList();
        CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new))
                .thenRun(() -> mainThread.execute(then));
    }

    /** Bukkit grant, or the live in-house answer (false without an in-house model). Never fails. */
    public CompletableFuture<Boolean> hasAsync(Player player, String node) {
        if (node == null || player.hasPermission(node)) {
            return CompletableFuture.completedFuture(true);
        }
        if (asyncCheck == null) {
            return CompletableFuture.completedFuture(false);
        }
        return decide(player, node).thenApply(decision -> decision == PermissionDecision.ALLOWED);
    }

    private CompletableFuture<PermissionDecision> decide(Player player, String node) {
        CompletableFuture<PermissionDecision> check;
        try {
            check = asyncCheck.apply(player, node);
        } catch (RuntimeException ex) {
            check = CompletableFuture.failedFuture(ex);
        }
        return check.exceptionally(ex -> {
            LOGGER.log(Level.WARNING, "Permission check failed for " + player.getName() + ", node " + node, ex);
            return PermissionDecision.UNAVAILABLE;
        });
    }
}
