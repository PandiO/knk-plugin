package net.knightsandkings.knk.paper.commands;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.users.UserIgnore;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.UserIgnoresApi;
import net.knightsandkings.knk.paper.commands.support.VisiblePlayers;
import net.knightsandkings.knk.paper.user.IgnoreService;

/**
 * /ignore [player] and /unignore &lt;player&gt; (KNG-18 Phase 2, docs/specs/private-messages/
 * DESIGN.md §3.3.1/§3.3.5). No argument lists who you ignore; /ignore &lt;player&gt; toggles, and
 * /unignore &lt;player&gt; only removes. An ignored player's private messages never reach you
 * (they don't find out) and their public chat is hidden from you. Works for offline players; staff
 * holding {@code knk.msg.unignorable} can't be ignored (knk-web-api enforces it, this pre-checks
 * so the answer is immediate). No permission needed.
 */
public class IgnoreCommand implements TabExecutor {

    /** Resolves a player name (online or not) to their knk user; tells the sender when there is none. */
    @FunctionalInterface
    public interface TargetResolver {
        void resolve(CommandSender sender, String name, Consumer<UserSummary> onFound);
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final IgnoreService ignoreService;
    private final TargetResolver targets;
    private final Function<UUID, CompletableFuture<Boolean>> unignorable;
    private final VisiblePlayers visiblePlayers;
    private final Executor mainThread;
    private final Logger logger;
    private final boolean removeOnly;

    /**
     * @param unignorable whether the player with this UUID holds {@code knk.msg.unignorable} (ops count)
     * @param removeOnly  true for /unignore, false for /ignore
     */
    public IgnoreCommand(IgnoreService ignoreService, TargetResolver targets,
                         Function<UUID, CompletableFuture<Boolean>> unignorable, VisiblePlayers visiblePlayers,
                         Executor mainThread, Logger logger, boolean removeOnly) {
        this.ignoreService = Objects.requireNonNull(ignoreService, "ignoreService must not be null");
        this.targets = Objects.requireNonNull(targets, "targets must not be null");
        this.unignorable = Objects.requireNonNull(unignorable, "unignorable must not be null");
        this.visiblePlayers = Objects.requireNonNull(visiblePlayers, "visiblePlayers must not be null");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread must not be null");
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
        this.removeOnly = removeOnly;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        if (args.length > 1 || (removeOnly && args.length == 0)) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + (removeOnly ? " <player>" : " [player]"));
            return true;
        }
        UUID uuid = player.getUniqueId();
        if (!ignoreService.isLoaded(uuid)) {
            ignoreService.load(uuid);
            sender.sendMessage(ChatColor.RED + "Your ignore list is still loading - try again in a moment.");
            return true;
        }
        if (args.length == 0) {
            sendList(player);
            return true;
        }

        String name = args[0];
        if (name.equalsIgnoreCase(player.getName())) {
            sender.sendMessage(ChatColor.RED + "You can't ignore yourself.");
            return true;
        }
        Optional<UserIgnore> listed = ignoreService.find(uuid, name);
        if (listed.isPresent()) {
            unignore(player, listed.get());
        } else if (removeOnly) {
            sender.sendMessage(ChatColor.RED + "You aren't ignoring " + name + ".");
        } else {
            targets.resolve(player, name, target -> ignore(player, target));
        }
        return true;
    }

    private void sendList(Player player) {
        List<UserIgnore> list = ignoreService.list(player.getUniqueId()).orElse(List.of());
        if (list.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "You aren't ignoring anyone. Use /ignore <player> to ignore someone.");
            return;
        }
        player.sendMessage(ChatColor.YELLOW + "You are ignoring " + list.size() + (list.size() == 1 ? " player:" : " players:"));
        for (UserIgnore entry : list) {
            String since = entry.createdAt() != null ? " " + ChatColor.DARK_GRAY + "(since " + DATE.format(entry.createdAt()) + ")" : "";
            player.sendMessage(ChatColor.GRAY + "- " + entry.ignoredUsername() + since);
        }
    }

    /** Main thread, with the resolved target. */
    private void ignore(Player player, UserSummary target) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline() || target.id() == null) {
            return;
        }
        if (uuid.equals(target.uuid())) {
            player.sendMessage(ChatColor.RED + "You can't ignore yourself.");
            return;
        }
        Optional<UserIgnore> listed = ignoreService.find(uuid, target.id());
        if (listed.isPresent()) {
            // Listed under an older name - still a toggle.
            unignore(player, listed.get());
            return;
        }
        CompletableFuture<Boolean> staff = target.uuid() != null
                ? unignorable.apply(target.uuid()).exceptionally(ex -> false)
                : CompletableFuture.completedFuture(false);
        staff.thenAccept(isStaff -> mainThread.execute(() -> {
            if (Boolean.TRUE.equals(isStaff)) {
                player.sendMessage(ChatColor.RED + "You can't ignore staff.");
                return;
            }
            ignoreService.ignore(uuid, target.id(), target.username(), target.uuid())
                    .whenComplete((result, ex) -> mainThread.execute(() -> reportIgnore(player, target.username(), result, ex)));
        }));
    }

    private void reportIgnore(Player player, String name, UserIgnoresApi.AddResult result, Throwable ex) {
        if (ex != null) {
            logger.log(Level.WARNING, player.getName() + " could not ignore " + name, ex);
            player.sendMessage(ChatColor.RED + "Couldn't update your ignore list - try again later.");
            return;
        }
        switch (result) {
            case IGNORED -> {
                player.sendMessage(ChatColor.GREEN + "You are now ignoring " + name + ". You won't see their messages or chat.");
                logger.info(player.getName() + " is now ignoring " + name);
            }
            case CANNOT_IGNORE_STAFF -> player.sendMessage(ChatColor.RED + "You can't ignore staff.");
            case LIMIT_REACHED -> player.sendMessage(ChatColor.RED + "Your ignore list is full - /unignore someone first.");
            case SELF_IGNORE -> player.sendMessage(ChatColor.RED + "You can't ignore yourself.");
            case USER_NOT_FOUND -> player.sendMessage(ChatColor.RED + "No player found named '" + name + "'.");
        }
    }

    private void unignore(Player player, UserIgnore entry) {
        String name = entry.ignoredUsername();
        ignoreService.unignore(player.getUniqueId(), entry.ignoredUserId()).whenComplete((ignored, ex) -> mainThread.execute(() -> {
            if (ex != null) {
                logger.log(Level.WARNING, player.getName() + " could not unignore " + name, ex);
                player.sendMessage(ChatColor.RED + "Couldn't update your ignore list - try again later.");
                return;
            }
            player.sendMessage(ChatColor.GRAY + "You are no longer ignoring " + name + ".");
            logger.info(player.getName() + " is no longer ignoring " + name);
        }));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !(sender instanceof Player player)) {
            return List.of();
        }
        if (!removeOnly) {
            return visiblePlayers.completeOthers(sender, args[0]);
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return ignoreService.list(player.getUniqueId()).orElse(List.of()).stream()
                .map(UserIgnore::ignoredUsername)
                .filter(name -> name != null && name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }
}
