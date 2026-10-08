package net.knightsandkings.knk.paper.navigation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.paper.navigation.NavigationDestinations.Located;
import net.knightsandkings.knk.paper.navigation.NavigationDestinations.Mode;
import net.knightsandkings.knk.paper.navigation.NavigationDestinations.Resolution;

/**
 * {@code /navigate <destination> [spawn|region]}, {@code /navigate stop}, {@code /navigate}
 * (DESIGN §6.1; alias {@code /nav} in plugin.yml). Node {@code knk.navigate} (default true),
 * checked like {@code DiscoveryAdminCommand.hasNode} (R15): Bukkit's permissible or KnkPermissible's
 * cache. Parsing and resolution only - {@link NavigationService} does the rest. The services are
 * read lazily: they exist only when {@code navigation.enabled} and the road cache started.
 */
public class NavigateCommand implements TabExecutor {

    public static final String NODE = "knk.navigate";
    private static final List<String> MODES = List.of("spawn", "region");

    private final Supplier<NavigationService> service;
    private final Supplier<NavigationDestinations> destinations;
    private final BiPredicate<Player, String> knkPermission;
    private final Executor mainThread;

    public NavigateCommand(Supplier<NavigationService> service, Supplier<NavigationDestinations> destinations,
                           BiPredicate<Player, String> knkPermission, Executor mainThread) {
        this.service = Objects.requireNonNull(service, "service");
        this.destinations = Objects.requireNonNull(destinations, "destinations");
        this.knkPermission = knkPermission;
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(NavigationMessages.playersOnly());
            return true;
        }
        if (!hasNode(player)) {
            player.sendMessage(NavigationMessages.noPermission());
            return true;
        }
        NavigationService navigation = service.get();
        NavigationDestinations catalogue = destinations.get();
        if (navigation == null || catalogue == null) {
            player.sendMessage(NavigationMessages.disabled());
            return true;
        }
        if (args.length == 0) {
            navigation.status(player);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("stop")) {
            if (!navigation.stop(player)) {
                player.sendMessage(NavigationMessages.notNavigating());
            }
            return true;
        }
        Parsed parsed = parse(args);
        Resolution resolution = catalogue.resolve(parsed.destination(), player.getWorld().getName());
        if (resolution.ambiguous()) {
            player.sendMessage(NavigationMessages.ambiguous(parsed.destination(), resolution.choiceNames()));
            return true;
        }
        if (!resolution.found()) {
            player.sendMessage(NavigationMessages.unknownDestination(parsed.destination()));
            if (!resolution.suggestions().isEmpty()) {
                player.sendMessage(NavigationMessages.didYouMean(resolution.suggestionNames()));
            }
            return true;
        }
        NavTarget target = resolution.target();
        String world = player.getWorld().getName();
        catalogue.locate(target, parsed.mode(), world).whenComplete((located, ex) -> mainThread.execute(() -> {
            if (ex != null || located == null) {
                player.sendMessage(NavigationMessages.unknownDestination(parsed.destination()));
                return;
            }
            if (!located.ok()) {
                player.sendMessage(switch (located.failure()) {
                    case OTHER_WORLD -> NavigationMessages.otherWorld(target.name());
                    case NO_LOCATION -> NavigationMessages.noLocation(target.name());
                    case NOT_FOUND -> NavigationMessages.unknownDestination(parsed.destination());
                });
                return;
            }
            navigation.navigate(player, located.destination());
        }));
        return true;
    }

    /** The destination words and the trailing {@code spawn} / {@code region}, if any. */
    record Parsed(String destination, Mode mode) {
    }

    static Parsed parse(String[] args) {
        List<String> words = new ArrayList<>(Arrays.asList(args));
        Mode mode = Mode.DEFAULT;
        if (words.size() >= 2) {
            Mode trailing = Mode.parse(words.get(words.size() - 1)).orElse(null);
            if (trailing != null) {
                mode = trailing;
                words.remove(words.size() - 1);
            }
        }
        return new Parsed(String.join(" ", words).trim(), mode);
    }

    boolean hasNode(Player player) {
        return player.hasPermission(NODE) || (knkPermission != null && knkPermission.test(player, NODE));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !hasNode(player) || args.length == 0) {
            return List.of();
        }
        NavigationDestinations catalogue = destinations.get();
        if (catalogue == null) {
            return List.of();
        }
        String world = player.getWorld().getName();
        List<String> words = Arrays.asList(args);
        String current = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (args.length == 1 && "stop".startsWith(current)) {
            out.add("stop");
        }
        out.addAll(catalogue.complete(words, world));
        if (args.length >= 2 && catalogue.isComplete(words.subList(0, args.length - 1), world)) {
            for (String mode : MODES) {
                if (mode.startsWith(current) && !out.contains(mode)) {
                    out.add(mode);
                }
            }
        }
        return out;
    }
}
