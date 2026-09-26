package net.knightsandkings.knk.paper.commands;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.teleport.SpawnPoint;
import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport;
import net.knightsandkings.knk.paper.commands.support.TargetRankCheck;
import net.knightsandkings.knk.paper.teleport.SpawnDestinationResolver;
import net.knightsandkings.knk.paper.teleport.TeleportNodes;
import net.knightsandkings.knk.paper.teleport.TeleportPlan;
import net.knightsandkings.knk.paper.teleport.TeleportService;
import net.knightsandkings.knk.paper.teleport.VisibleTargetResolver;

/**
 * {@code /spawn} (docs/specs/teleport/DESIGN.md §3.6, Phase 4). The destination is the spawn set on
 * the web-app Game Settings page, else the main world's spawn ({@link SpawnDestinationResolver}).
 * <ul>
 *   <li>{@code /spawn} - you to spawn ({@value TeleportNodes#SPAWN}). Free, no title/premium gate
 *       (the universal escape hatch, DESIGN §4 D5), but a player teleport: warmup, cooldown, combat
 *       tag, safe-spot check and every guard apply.</li>
 *   <li>{@code /spawn <player> [-s]} - send someone to spawn ({@value TeleportNodes#STAFF_OTHERS};
 *       you must outrank them). A staff teleport: instant, audited. Console allowed. {@code -s} needs
 *       {@value TeleportNodes#STAFF_SILENT}; always silent while you're vanished.</li>
 * </ul>
 * Permissions resolve through {@code KnkPermissible}, hence no {@code permission:} in plugin.yml.
 */
public class SpawnCommand implements TabExecutor {

    private static final Logger LOGGER = Logger.getLogger(SpawnCommand.class.getName());
    private static final String UNAVAILABLE = "Spawn isn't available right now.";

    private final PlayerCommandSupport support;
    private final TargetRankCheck rankCheck;
    private final VisibleTargetResolver targets;
    private final TeleportService teleportService;
    private final SpawnDestinationResolver spawn;
    private final Predicate<Player> isVanished;

    public SpawnCommand(PlayerCommandSupport support, TargetRankCheck rankCheck, VisibleTargetResolver targets,
                        TeleportService teleportService, SpawnDestinationResolver spawn, Predicate<Player> isVanished) {
        this.support = Objects.requireNonNull(support, "support must not be null");
        this.rankCheck = Objects.requireNonNull(rankCheck, "rankCheck must not be null");
        this.targets = Objects.requireNonNull(targets, "targets must not be null");
        this.teleportService = Objects.requireNonNull(teleportService, "teleportService must not be null");
        this.spawn = Objects.requireNonNull(spawn, "spawn must not be null");
        this.isVanished = Objects.requireNonNull(isVanished, "isVanished must not be null");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        StaffTeleportCommand.Parsed parsed = StaffTeleportCommand.Parsed.of(args);
        if (parsed.args().isEmpty() && !parsed.silent()) {
            self(sender);
        } else if (parsed.args().size() == 1) {
            other(sender, parsed.args().get(0), parsed.silent());
        } else {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /spawn | /spawn <player> [-s]");
        }
        return true;
    }

    /**
     * {@code player} to spawn exactly as {@code /spawn} does it (node, warmup, every engine guard) -
     * the teleport menu's Spawn tile (Phase 6). Main thread.
     */
    public void teleportSelf(Player player) {
        self(player);
    }

    private void self(CommandSender sender) {
        Player player = support.requirePlayer(sender);
        if (player == null) {
            sender.sendMessage(ChatColor.YELLOW + "Usage from the console: /spawn <player>");
            return;
        }
        support.whenAllowed(sender, TeleportNodes.SPAWN, () -> withSpawn(sender, (destination, point) ->
            teleportService.start(TeleportPlan.spawn(player, destination, point.label()))
                .thenAccept(outcome -> reportOwn(player, outcome))));
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
        support.whenAllowed(sender, TeleportNodes.STAFF_OTHERS, () -> withSilent(sender, silentRequested, silent ->
            rankCheck.whenOutranks(sender, target.getName(), summary -> withSpawn(sender, (destination, point) -> {
                TeleportPlan plan = TeleportPlan.staffToLocation(sender, target, destination, point.label(), silent);
                teleportService.start(plan).thenAccept(outcome -> StaffTeleportCommand.report(sender, plan, outcome, () -> {
                    sender.sendMessage(ChatColor.GREEN + "Sent " + target.getName() + " to spawn.");
                    if (!silent && target.isOnline()) {
                        target.sendMessage(ChatColor.GRAY + sender.getName() + " sent you to spawn.");
                    }
                }));
            }))));
    }

    /**
     * Resolve the spawn (async - it may read the API) and hand its Location to {@code next} on the main
     * thread; tells the sender when there is none.
     */
    private void withSpawn(CommandSender sender, BiConsumer<Location, SpawnPoint> next) {
        CompletableFuture<SpawnPoint> resolved;
        try {
            resolved = spawn.resolve();
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, "[KnK Teleport] Could not resolve the spawn", ex);
            sender.sendMessage(ChatColor.RED + UNAVAILABLE);
            return;
        }
        resolved.whenComplete((point, ex) -> support.mainThread().execute(() -> {
            if (ex != null || point == null) {
                LOGGER.log(Level.WARNING, "[KnK Teleport] Could not resolve the spawn", ex);
                sender.sendMessage(ChatColor.RED + UNAVAILABLE);
                return;
            }
            Location destination = spawn.toLocation(point);
            if (destination == null || destination.getWorld() == null) {
                sender.sendMessage(ChatColor.RED + UNAVAILABLE);
                return;
            }
            next.accept(destination, point);
        }));
    }

    /** Tell a player how their own {@code /spawn} ended; the engine already explained a cancelled warmup. */
    private static void reportOwn(Player player, TeleportOutcome outcome) {
        if (!player.isOnline()) {
            return;
        }
        switch (outcome.status()) {
            case TELEPORTED -> player.sendMessage(ChatColor.GREEN + "Teleported to spawn.");
            case CANCELLED -> { }
            case DENIED, FAILED -> player.sendMessage(ChatColor.RED
                + (outcome.message() != null ? outcome.message() : "The teleport didn't happen."));
        }
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
        if (args.length == 1) {
            return targets.complete(sender, args[0]);
        }
        if (args.length == 2 && "-s".startsWith(args[1].toLowerCase(Locale.ROOT))) {
            return List.of("-s");
        }
        return Collections.emptyList();
    }
}
