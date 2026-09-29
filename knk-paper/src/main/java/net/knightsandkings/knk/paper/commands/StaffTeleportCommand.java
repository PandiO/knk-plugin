package net.knightsandkings.knk.paper.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport;
import net.knightsandkings.knk.paper.commands.support.TargetRankCheck;
import net.knightsandkings.knk.paper.teleport.TeleportNodes;
import net.knightsandkings.knk.paper.teleport.TeleportPlan;
import net.knightsandkings.knk.paper.teleport.TeleportService;
import net.knightsandkings.knk.paper.teleport.VisibleTargetResolver;

/**
 * Staff teleports (docs/specs/teleport/DESIGN.md §3.2, Phase 1; the plugin owns {@code /tp} and
 * {@code /tphere}, vanilla stays reachable as {@code /minecraft:tp}):
 * <ul>
 *   <li>{@code /tp <player>} - you to them ({@value TeleportNodes#STAFF}, or the older
 *       {@value TeleportNodes#LEGACY_ADMIN_TP}; also reachable as {@code /knk tp <player>}).</li>
 *   <li>{@code /tp <player> <target>} - one player to another ({@value TeleportNodes#STAFF_OTHERS};
 *       you must outrank both). Console allowed.</li>
 *   <li>{@code /tp <x> <y> <z> [world] [yaw pitch]} - you to coordinates, {@code ~} relative
 *       ({@value TeleportNodes#STAFF}).</li>
 *   <li>{@code /tphere <player>} - them to you ({@value TeleportNodes#STAFF_OTHERS}, rank-checked).</li>
 *   <li>{@code -s} (or v1's {@code silent}/{@code s} as the last argument) - no message to the moved
 *       or visited player ({@value TeleportNodes#STAFF_SILENT}). Always silent while you're vanished.</li>
 * </ul>
 * Every form is rank-checked against each other player involved ({@link TargetRankCheck}: your
 * highest group weight must be above theirs; the console and {@code knk.admin.user.manage.all}
 * pass), resolves names vanish-aware ({@link VisibleTargetResolver}), and runs through
 * {@link TeleportService} - instant, no cost/cooldown/safety check, but the freeze, region and siege
 * guards apply, and the teleport fires with cause {@code COMMAND}. Permissions resolve through
 * {@code KnkPermissible}, hence no {@code permission:} on the plugin.yml entries.
 */
public class StaffTeleportCommand implements TabExecutor {

    public enum Form { TP, TPHERE }

    private static final Set<String> SILENT_LAST_ARGS = Set.of("-s", "silent", "s");
    private static final double MAX_HORIZONTAL = 30_000_000;

    private final Form form;
    private final PlayerCommandSupport support;
    private final TargetRankCheck rankCheck;
    private final VisibleTargetResolver targets;
    private final TeleportService teleportService;
    private final Predicate<Player> isVanished;
    private final Function<String, World> worldByName;
    private final Supplier<List<String>> worldNames;

    public StaffTeleportCommand(Form form, PlayerCommandSupport support, TargetRankCheck rankCheck,
                                VisibleTargetResolver targets, TeleportService teleportService,
                                Predicate<Player> isVanished, Function<String, World> worldByName,
                                Supplier<List<String>> worldNames) {
        this.form = Objects.requireNonNull(form, "form must not be null");
        this.support = Objects.requireNonNull(support, "support must not be null");
        this.rankCheck = Objects.requireNonNull(rankCheck, "rankCheck must not be null");
        this.targets = Objects.requireNonNull(targets, "targets must not be null");
        this.teleportService = Objects.requireNonNull(teleportService, "teleportService must not be null");
        this.isVanished = Objects.requireNonNull(isVanished, "isVanished must not be null");
        this.worldByName = Objects.requireNonNull(worldByName, "worldByName must not be null");
        this.worldNames = Objects.requireNonNull(worldNames, "worldNames must not be null");
    }

    /** The same command in its other form ({@code /tp} ↔ {@code /tphere}), sharing everything else. */
    public StaffTeleportCommand withForm(Form other) {
        return new StaffTeleportCommand(other, support, rankCheck, targets, teleportService, isVanished, worldByName, worldNames);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Parsed parsed = Parsed.of(args);
        if (form == Form.TPHERE) {
            if (parsed.args().size() != 1) {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /tphere <player> [-s]");
                return true;
            }
            Player target = findVisible(sender, parsed.args().get(0));
            if (target != null) {
                here(sender, target, parsed.silent());
            }
            return true;
        }

        List<String> a = parsed.args();
        if (a.size() >= 3 && isCoordinate(a.get(0)) && isCoordinate(a.get(1)) && isCoordinate(a.get(2))) {
            selfToCoordinates(sender, a);
        } else if (a.size() == 1) {
            selfToPlayer(sender, a.get(0), parsed.silent(), false);
        } else if (a.size() == 2) {
            playerToPlayer(sender, a.get(0), a.get(1), parsed.silent());
        } else {
            sendTpUsage(sender);
        }
        return true;
    }

    /**
     * {@code /knk tp <player> [-s]} (kept from before this command existed). {@code /knk} has already
     * checked {@code knk.admin.tp}, so only the silent flag is still permission-checked here.
     */
    public boolean onKnkTp(CommandSender sender, String[] args) {
        Parsed parsed = Parsed.of(args);
        if (parsed.args().size() != 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk tp <player> [-s]");
            return true;
        }
        selfToPlayer(sender, parsed.args().get(0), parsed.silent(), true);
        return true;
    }

    // ----- forms -----

    private void selfToPlayer(CommandSender sender, String targetName, boolean silentRequested, boolean nodeChecked) {
        Player actor = support.requirePlayer(sender);
        if (actor == null) {
            sender.sendMessage(ChatColor.YELLOW + "Usage from the console: /tp <player> <target>");
            return;
        }
        Player visited = findVisible(sender, targetName);
        if (visited == null) {
            return;
        }
        if (visited.getUniqueId().equals(actor.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "You can't teleport to yourself.");
            return;
        }
        Runnable run = () -> withSilent(sender, silentRequested, silent -> rankChecked(sender, List.of(visited), () ->
            launch(sender, TeleportPlan.staffToPlayer(sender, actor, visited, silent), () -> {
                sender.sendMessage(ChatColor.GREEN + "Teleported to " + visited.getName() + ".");
                if (!silent) {
                    tell(visited, actor.getName() + " teleported to you.");
                }
            })));
        if (nodeChecked) {
            run.run();
        } else {
            support.whenAnyAllowed(sender, List.of(TeleportNodes.STAFF, TeleportNodes.LEGACY_ADMIN_TP), run);
        }
    }

    private void playerToPlayer(CommandSender sender, String subjectName, String targetName, boolean silentRequested) {
        Player subject = findVisible(sender, subjectName);
        if (subject == null) {
            return;
        }
        Player visited = findVisible(sender, targetName);
        if (visited == null) {
            return;
        }
        if (subject.getUniqueId().equals(visited.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "That's the same player twice.");
            return;
        }
        if (PlayerCommandSupport.isSelf(sender, subject)) {
            selfToPlayer(sender, visited.getName(), silentRequested, false);
            return;
        }
        if (PlayerCommandSupport.isSelf(sender, visited)) {
            here(sender, subject, silentRequested);
            return;
        }
        support.whenAllowed(sender, TeleportNodes.STAFF_OTHERS, () -> withSilent(sender, silentRequested, silent ->
            rankChecked(sender, List.of(subject, visited), () ->
                launch(sender, TeleportPlan.staffToPlayer(sender, subject, visited, silent), () -> {
                    sender.sendMessage(ChatColor.GREEN + "Teleported " + subject.getName() + " to " + visited.getName() + ".");
                    if (!silent) {
                        tell(subject, sender.getName() + " teleported you to " + visited.getName() + ".");
                        tell(visited, subject.getName() + " was teleported to you.");
                    }
                }))));
    }

    private void here(CommandSender sender, Player subject, boolean silentRequested) {
        Player actor = support.requirePlayer(sender);
        if (actor == null) {
            sender.sendMessage(ChatColor.YELLOW + "Usage from the console: /tp <player> <target>");
            return;
        }
        if (subject.getUniqueId().equals(actor.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "You can't teleport yourself to yourself.");
            return;
        }
        support.whenAllowed(sender, TeleportNodes.STAFF_OTHERS, () -> withSilent(sender, silentRequested, silent ->
            rankChecked(sender, List.of(subject), () ->
                launch(sender, TeleportPlan.staffToPlayer(sender, subject, actor, silent), () -> {
                    sender.sendMessage(ChatColor.GREEN + "Teleported " + subject.getName() + " to you.");
                    if (!silent) {
                        tell(subject, actor.getName() + " teleported you to them.");
                    }
                }))));
    }

    private void selfToCoordinates(CommandSender sender, List<String> a) {
        Player actor = support.requirePlayer(sender);
        if (actor == null) {
            return;
        }
        Location destination = parseDestination(sender, actor.getLocation(), a);
        if (destination == null) {
            return;
        }
        String label = String.format(Locale.ROOT, "%.1f, %.1f, %.1f in %s",
            destination.getX(), destination.getY(), destination.getZ(), destination.getWorld().getName());
        support.whenAllowed(sender, TeleportNodes.STAFF, () ->
            launch(sender, TeleportPlan.staffToLocation(sender, actor, destination, label), () ->
                sender.sendMessage(ChatColor.GREEN + "Teleported to " + label + ".")));
    }

    // ----- plumbing -----

    private void launch(CommandSender sender, TeleportPlan plan, Runnable onTeleported) {
        teleportService.start(plan).thenAccept(outcome -> report(sender, plan, outcome, onTeleported));
    }

    static void report(CommandSender sender, TeleportPlan plan, TeleportOutcome outcome, Runnable onTeleported) {
        if (outcome.isTeleported()) {
            onTeleported.run();
            return;
        }
        String reason = outcome.message() != null ? outcome.message() : "The teleport didn't happen.";
        if (plan.movesActor()) {
            sender.sendMessage(ChatColor.RED + reason);
        } else {
            sender.sendMessage(ChatColor.RED + "Can't teleport " + plan.subject().getName() + ": " + reason);
        }
    }

    /**
     * Decide whether the teleport is silent: always while the actor is vanished (DESIGN §3.4.4), else
     * only when asked for - and asking needs {@value TeleportNodes#STAFF_SILENT}.
     */
    private void withSilent(CommandSender sender, boolean requested, Consumer<Boolean> next) {
        if (sender instanceof Player player && isVanished.test(player)) {
            next.accept(true);
        } else if (requested) {
            support.whenAllowed(sender, TeleportNodes.STAFF_SILENT, () -> next.accept(true));
        } else {
            next.accept(false);
        }
    }

    /** Runs {@code onAllowed} once the sender outranks every player in {@code players} (themselves excepted). */
    private void rankChecked(CommandSender sender, List<Player> players, Runnable onAllowed) {
        if (players.isEmpty()) {
            onAllowed.run();
            return;
        }
        Player first = players.get(0);
        List<Player> rest = players.subList(1, players.size());
        if (PlayerCommandSupport.isSelf(sender, first)) {
            rankChecked(sender, rest, onAllowed);
            return;
        }
        rankCheck.whenOutranks(sender, first.getName(), summary -> rankChecked(sender, rest, onAllowed));
    }

    private Player findVisible(CommandSender sender, String name) {
        Player target = targets.find(sender, name);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "No online player named '" + name + "'.");
        }
        return target;
    }

    private static void tell(Player player, String message) {
        if (player.isOnline()) {
            player.sendMessage(ChatColor.GRAY + message);
        }
    }

    private void sendTpUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Usage: /tp <player> [-s] | /tp <player> <target> [-s] | /tp <x> <y> <z> [world] [yaw pitch]");
    }

    /** {@code x y z [world] [yaw pitch]} relative to {@code base}; null (after telling the sender) when invalid. */
    Location parseDestination(CommandSender sender, Location base, List<String> a) {
        World world = base.getWorld();
        int rest = a.size() - 3;
        String worldArg = rest == 1 || rest == 3 ? a.get(3) : null;
        if (rest < 0 || rest > 3) {
            sendTpUsage(sender);
            return null;
        }
        if (worldArg != null) {
            world = worldByName.apply(worldArg);
            if (world == null) {
                sender.sendMessage(ChatColor.RED + "Unknown world '" + worldArg + "'.");
                return null;
            }
        }
        Double x = coordinate(a.get(0), base.getX());
        Double y = coordinate(a.get(1), base.getY());
        Double z = coordinate(a.get(2), base.getZ());
        Double yaw = (double) base.getYaw();
        Double pitch = (double) base.getPitch();
        if (rest >= 2) {
            yaw = coordinate(a.get(rest == 2 ? 3 : 4), base.getYaw());
            pitch = coordinate(a.get(rest == 2 ? 4 : 5), base.getPitch());
        }
        if (x == null || y == null || z == null || yaw == null || pitch == null || world == null) {
            sendTpUsage(sender);
            return null;
        }
        if (Math.abs(x) > MAX_HORIZONTAL || Math.abs(z) > MAX_HORIZONTAL || Math.abs(y) > MAX_HORIZONTAL) {
            sender.sendMessage(ChatColor.RED + "Those coordinates are outside the world.");
            return null;
        }
        double clampedPitch = Math.max(-90, Math.min(90, pitch));
        return new Location(world, x, y, z, yaw.floatValue(), (float) clampedPitch);
    }

    static boolean isCoordinate(String arg) {
        return coordinate(arg, 0) != null;
    }

    /** A number, {@code ~} (= base) or {@code ~n} (= base + n); null when neither. */
    static Double coordinate(String arg, double base) {
        if (arg == null || arg.isEmpty()) {
            return null;
        }
        boolean relative = arg.startsWith("~");
        String number = relative ? arg.substring(1) : arg;
        if (relative && number.isEmpty()) {
            return base;
        }
        try {
            double value = Double.parseDouble(number);
            if (!Double.isFinite(value) || number.contains("x") || number.contains("X")
                    || number.endsWith("d") || number.endsWith("D") || number.endsWith("f") || number.endsWith("F")) {
                return null;
            }
            return relative ? base + value : value;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) {
            return Collections.emptyList();
        }
        String last = args[args.length - 1];
        if (form == Form.TPHERE) {
            return args.length == 1 ? targets.complete(sender, last) : prefixed(List.of("-s"), last);
        }
        if (args.length == 1) {
            return targets.complete(sender, last);
        }
        boolean coordinates = isCoordinate(args[0]);
        if (coordinates) {
            if (args.length <= 3) {
                return prefixed(List.of("~"), last);
            }
            return args.length == 4 ? prefixed(worldNames.get(), last) : Collections.emptyList();
        }
        if (args.length == 2) {
            return targets.complete(sender, last, "-s");
        }
        return args.length == 3 ? prefixed(List.of("-s"), last) : Collections.emptyList();
    }

    private static List<String> prefixed(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }

    /** The arguments with the silent flag taken out: {@code -s} anywhere, or {@code silent}/{@code s} last (v1). */
    record Parsed(List<String> args, boolean silent) {
        static Parsed of(String[] raw) {
            List<String> args = new ArrayList<>();
            boolean silent = false;
            for (int i = 0; i < raw.length; i++) {
                String arg = raw[i];
                boolean isLast = i == raw.length - 1;
                if (arg.equalsIgnoreCase("-s") || (isLast && i > 0 && SILENT_LAST_ARGS.contains(arg.toLowerCase(Locale.ROOT)))) {
                    silent = true;
                } else {
                    args.add(arg);
                }
            }
            return new Parsed(List.copyOf(args), silent);
        }
    }
}
