package net.knightsandkings.knk.paper.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport;
import net.knightsandkings.knk.paper.commands.support.TargetRankCheck;
import net.knightsandkings.knk.paper.teleport.TeleportCharges;
import net.knightsandkings.knk.paper.teleport.TeleportNodes;
import net.knightsandkings.knk.paper.teleport.TeleportPlan;
import net.knightsandkings.knk.paper.teleport.TeleportService;
import net.knightsandkings.knk.paper.teleport.VisibleTargetResolver;

/**
 * {@code /hub} and {@code /hub <player> [-s]} (KNG-188, hub DESIGN §12): to the hub spawn selected on the web-app Game
 * Settings page ({@code HubService.admissionSpawn()}), through the teleport engine like {@code /spawn}. The
 * {@code /hub return} button stays with {@code HubCommand}, which hands every other form to this class.
 * <ul>
 *   <li>{@code /hub} - you to the hub. Any player (no node). A {@code HUB} teleport: warmup, its own cooldown, combat
 *       tag, safe-spot check and every engine guard (siege, freeze, outage, domain access). The groups' spawn price
 *       and cooldown apply (KNG-41), {@value TeleportNodes#BYPASS_COST} makes it free.</li>
 *   <li>{@code /hub <player> [-s]} - send someone to the hub ({@value TeleportNodes#STAFF_OTHERS}; you must outrank
 *       them), like {@code /spawn <player>}. A staff teleport: instant, audited. Console allowed. {@code -s} needs
 *       {@value TeleportNodes#STAFF_SILENT}; always silent while you're vanished.</li>
 * </ul>
 * Without a usable hub (none selected, its world not loaded, its spawn unresolved) nobody is moved, and never to
 * another world instead (KNG-114).
 */
public class HubTeleportCommand {

    private static final Logger LOGGER = Logger.getLogger(HubTeleportCommand.class.getName());
    static final String UNAVAILABLE = "The hub isn't available right now.";
    private static final String LABEL = "the hub";

    private final PlayerCommandSupport support;
    private final TargetRankCheck rankCheck;
    private final VisibleTargetResolver targets;
    private final TeleportService teleportService;
    private final Supplier<Optional<Location>> hubSpawn;
    private final Supplier<Optional<String>> whyUnavailable;
    private final Predicate<Player> isVanished;
    private volatile Consumer<Player> settleInWorld = player -> { };
    private volatile TeleportCharges charges;

    /**
     * @param hubSpawn       the hub spawn now, empty while no hub can take players; read again when the warmup ends
     * @param whyUnavailable why there is no hub spawn, for staff ("the hub world 'knk_hub' is not loaded")
     */
    public HubTeleportCommand(PlayerCommandSupport support, TargetRankCheck rankCheck, VisibleTargetResolver targets,
                              TeleportService teleportService, Supplier<Optional<Location>> hubSpawn,
                              Supplier<Optional<String>> whyUnavailable, Predicate<Player> isVanished) {
        this.support = Objects.requireNonNull(support, "support must not be null");
        this.rankCheck = Objects.requireNonNull(rankCheck, "rankCheck must not be null");
        this.targets = Objects.requireNonNull(targets, "targets must not be null");
        this.teleportService = Objects.requireNonNull(teleportService, "teleportService must not be null");
        this.hubSpawn = Objects.requireNonNull(hubSpawn, "hubSpawn must not be null");
        this.whyUnavailable = Objects.requireNonNull(whyUnavailable, "whyUnavailable must not be null");
        this.isVanished = Objects.requireNonNull(isVanished, "isVanished must not be null");
    }

    /**
     * How a permission group's price is charged (KNG-41, the spawn price); null = {@code /hub} stays free (no API
     * client).
     */
    public void setCharges(TeleportCharges charges) {
        this.charges = charges;
    }

    /** What happens to a player the teleport brought into another world: its game mode, as for the other hub entries. */
    public void setSettleInWorld(Consumer<Player> settleInWorld) {
        this.settleInWorld = settleInWorld != null ? settleInWorld : player -> { };
    }

    /** {@code /hub [<player>] [-s]}; {@code args} never starts with {@code return}. */
    public void onCommand(CommandSender sender, String[] args) {
        StaffTeleportCommand.Parsed parsed = StaffTeleportCommand.Parsed.of(args);
        if (parsed.args().isEmpty() && !parsed.silent()) {
            self(sender);
        } else if (parsed.args().size() == 1) {
            other(sender, parsed.args().get(0), parsed.silent());
        } else {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /hub | /hub <player> [-s]");
        }
    }

    private void self(CommandSender sender) {
        Player player = support.requirePlayer(sender);
        if (player == null) {
            sender.sendMessage(ChatColor.YELLOW + "Usage from the console: /hub <player>");
            return;
        }
        Location destination = spawn();
        if (destination == null) {
            player.sendMessage(ChatColor.RED + UNAVAILABLE);
            return;
        }
        World from = player.getWorld();
        // Read again when the warmup ends: a hub that stopped taking players in the meantime fails the teleport.
        TeleportPlan plan = TeleportPlan.hub(player, this::spawn, LABEL);
        TeleportCharges fees = charges;
        if (fees == null) {
            teleportService.start(plan).thenAccept(outcome -> reportOwn(player, from, outcome));
            return;
        }
        // The groups' /spawn price (KNG-41); knk.teleport.bypass.cost makes it free.
        support.hasAsync(player, TeleportNodes.BYPASS_COST).thenAccept(bypassCost -> support.mainThread().execute(() -> {
            TeleportPlan priced = Boolean.TRUE.equals(bypassCost) ? plan : plan.withCharge(fees.hubFee(player));
            teleportService.start(priced).thenAccept(outcome -> reportOwn(player, from, outcome));
        }));
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
            rankCheck.whenOutranks(sender, target.getName(), summary -> {
                Location destination = spawn();
                if (destination == null) {
                    sender.sendMessage(ChatColor.RED + UNAVAILABLE
                        + whyUnavailable().map(why -> " (" + why + ")").orElse(""));
                    return;
                }
                World from = target.getWorld();
                TeleportPlan plan = TeleportPlan.staffToLocation(sender, target, destination, LABEL, silent);
                teleportService.start(plan).thenAccept(outcome -> StaffTeleportCommand.report(sender, plan, outcome, () -> {
                    settle(target, from);
                    sender.sendMessage(ChatColor.GREEN + "Sent " + target.getName() + " to the hub.");
                    if (!silent && target.isOnline()) {
                        target.sendMessage(ChatColor.GRAY + sender.getName() + " sent you to the hub.");
                    }
                }));
            })));
    }

    /** The hub spawn now, or null; never throws. */
    private Location spawn() {
        try {
            Location spawn = hubSpawn.get().orElse(null);
            return spawn != null && spawn.getWorld() != null ? spawn : null;
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, "[KnK Hub] Could not resolve the hub spawn", ex);
            return null;
        }
    }

    private Optional<String> whyUnavailable() {
        try {
            return whyUnavailable.get();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /** Tell a player how their own {@code /hub} ended; the engine already explained a cancelled warmup. */
    private void reportOwn(Player player, World from, TeleportOutcome outcome) {
        if (!player.isOnline()) {
            return;
        }
        switch (outcome.status()) {
            case TELEPORTED -> {
                settle(player, from);
                player.sendMessage(ChatColor.GREEN + "Teleported to the hub.");
            }
            case CANCELLED -> { }
            case DENIED, FAILED -> player.sendMessage(ChatColor.RED
                + (outcome.message() != null ? outcome.message() : "The teleport didn't happen."));
        }
    }

    private void settle(Player player, World from) {
        if (player.isOnline() && player.getWorld() != from) {
            settleInWorld.accept(player);
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

    /** The {@code <player>} form and its {@code -s}, only for staff holding their nodes (KNG-107, cached check). */
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (!support.holds(sender, TeleportNodes.STAFF_OTHERS)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return new ArrayList<>(targets.complete(sender, args[0]));
        }
        if (args.length == 2 && "-s".startsWith(args[1].toLowerCase(Locale.ROOT))
                && support.holds(sender, TeleportNodes.STAFF_SILENT)) {
            return List.of("-s");
        }
        return Collections.emptyList();
    }
}
