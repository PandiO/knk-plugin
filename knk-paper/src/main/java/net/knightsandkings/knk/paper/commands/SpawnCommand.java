package net.knightsandkings.knk.paper.commands;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
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
import net.knightsandkings.knk.paper.teleport.TeleportCharges;
import net.knightsandkings.knk.paper.teleport.TeleportExemptions;
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
 *       your rank or lower since PandiO/knk-workspace#171). A staff teleport: instant, audited. Console allowed. {@code -s} needs
 *       {@value TeleportNodes#STAFF_SILENT}; always silent while you're vanished. Same rank or lower, and not a
 *       player exempt from it ({@code knk.teleport.exempt.spawn}, staff/owner mode) unless you override
 *       (PandiO/knk-workspace#171).</li>
 *   <li>{@code /spawn all [-s]} - every eligible player to their spawn ({@code knk.teleport.all.spawn}, #171).</li>
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
    /** A player's own spawn (their group's Game Settings override, KNG-52), or null for the server spawn. */
    private volatile Function<Player, SpawnPoint> playerSpawn = player -> null;
    private volatile TeleportCharges charges;
    /** Exempt players and {@code /spawn all} (#171); null = nobody exempt, no {@code all}. */
    private volatile TeleportExemptions exemptions;

    public SpawnCommand(PlayerCommandSupport support, TargetRankCheck rankCheck, VisibleTargetResolver targets,
                        TeleportService teleportService, SpawnDestinationResolver spawn, Predicate<Player> isVanished) {
        this.support = Objects.requireNonNull(support, "support must not be null");
        this.rankCheck = Objects.requireNonNull(rankCheck, "rankCheck must not be null");
        this.targets = Objects.requireNonNull(targets, "targets must not be null");
        this.teleportService = Objects.requireNonNull(teleportService, "teleportService must not be null");
        this.spawn = Objects.requireNonNull(spawn, "spawn must not be null");
        this.isVanished = Objects.requireNonNull(isVanished, "isVanished must not be null");
    }

    /**
     * Where {@code /spawn} takes a particular player when it isn't the server spawn: their group's
     * spawn override from the Game Settings page (docs/specs/game-settings/DESIGN.md §3.8). Null
     * answers mean the server spawn.
     */
    public void setPlayerSpawn(Function<Player, SpawnPoint> playerSpawn) {
        this.playerSpawn = playerSpawn != null ? playerSpawn : player -> null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        StaffTeleportCommand.Parsed parsed = StaffTeleportCommand.Parsed.of(args);
        if (parsed.args().isEmpty() && !parsed.silent()) {
            self(sender);
        } else if (parsed.args().size() == 1 && exemptions != null
                && TeleportExemptions.ALL_ARGUMENT.equalsIgnoreCase(parsed.args().get(0))) {
            all(sender, parsed.silent());
        } else if (parsed.args().size() == 1) {
            other(sender, parsed.args().get(0), parsed.silent());
        } else {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /spawn | /spawn <player> [-s]" + (exemptions != null ? " | /spawn all [-s]" : ""));
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
        support.whenAllowed(sender, TeleportNodes.SPAWN, () -> withSpawn(sender, player, (destination, point) -> {
            TeleportCharges fees = charges;
            if (fees == null) {
                teleportService.start(TeleportPlan.spawn(player, destination, point.label()))
                    .thenAccept(outcome -> reportOwn(player, outcome));
                return;
            }
            // A permission group may price /spawn (Linear KNG-41); knk.teleport.bypass.cost makes it free.
            support.hasAsync(player, TeleportNodes.BYPASS_COST).thenAccept(bypassCost -> support.mainThread().execute(() -> {
                TeleportPlan plan = TeleportPlan.spawn(player, destination, point.label());
                if (!Boolean.TRUE.equals(bypassCost)) {
                    // The engine tells the player the price during the warmup.
                    plan = plan.withCharge(fees.spawnFee(player));
                }
                teleportService.start(plan).thenAccept(outcome -> reportOwn(player, outcome));
            }));
        }));
    }

    /**
     * How a permission group's {@code /spawn} price is charged (Linear KNG-41); null = {@code /spawn}
     * stays free (no API client).
     */
    public void setCharges(TeleportCharges charges) {
        this.charges = charges;
    }

    /** Exempt players and {@code /spawn all} (PandiO/knk-workspace#171). */
    public void setExemptions(TeleportExemptions exemptions) {
        this.exemptions = exemptions;
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
            rankCheck.whenOutranks(sender, target.getName(), summary -> notExempt(sender, target, () -> withSpawn(sender, target, (destination, point) -> {
                TeleportPlan plan = TeleportPlan.staffToLocation(sender, target, destination, point.label(), silent);
                teleportService.start(plan).thenAccept(outcome -> StaffTeleportCommand.report(sender, plan, outcome, () -> {
                    sender.sendMessage(ChatColor.GREEN + "Sent " + target.getName() + " to spawn.");
                    if (!silent && target.isOnline()) {
                        target.sendMessage(ChatColor.GRAY + sender.getName() + " sent you to spawn.");
                    }
                }));
            })))));
    }

    /**
     * {@code /spawn all}: every eligible player to their own spawn (a group spawn override, else the server spawn),
     * PandiO/knk-workspace#171.
     */
    private void all(CommandSender sender, boolean silentRequested) {
        TeleportExemptions rules = exemptions;
        support.whenAllowed(sender, TeleportExemptions.Type.SPAWN.allNode(), () -> withSilent(sender, silentRequested, silent ->
            rules.forAll(sender, TeleportExemptions.Type.SPAWN, Set.of(), players ->
                TeleportExemptions.runAll(sender, players, "to spawn", target -> {
                    CompletableFuture<TeleportOutcome> done = new CompletableFuture<>();
                    // withSpawn tells the sender itself when there is no spawn; that player then counts as not moved.
                    withSpawn(sender, target, (destination, point) -> {
                        TeleportPlan plan = TeleportPlan.staffToLocation(sender, target, destination, point.label(), silent);
                        teleportService.start(plan).whenComplete((outcome, ex) -> {
                            if (outcome != null && outcome.isTeleported() && !silent && target.isOnline()) {
                                target.sendMessage(ChatColor.GRAY + sender.getName() + " sent you to spawn.");
                            }
                            done.complete(outcome != null ? outcome : TeleportOutcome.failed(UNAVAILABLE));
                        });
                    }, () -> done.complete(TeleportOutcome.failed(UNAVAILABLE)));
                    return done;
                }))));
    }

    private void notExempt(CommandSender sender, Player target, Runnable next) {
        TeleportExemptions rules = exemptions;
        if (rules == null) {
            next.run();
        } else {
            rules.whenNotExempt(sender, target, TeleportExemptions.Type.SPAWN, next);
        }
    }

    /**
     * Resolve the spawn of {@code traveller} (async - it may read the API) and hand its Location to
     * {@code next} on the main thread; tells the sender when there is none. A group spawn override
     * is already resolved and used as is.
     */
    private void withSpawn(CommandSender sender, Player traveller, BiConsumer<Location, SpawnPoint> next) {
        withSpawn(sender, traveller, next, () -> { });
    }

    /** {@link #withSpawn(CommandSender, Player, BiConsumer)}; {@code onUnavailable} runs after the sender was told. */
    private void withSpawn(CommandSender sender, Player traveller, BiConsumer<Location, SpawnPoint> next, Runnable onUnavailable) {
        SpawnPoint own;
        try {
            own = playerSpawn.apply(traveller);
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, "[KnK Teleport] Could not look up the group spawn of " + traveller.getName(), ex);
            own = null;
        }
        if (own != null) {
            Location destination = spawn.toLocation(own);
            if (destination != null && destination.getWorld() != null) {
                next.accept(destination, own);
                return;
            }
        }
        CompletableFuture<SpawnPoint> resolved;
        try {
            resolved = spawn.resolve();
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, "[KnK Teleport] Could not resolve the spawn", ex);
            sender.sendMessage(ChatColor.RED + UNAVAILABLE);
            onUnavailable.run();
            return;
        }
        resolved.whenComplete((point, ex) -> support.mainThread().execute(() -> {
            if (ex != null || point == null) {
                LOGGER.log(Level.WARNING, "[KnK Teleport] Could not resolve the spawn", ex);
                sender.sendMessage(ChatColor.RED + UNAVAILABLE);
                onUnavailable.run();
                return;
            }
            Location destination = spawn.toLocation(point);
            if (destination == null || destination.getWorld() == null) {
                sender.sendMessage(ChatColor.RED + UNAVAILABLE);
                onUnavailable.run();
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
        // KNG-107: the <player> form and its -s only for staff holding their nodes (cached check).
        TeleportExemptions rules = exemptions;
        boolean all = rules != null && args.length >= 1 && rules.offersAll(sender, TeleportExemptions.Type.SPAWN, args[0]);
        if (!support.holds(sender, TeleportNodes.STAFF_OTHERS)) {
            if (args.length == 1 && all) {
                return new ArrayList<>(List.of(TeleportExemptions.ALL_ARGUMENT));
            }
            if (args.length == 2 && rules != null && TeleportExemptions.ALL_ARGUMENT.equalsIgnoreCase(args[0])
                    && support.holds(sender, TeleportExemptions.Type.SPAWN.allNode())
                    && "-s".startsWith(args[1].toLowerCase(Locale.ROOT)) && support.holds(sender, TeleportNodes.STAFF_SILENT)) {
                return List.of("-s");
            }
            return Collections.emptyList();
        }
        if (args.length == 1) {
            List<String> options = new ArrayList<>(targets.complete(sender, args[0]));
            if (all) {
                options.add(TeleportExemptions.ALL_ARGUMENT);
            }
            return options;
        }
        if (args.length == 2 && "-s".startsWith(args[1].toLowerCase(Locale.ROOT))
                && support.holds(sender, TeleportNodes.STAFF_SILENT)) {
            return List.of("-s");
        }
        return Collections.emptyList();
    }
}
