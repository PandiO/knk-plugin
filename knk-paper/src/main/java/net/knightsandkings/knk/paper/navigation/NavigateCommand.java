package net.knightsandkings.knk.paper.navigation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.navigation.destinations.Candidate;
import net.knightsandkings.knk.core.navigation.destinations.DestinationEngine;
import net.knightsandkings.knk.core.navigation.destinations.InitialScopes;
import net.knightsandkings.knk.paper.navigation.MatchNavigationScope.MatchDestination;
import net.knightsandkings.knk.paper.navigation.NavigationDestinations.Located;
import net.knightsandkings.knk.paper.navigation.NavigationDestinations.Mode;
import net.knightsandkings.knk.paper.navigation.NavigationDestinations.Resolution;
import net.knightsandkings.knk.paper.navigation.engine.PlayerNavigationEngine;

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
    /** KNG-234: in a minigame match only the match's destinations are accepted and offered. */
    private Supplier<MatchNavigationScope> matchScope = () -> null;
    /**
     * Destination engine phase 3 (PandiO/knk-workspace#69): with {@code navigation.destination-engine} on, the
     * engine resolves and completes; null (the flag off, the default) keeps today's paths below unchanged.
     */
    private Supplier<PlayerNavigationEngine> engine = () -> null;

    public NavigateCommand(Supplier<NavigationService> service, Supplier<NavigationDestinations> destinations,
                           BiPredicate<Player, String> knkPermission, Executor mainThread) {
        this.service = Objects.requireNonNull(service, "service");
        this.destinations = Objects.requireNonNull(destinations, "destinations");
        this.knkPermission = knkPermission;
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
    }

    /** The rule for players in a minigame match (KNG-234); read lazily like the other services. */
    public void setMatchScope(Supplier<MatchNavigationScope> matchScope) {
        this.matchScope = Objects.requireNonNull(matchScope, "matchScope");
    }

    /** The destination engine, read lazily; a supplier answering null means the flag is off. */
    public void setEngine(Supplier<PlayerNavigationEngine> engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    private Optional<List<MatchDestination>> matchOf(Player player) {
        MatchNavigationScope scope = matchScope.get();
        return scope == null ? Optional.empty() : scope.destinationsOf(player);
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
        PlayerNavigationEngine destinationEngine = engine.get();
        if (destinationEngine != null) {
            navigateWithEngine(player, navigation, destinationEngine, args);
            return true;
        }
        Optional<List<MatchDestination>> inMatch = matchOf(player);
        if (inMatch.isPresent()) {
            navigateInMatch(player, navigation, String.join(" ", args).trim(), inMatch.get());
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

    /**
     * In a match only the match's destinations exist (KNG-234): the typed name must be one of them - exactly, or the
     * start of exactly one.
     */
    private void navigateInMatch(Player player, NavigationService navigation, String typed, List<MatchDestination> offered) {
        if (offered.isEmpty()) {
            player.sendMessage(CompassMessages.noMatchDestinations());
            return;
        }
        List<String> labels = offered.stream().map(MatchDestination::label).toList();
        String wanted = typed.toLowerCase(Locale.ROOT);
        List<MatchDestination> exact = offered.stream().filter(d -> d.label().toLowerCase(Locale.ROOT).equals(wanted)).toList();
        List<MatchDestination> found = !exact.isEmpty() ? exact
            : offered.stream().filter(d -> d.label().toLowerCase(Locale.ROOT).startsWith(wanted)).toList();
        if (found.isEmpty()) {
            player.sendMessage(CompassMessages.matchOnly(typed, labels));
        } else if (found.size() > 1 && exact.isEmpty()) {
            player.sendMessage(CompassMessages.matchAmbiguous(typed, found.stream().map(MatchDestination::label).toList()));
        } else {
            navigation.navigate(player, found.get(0).destination());
        }
    }

    // ==================== the destination engine (phase 3, #69) ====================

    /**
     * {@code /navigate <destination> [spawn|region]} through the engine: only the player's list exists, so the same
     * forms ({@code type:name}, {@code type:#id}, a bare name, the mode words) work in every scope - in a match too
     * (DESIGN § 3.1). The messages are today's: ambiguity choices, "unknown destination" with "did you mean", "is in
     * another world" (E11), "has no Location", the scope's reason in today's words (E15); in a match an unknown name
     * names the match's destinations and the start of exactly one name picks it, as today. The model's freshness
     * note follows a name not found (§ 7.4).
     */
    private void navigateWithEngine(Player player, NavigationService navigation, PlayerNavigationEngine destinations,
                                    String[] args) {
        Parsed parsed = parse(args);
        String typed = String.join(" ", args).trim();
        DestinationEngine.Resolution resolution = destinations.resolve(player, parsed.destination(), coreMode(parsed.mode()));
        if (!resolution.found() && parsed.mode() != Mode.DEFAULT) {
            // "Blue spawn" may be a whole name (a team spawn in a match): the whole text, as today's match path read it
            DestinationEngine.Resolution whole = destinations.resolve(player, typed, Candidate.Mode.DEFAULT);
            if (whole.found()) {
                resolution = whole;
            }
        }
        boolean inMatch = InitialScopes.SIEGE_MATCH.equals(resolution.list().scopeId());
        switch (resolution.outcome()) {
            case FOUND -> navigation.navigate(player, resolution.destination());
            case NOT_ALLOWED -> player.sendMessage(EngineReasons.forCommand(resolution.reason()));
            case AMBIGUOUS -> player.sendMessage(NavigationMessages.ambiguous(parsed.destination(), resolution.choiceNames()));
            case OTHER_WORLD -> player.sendMessage(NavigationMessages.otherWorld(resolution.candidate().label()));
            case NO_LOCATION -> player.sendMessage(NavigationMessages.noLocation(resolution.candidate().label()));
            case NOT_FOUND -> {
                if (inMatch) {
                    notFoundInMatch(player, navigation, destinations, typed, resolution);
                    return;
                }
                player.sendMessage(NavigationMessages.unknownDestination(parsed.destination()));
                if (!resolution.suggestions().isEmpty()) {
                    player.sendMessage(NavigationMessages.didYouMean(resolution.suggestionNames()));
                }
                destinations.degradedNote().ifPresent(note -> player.sendMessage(NavigationMessages.info(note)));
            }
        }
    }

    /** In a match: the start of exactly one name picks it; several are a choice; none names the match's list. */
    private static void notFoundInMatch(Player player, NavigationService navigation, PlayerNavigationEngine destinations,
                                        String typed, DestinationEngine.Resolution resolution) {
        List<Candidate> starting = DestinationEngine.NAMES.suggestions(resolution.list().candidates(), typed);
        if (starting.size() == 1) {
            DestinationEngine.Resolution picked = destinations.resolveKey(player, starting.get(0).key(), Candidate.Mode.DEFAULT);
            if (picked.found()) {
                navigation.navigate(player, picked.destination());
                return;
            }
        }
        if (starting.size() > 1) {
            player.sendMessage(CompassMessages.matchAmbiguous(typed, starting.stream().map(Candidate::label).toList()));
            return;
        }
        player.sendMessage(CompassMessages.matchOnly(typed, resolution.list().candidates().stream().map(Candidate::label).toList()));
    }

    /** Tab completion through the engine: {@code stop}, the list's names, then the mode words outside a match. */
    private static List<String> completeWithEngine(Player player, PlayerNavigationEngine destinations, String[] args) {
        List<String> words = Arrays.asList(args);
        String current = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (args.length == 1 && "stop".startsWith(current)) {
            out.add("stop");
        }
        for (String word : destinations.complete(player, words)) {
            if (!out.contains(word)) {
                out.add(word);
            }
        }
        if (args.length >= 2 && destinations.isComplete(player, words.subList(0, args.length - 1))
            && !InitialScopes.SIEGE_MATCH.equals(destinations.list(player).scopeId())) {
            for (String mode : MODES) {
                if (mode.startsWith(current) && !out.contains(mode)) {
                    out.add(mode);
                }
            }
        }
        return out;
    }

    /** The command's mode word as the engine's. */
    static Candidate.Mode coreMode(Mode mode) {
        return switch (mode == null ? Mode.DEFAULT : mode) {
            case SPAWN -> Candidate.Mode.SPAWN;
            case REGION -> Candidate.Mode.REGION;
            case DEFAULT -> Candidate.Mode.DEFAULT;
        };
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
        PlayerNavigationEngine destinationEngine = engine.get();
        if (destinationEngine != null) {
            return completeWithEngine(player, destinationEngine, args);
        }
        Optional<List<MatchDestination>> inMatch = matchOf(player);
        if (inMatch.isPresent()) {
            return completeInMatch(args, inMatch.get());
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

    /**
     * Tab completion in a match: only the match's destinations (and {@code stop}). The server replaces the last word,
     * so a multi-word label is offered from the word being typed on.
     */
    static List<String> completeInMatch(String[] args, List<MatchDestination> offered) {
        String typed = String.join(" ", args).toLowerCase(Locale.ROOT);
        int wordIndex = args.length - 1;
        List<String> out = new ArrayList<>();
        if (args.length == 1 && "stop".startsWith(typed)) {
            out.add("stop");
        }
        for (MatchDestination d : offered) {
            if (d.label().toLowerCase(Locale.ROOT).startsWith(typed)) {
                String[] words = d.label().split(" ");
                if (wordIndex < words.length) {
                    String rest = String.join(" ", Arrays.copyOfRange(words, wordIndex, words.length));
                    if (!out.contains(rest)) {
                        out.add(rest);
                    }
                }
            }
        }
        return out;
    }
}
