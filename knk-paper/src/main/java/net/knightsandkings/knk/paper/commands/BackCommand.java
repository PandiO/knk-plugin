package net.knightsandkings.knk.paper.commands;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.teleport.BackKind;
import net.knightsandkings.knk.core.teleport.TeleportDenial;
import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport;
import net.knightsandkings.knk.paper.commands.support.TargetRankCheck;
import net.knightsandkings.knk.paper.teleport.BackService;
import net.knightsandkings.knk.paper.teleport.TeleportNodes;
import net.knightsandkings.knk.paper.teleport.VisibleTargetResolver;

/**
 * {@code /back} (docs/specs/teleport/IMPLEMENTATION_PLAN.md Phase 7; Linear KNG-42).
 * <ul>
 *   <li>{@code /back} - back to the latest place among the kinds your nodes allow: where you died
 *       ({@value TeleportNodes#BACK}), or where you were before a warp ({@value TeleportNodes#BACK_WARPS}),
 *       a teleport ({@value TeleportNodes#BACK_TELEPORT}) or {@code /spawn} ({@value TeleportNodes#BACK_SPAWN});
 *       {@value TeleportNodes#BACK_ALL} allows all of them. Single use, within
 *       {@code teleport.back.expire-seconds}; a player teleport: warmup, cooldown, combat tag, safe-spot
 *       check, every guard, and {@code teleport.back.price-coins} when set ({@link BackService}).</li>
 *   <li>{@code /back <player> [-s]} - send someone back to their latest place of any kind
 *       ({@value TeleportNodes#STAFF_BACK_OTHERS}; you must outrank them). A staff teleport: instant,
 *       audited, uses their entry up. Console allowed. {@code -s} needs {@value TeleportNodes#STAFF_SILENT};
 *       always silent while you're vanished.</li>
 * </ul>
 * Permissions resolve through {@code KnkPermissible}, hence no {@code permission:} in plugin.yml.
 */
public class BackCommand implements TabExecutor {

    private final PlayerCommandSupport support;
    private final BackService backService;
    private final TargetRankCheck rankCheck;
    private final VisibleTargetResolver targets;
    private final Predicate<Player> isVanished;

    public BackCommand(PlayerCommandSupport support, BackService backService, TargetRankCheck rankCheck,
                       VisibleTargetResolver targets, Predicate<Player> isVanished) {
        this.support = Objects.requireNonNull(support, "support must not be null");
        this.backService = Objects.requireNonNull(backService, "backService must not be null");
        this.rankCheck = Objects.requireNonNull(rankCheck, "rankCheck must not be null");
        this.targets = Objects.requireNonNull(targets, "targets must not be null");
        this.isVanished = Objects.requireNonNull(isVanished, "isVanished must not be null");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!backService.isEnabled()) {
            sender.sendMessage(ChatColor.RED + "/back is turned off on this server.");
            return true;
        }
        StaffTeleportCommand.Parsed parsed = StaffTeleportCommand.Parsed.of(args);
        if (parsed.args().isEmpty() && !parsed.silent()) {
            self(sender);
        } else if (parsed.args().size() == 1) {
            other(sender, parsed.args().get(0), parsed.silent());
        } else {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /back | /back <player> [-s]");
        }
        return true;
    }

    private void self(CommandSender sender) {
        Player player = support.requirePlayer(sender);
        if (player == null) {
            sender.sendMessage(ChatColor.YELLOW + "Usage from the console: /back <player>");
            return;
        }
        support.whenAnyAllowed(sender, BackService.PLAYER_NODES, () ->
            backService.access(player).whenComplete((access, ex) -> support.mainThread().execute(() -> {
                if (ex != null || access == null || !access.allowsAny()) {
                    player.sendMessage(PlayerCommandSupport.UNAVAILABLE_MESSAGE);
                    return;
                }
                backService.start(player, access).thenAccept(trip -> reportOwn(player, trip));
            })));
    }

    private void other(CommandSender sender, String targetName, boolean silentRequested) {
        Player target = targets.find(sender, targetName);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "No online player named '" + targetName + "'.");
            return;
        }
        if (PlayerCommandSupport.isSelf(sender, target)) {
            self(sender);
            return;
        }
        support.whenAllowed(sender, TeleportNodes.STAFF_BACK_OTHERS, () -> withSilent(sender, silentRequested, silent ->
            rankCheck.whenOutranks(sender, target.getName(), summary ->
                backService.startFor(sender, target, silent).thenAccept(trip -> reportStaff(sender, target, silent, trip)))));
    }

    /** Tell a player how their own {@code /back} ended; the engine already explained a cancelled warmup. */
    static void reportOwn(Player player, BackService.Trip trip) {
        if (!player.isOnline()) {
            return;
        }
        TeleportOutcome outcome = trip.outcome();
        switch (outcome.status()) {
            case TELEPORTED -> player.sendMessage(ChatColor.GREEN + "Teleported back to " + describe(trip.kind()) + ".");
            case CANCELLED -> { }
            case DENIED, FAILED -> player.sendMessage(ChatColor.RED + reason(outcome, trip.kind()));
        }
    }

    private static void reportStaff(CommandSender sender, Player target, boolean silent, BackService.Trip trip) {
        TeleportOutcome outcome = trip.outcome();
        if (outcome.isTeleported()) {
            sender.sendMessage(ChatColor.GREEN + "Sent " + target.getName() + " back to " + describeOther(trip.kind()) + ".");
            if (!silent && target.isOnline()) {
                target.sendMessage(ChatColor.GRAY + sender.getName() + " sent you back to " + describe(trip.kind()) + ".");
            }
            return;
        }
        if (outcome.status() == TeleportOutcome.Status.CANCELLED) {
            return;
        }
        sender.sendMessage(ChatColor.RED + "Can't send " + target.getName() + " back: " + reason(outcome, trip.kind()));
    }

    private static String reason(TeleportOutcome outcome, BackKind kind) {
        if (TeleportDenial.UNSAFE.equals(outcome.code())) {
            return kind == BackKind.DEATH
                ? "Where you died isn't safe to return to (no safe ground nearby)."
                : "That place isn't safe to return to (no safe ground nearby).";
        }
        return outcome.message() != null ? outcome.message() : "The teleport didn't happen.";
    }

    private static String describe(BackKind kind) {
        return kind != null ? kind.description() : "where you were";
    }

    /** {@link BackKind#description()} about someone else. */
    private static String describeOther(BackKind kind) {
        if (kind == null) {
            return "where they were";
        }
        return switch (kind) {
            case DEATH -> "where they died";
            case WARPS -> "where they were before their warp";
            case TELEPORT -> "where they were before their teleport";
            case SPAWN -> "where they were before /spawn";
        };
    }

    /** Silent while the actor is vanished (DESIGN §3.4.4); otherwise only when asked, which needs the node. */
    private void withSilent(CommandSender sender, boolean requested, Consumer<Boolean> next) {
        if (sender instanceof Player player && isVanished.test(player)) {
            next.accept(true);
        } else if (requested) {
            support.whenAllowed(sender, TeleportNodes.STAFF_SILENT, () -> next.accept(true));
        } else {
            next.accept(false);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // KNG-107: the <player> form and its -s only for staff holding their nodes (cached check).
        if (!support.holds(sender, TeleportNodes.STAFF_BACK_OTHERS)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return targets.complete(sender, args[0]);
        }
        if (args.length == 2 && "-s".startsWith(args[1].toLowerCase(Locale.ROOT))
                && support.holds(sender, TeleportNodes.STAFF_SILENT)) {
            return List.of("-s");
        }
        return Collections.emptyList();
    }
}
