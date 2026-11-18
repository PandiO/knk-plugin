package net.knightsandkings.knk.paper.navigation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.navigation.compass.CompassCycle;
import net.knightsandkings.knk.core.navigation.compass.CompassSettings;
import net.knightsandkings.knk.core.navigation.compass.CompassUse;
import net.knightsandkings.knk.core.navigation.destinations.Candidate;
import net.knightsandkings.knk.core.navigation.destinations.DestinationEngine;
import net.knightsandkings.knk.core.navigation.destinations.DestinationList;
import net.knightsandkings.knk.core.navigation.destinations.InitialScopes;
import net.knightsandkings.knk.core.navigation.destinations.NavigationContext;
import net.knightsandkings.knk.core.navigation.engine.CompassTargets;
import net.knightsandkings.knk.core.navigation.engine.SiegeMatchProvider;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.siege.SiegeCompassTargets;
import net.knightsandkings.knk.core.siege.SiegeNavigationItem;
import net.knightsandkings.knk.core.siege.SiegeCompassTargets.Target;
import net.knightsandkings.knk.paper.navigation.NavigationDestinations.Located;
import net.knightsandkings.knk.paper.navigation.NavigationDestinations.Resolution;
import net.knightsandkings.knk.paper.navigation.engine.PlayerNavigationEngine;
import net.knightsandkings.knk.paper.siege.SiegeLobbyRuntime;
import net.knightsandkings.knk.paper.siege.SiegeMatch;
import net.knightsandkings.knk.paper.siege.SiegeMatchObserver;
import net.knightsandkings.knk.paper.siege.SiegeService;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * The navigation compass (KNG-234): a right-click cycles through the places that matter to the player and
 * navigates to the chosen one (the {@code /navigate} machinery, {@link NavigationService#navigate}); a
 * shift-click switches navigation off, and on again to the last target.
 *
 * <ul>
 *   <li><b>In a running siege match</b> the list is {@link SiegeCompassTargets}: the player's own objectives
 *       (defenders) or the ones to capture (attackers), objectives under attack first, then the team spawn.</li>
 *   <li><b>Elsewhere</b> it is the destinations the web app lists under the compass settings, in that order,
 *       when the compass is enabled outside sieges.</li>
 * </ul>
 *
 * Which item is a compass is not coded here: {@link #isCompass} asks the settings' material and display-name
 * rule, so any item an admin hands out with those properties works at once. Main thread only.
 */
public final class NavigationCompassService implements SiegeMatchObserver, MatchNavigationScope {

    /** Ignore a second click this soon after the last (a client can send one click twice). */
    static final long CLICK_COOLDOWN_MILLIS = 250;

    /** One line of the cycle: a stable key, what to call it, what to tell about it, where it leads. */
    private record Entry(String key, String label, String detail, Destination destination, NavTarget lookup,
                         NavigationDestinations.Mode mode) {
        static Entry ofDestination(String key, String label, String detail, Destination destination) {
            return new Entry(key, label, detail, destination, null, null);
        }

        static Entry ofLookup(String key, String label, NavTarget lookup, NavigationDestinations.Mode mode) {
            return new Entry(key, label, null, null, lookup, mode);
        }
    }

    /** What a player chose last, and in which situation (a different one starts the cycle afresh). */
    private record Last(String situation, String key, Destination destination) {
    }

    private final Supplier<CompassSettings> settings;
    private final Supplier<NavigationService> navigation;
    private final Supplier<NavigationDestinations> catalogue;
    private final Supplier<SiegeService> siege;
    private final BiPredicate<Player, String> knkPermission;
    private final Executor mainThread;
    private final java.util.function.LongSupplier clock;
    private final CompassItems items;

    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger(NavigationCompassService.class.getName());
    private final java.util.Set<String> loggedRounds = new java.util.HashSet<>();

    private final Map<UUID, Last> last = new HashMap<>();
    private SiegeGateApproaches gateApproaches = SiegeGateApproaches.NONE;
    private final Map<UUID, Long> lastClick = new HashMap<>();
    /**
     * Destination engine phase 3 (PandiO/knk-workspace#69): with {@code navigation.destination-engine} on, the
     * compass cycles the engine's list; null (the flag off, the default) keeps today's lists below unchanged.
     */
    private Supplier<PlayerNavigationEngine> engine = () -> null;
    /** With the engine: what a player chose last, as a candidate key (a resume resolves it again, § 2.1 item 7). */
    private record LastKey(String situation, String key, Candidate.Mode mode) {
    }

    private final Map<UUID, LastKey> lastKeys = new HashMap<>();

    public NavigationCompassService(Supplier<CompassSettings> settings, Supplier<NavigationService> navigation,
                                    Supplier<NavigationDestinations> catalogue, Supplier<SiegeService> siege,
                                    BiPredicate<Player, String> knkPermission, Executor mainThread,
                                    java.util.function.LongSupplier clock, CompassItems items) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
        this.siege = Objects.requireNonNull(siege, "siege");
        this.knkPermission = knkPermission;
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.items = Objects.requireNonNull(items, "items");
    }

    /**
     * PandiO/knk-workspace#176: an objective to capture behind a closed gate is navigated to the gate in the way, not to
     * its capture point (the gate cache is ready after this service is built).
     */
    public void setGateApproaches(SiegeGateApproaches gateApproaches) {
        this.gateApproaches = gateApproaches == null ? SiegeGateApproaches.NONE : gateApproaches;
    }

    /** The destination engine, read lazily; a supplier answering null means the flag is off. */
    public void setEngine(Supplier<PlayerNavigationEngine> engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    // ==================== the item ====================

    /** Whether {@code item} is a navigation compass under the current settings (material + display name). */
    public boolean isCompass(ItemStack item) {
        if (item == null) {
            return false;
        }
        return isCompass(item.getType().name(), displayNameOf(item));
    }

    /** As {@link #isCompass(ItemStack)} for an item's material name and display name (the display name may be null). */
    public boolean isCompass(String material, String displayName) {
        return settings.get().item().matches(material, displayName);
    }

    private static String displayNameOf(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return null;
        }
        return LegacyComponentSerializer.legacySection().serialize(meta.displayName());
    }

    // ==================== a click ====================

    /**
     * A click with a compass in hand. Returns false when the compass does nothing for this player right now
     * (so the caller leaves the event alone), true when it handled the click.
     */
    public boolean use(Player player, boolean sneaking) {
        NavigationService nav = navigation.get();
        NavigationDestinations destinations = catalogue.get();
        if (nav == null || destinations == null) {
            player.sendMessage(CompassMessages.unavailable());
            return true;
        }
        if (!hasNode(player)) {
            player.sendMessage(CompassMessages.noPermission());
            return true;
        }
        long now = clock.getAsLong();
        Long previous = lastClick.put(player.getUniqueId(), now);
        if (previous != null && now - previous < CLICK_COOLDOWN_MILLIS) {
            return true;
        }
        PlayerNavigationEngine destinationEngine = engine.get();
        if (destinationEngine != null) {
            useWithEngine(player, sneaking, nav, destinationEngine);
            return true;
        }

        CompassSettings config = settings.get();
        SiegeService siegeService = siege.get();
        SiegeMatch match = siegeService == null ? null : siegeService.runningMatchOf(player.getUniqueId()).orElse(null);
        String situation;
        List<Entry> entries;
        if (match != null) {
            if (!config.siegeEnabled()) {
                player.sendMessage(CompassMessages.disabledInSiege());
                return true;
            }
            situation = "siege:" + match.matchToken();
            entries = siegeEntries(player, match);
        } else if (siegeService != null && siegeService.lobbyOf(player.getUniqueId()).isPresent()) {
            player.sendMessage(CompassMessages.waitForMatch());
            return true;
        } else if (config.generalEnabled()) {
            situation = "general";
            entries = generalEntries(player, config, destinations);
        } else {
            player.sendMessage(CompassMessages.disabledHere());
            return true;
        }

        Last chosen = last.get(player.getUniqueId());
        if (chosen != null && !chosen.situation().equals(situation)) {
            last.remove(player.getUniqueId());
            chosen = null;
        }
        switch (CompassUse.onClick(sneaking, nav.isNavigating(player.getUniqueId()), chosen != null)) {
            case STOP -> {
                if (!nav.stop(player)) {
                    player.sendMessage(NavigationMessages.notNavigating());
                }
            }
            case RESUME -> nav.navigate(player, chosen.destination());
            case CYCLE -> cycle(player, nav, destinations, situation, entries, chosen);
        }
        return true;
    }

    private void cycle(Player player, NavigationService nav, NavigationDestinations destinations, String situation,
                       List<Entry> entries, Last chosen) {
        if (entries.isEmpty()) {
            player.sendMessage(CompassMessages.disabledHere());
            return;
        }
        List<String> keys = entries.stream().map(Entry::key).toList();
        int index = CompassCycle.next(keys, chosen == null ? null : chosen.key());
        Entry entry = entries.get(index);
        player.sendMessage(CompassMessages.selected(index + 1, entries.size(), entry.label(), entry.detail()));
        if (entry.destination() != null) {
            go(player, nav, situation, entry.key(), entry.destination());
            return;
        }
        destinations.locate(entry.lookup(), entry.mode(), player.getWorld().getName())
            .whenComplete((located, error) -> mainThread.execute(() -> {
                if (error != null || located == null || !located.ok()) {
                    player.sendMessage(located == null || located.failure() == null
                        ? NavigationMessages.unknownDestination(entry.label())
                        : failure(located, entry.label()));
                    return;
                }
                if (player.isOnline()) {
                    go(player, nav, situation, entry.key(), located.destination());
                }
            }));
    }

    private static net.kyori.adventure.text.Component failure(Located located, String name) {
        return switch (located.failure()) {
            case OTHER_WORLD -> NavigationMessages.otherWorld(name);
            case NO_LOCATION -> NavigationMessages.noLocation(name);
            case NOT_FOUND -> NavigationMessages.unknownDestination(name);
        };
    }

    private void go(Player player, NavigationService nav, String situation, String key, Destination destination) {
        last.put(player.getUniqueId(), new Last(situation, key, destination));
        nav.navigate(player, destination);
    }

    // ==================== the destination engine (phase 3, #69) ====================

    /** One line of the engine-backed cycle. */
    private record EngineEntry(String key, String label, String detail, Candidate.Mode mode) {
    }

    /**
     * A click through the engine (DESIGN § 5: the compass is a client): the list is the engine's, the compass keeps
     * the cursor (a candidate key now), the click rule and its own wording per reason code (E15). In a running match
     * it cycles the match's destinations; outside a siege the configured general targets as a filter of the World
     * list ({@link CompassTargets}); for the bypass (owner/staff mode, {@code knk.navigate.any}) the same as for
     * anyone in their situation, as today. A resume resolves the stored key again: a destination that left the list
     * is not resumed.
     */
    private void useWithEngine(Player player, boolean sneaking, NavigationService nav, PlayerNavigationEngine destinations) {
        NavigationContext ctx = destinations.context(player);
        DestinationList list = destinations.withFreshness(destinations.engine().listFor(ctx));
        if (list.refused()) {
            player.sendMessage(EngineReasons.forCompass(list.reason()));
            return;
        }
        String situation;
        List<EngineEntry> entries;
        boolean bypass = InitialScopes.BYPASS.equals(list.scopeId());
        if (InitialScopes.SIEGE_MATCH.equals(list.scopeId()) || (bypass && ctx.siege().matchRunning())) {
            if (!ctx.siege().compassEnabled()) {
                player.sendMessage(CompassMessages.disabledInSiege());
                return;
            }
            situation = "siege:" + matchTokenOf(player);
            entries = new ArrayList<>();
            for (Candidate candidate : list.candidates()) {
                if (SiegeMatchProvider.ID.equals(candidate.source())) {
                    entries.add(new EngineEntry(candidate.key(), candidate.label(), candidate.detail(), Candidate.Mode.DEFAULT));
                }
            }
        } else if (bypass && ctx.siege().lobbyMember()) {
            player.sendMessage(CompassMessages.waitForMatch());
            return;
        } else if (settings.get().generalEnabled()) {
            situation = "general";
            entries = new ArrayList<>();
            for (CompassTargets.Pick pick : CompassTargets.of(list, settings.get().generalTargets())) {
                entries.add(new EngineEntry(pick.candidate().key(), pick.candidate().label(), null, pick.mode()));
            }
        } else {
            player.sendMessage(CompassMessages.disabledHere());
            return;
        }

        LastKey chosen = lastKeys.get(player.getUniqueId());
        if (chosen != null && !chosen.situation().equals(situation)) {
            lastKeys.remove(player.getUniqueId());
            chosen = null;
        }
        switch (CompassUse.onClick(sneaking, nav.isNavigating(player.getUniqueId()), chosen != null)) {
            case STOP -> {
                if (!nav.stop(player)) {
                    player.sendMessage(NavigationMessages.notNavigating());
                }
            }
            case RESUME -> {
                DestinationEngine.Resolution again = destinations.resolveKey(player, chosen.key(), chosen.mode());
                if (again.found()) {
                    nav.navigate(player, again.destination());
                } else {
                    lastKeys.remove(player.getUniqueId());
                    player.sendMessage(CompassMessages.nothingToResume());
                }
            }
            case CYCLE -> cycleWithEngine(player, nav, destinations, situation, entries, list, chosen);
        }
    }

    private void cycleWithEngine(Player player, NavigationService nav, PlayerNavigationEngine destinations, String situation,
                                 List<EngineEntry> entries, DestinationList list, LastKey chosen) {
        if (entries.isEmpty()) {
            player.sendMessage(CompassMessages.disabledHere());
            if (list.degraded() && list.degradedText() != null) {
                player.sendMessage(NavigationMessages.info(list.degradedText()));
            }
            return;
        }
        List<String> keys = entries.stream().map(EngineEntry::key).toList();
        int index = CompassCycle.next(keys, chosen == null ? null : chosen.key());
        EngineEntry entry = entries.get(index);
        player.sendMessage(CompassMessages.selected(index + 1, entries.size(), entry.label(), entry.detail()));
        DestinationEngine.Resolution resolution = destinations.resolveKey(player, entry.key(), entry.mode());
        switch (resolution.outcome()) {
            case FOUND -> {
                lastKeys.put(player.getUniqueId(), new LastKey(situation, entry.key(), entry.mode()));
                nav.navigate(player, resolution.destination());
            }
            case NOT_ALLOWED -> player.sendMessage(EngineReasons.forCompass(resolution.reason()));
            case OTHER_WORLD -> player.sendMessage(NavigationMessages.otherWorld(entry.label()));
            case NO_LOCATION -> player.sendMessage(NavigationMessages.noLocation(entry.label()));
            case AMBIGUOUS, NOT_FOUND -> player.sendMessage(NavigationMessages.unknownDestination(entry.label()));
        }
    }

    private String matchTokenOf(Player player) {
        SiegeService siegeService = siege.get();
        return siegeService == null ? "?" : siegeService.runningMatchOf(player.getUniqueId()).map(SiegeMatch::matchToken).orElse("?");
    }

    // ==================== the lists ====================

    private List<Entry> siegeEntries(Player player, SiegeMatch match) {
        List<Entry> out = new ArrayList<>();
        for (MatchDestination d : siegeDestinations(player, match)) {
            out.add(Entry.ofDestination(d.key(), d.label(), d.detail(), d.destination()));
        }
        return out;
    }

    private List<MatchDestination> siegeDestinations(Player player, SiegeMatch match) {
        OptionalInt team = match.roster().teamOf(player.getUniqueId());
        if (team.isEmpty()) {
            return List.of();
        }
        List<MatchDestination> out = new ArrayList<>();
        KnkSiegeScenario scenario = match.scenario();
        Optional<KnkSiegeTeam> ownTeam = scenario.team(team.getAsInt());
        for (Target target : SiegeCompassTargets.forTeam(scenario, match.board(), match.alliances(), team.getAsInt())) {
            KnkLocation at = target.location();
            if (target.kind() == SiegeCompassTargets.Kind.CAPTURE && ownTeam.isPresent()) {
                // #176: a capture point behind a closed gate is reached through the gate - head for the gate
                Optional<KnkSiegeObjective> objective = objectiveOf(scenario, target.key());
                if (objective.isPresent()) {
                    at = gateApproaches.approach(objective.get(), ownTeam.get()).orElse(at);
                }
            }
            out.add(new MatchDestination(target.key(), target.label(), CompassMessages.siegeDetail(target),
                Destination.point(target.label(), at.world(), at.x(), at.y(), at.z())));
        }
        return out;
    }

    /** The objective a compass target's key ({@code objective:<id>}) names. */
    private static Optional<KnkSiegeObjective> objectiveOf(KnkSiegeScenario scenario, String key) {
        if (key == null || !key.startsWith("objective:")) {
            return Optional.empty();
        }
        try {
            int id = Integer.parseInt(key.substring("objective:".length()));
            return scenario.objectives().stream().filter(o -> o.id() == id).findFirst();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * In a running siege match: the objectives and the team spawn, in the compass's order - the only places
     * {@code /navigate} accepts and offers (KNG-234). Empty outside a match; an empty list when the compass is switched
     * off for sieges (nothing is relevant then).
     */
    @Override
    public Optional<List<MatchDestination>> destinationsOf(Player player) {
        SiegeService siegeService = siege.get();
        if (siegeService == null) {
            return Optional.empty();
        }
        Optional<SiegeMatch> match = siegeService.runningMatchOf(player.getUniqueId());
        if (match.isEmpty()) {
            return Optional.empty();
        }
        CompassSettings config = settings.get();
        if (!config.item().enabled() || !config.siegeEnabled()) {
            return Optional.of(List.of());
        }
        return Optional.of(siegeDestinations(player, match.get()));
    }

    private List<Entry> generalEntries(Player player, CompassSettings config, NavigationDestinations destinations) {
        String world = player.getWorld().getName();
        List<Entry> out = new ArrayList<>();
        for (String text : config.generalTargets()) {
            NavigateCommand.Parsed parsed = NavigateCommand.parse(text.split("\\s+"));
            Resolution resolution = destinations.resolve(parsed.destination(), world);
            if (resolution.found()) {
                out.add(Entry.ofLookup(text, resolution.target().name(), resolution.target(), parsed.mode()));
            }
        }
        return out;
    }

    // ==================== given by the siege (KNG-234) ====================

    /**
     * A siege member is at the hub, their inventory saved: give them the compass when lobby, scenario or the
     * global siege configuration (in that order of say) wants it and the compass works in sieges. The item goes
     * into the live inventory only - never the saved one - so the vault restore at the end of the match (or on
     * leaving, kicking, quitting) takes it away with everything else the match put there.
     */
    @Override
    public void memberReadiedAtHub(SiegeLobbyRuntime lobby, KnkSiegeScenario scenario, Player player) {
        CompassSettings config = settings.get();
        if (!config.item().enabled() || !config.siegeEnabled()) {
            return;
        }
        boolean global = lobby.machine().configuration().giveNavigationItem();
        boolean give = SiegeNavigationItem.resolve(lobby.lobby().navigationItem(), scenario.navigationItem(), global);
        logGrantDecision(lobby, scenario, global, give);
        if (!give || items.holds(player, config.item())) {
            return;
        }
        ItemStack item = items.create(config.item());
        if (item == null) {
            return;
        }
        String name = items.nameOf(config.item());
        player.sendMessage(items.give(player, item) ? CompassMessages.given(name) : CompassMessages.noRoom(name));
    }

    /**
     * One line per match (not per member) saying why the compass is or is not given at the hub, so "nobody got one" can be
     * traced from the console: the three levels, and the fact that the plugin holds the siege configuration of the round
     * (a web-app change reaches a lobby at its next cooldown or {@code /siege admin reload}, never mid-round).
     */
    private void logGrantDecision(SiegeLobbyRuntime lobby, KnkSiegeScenario scenario, boolean global, boolean give) {
        String round = lobby.key() + "#" + lobby.matchToken().orElse("?");
        if (!loggedRounds.add(round)) {
            return;
        }
        if (loggedRounds.size() > 64) {
            loggedRounds.clear();
            loggedRounds.add(round);
        }
        LOGGER.info("[Navigation] Siege lobby '" + lobby.key() + "', scenario '" + scenario.name() + "': navigation compass at the hub = "
            + (give ? "GIVE" : "no") + " (lobby=" + lobby.lobby().navigationItem() + ", scenario=" + scenario.navigationItem()
            + ", global=" + global + " as loaded for this round)");
    }

    // ==================== housekeeping ====================

    /** Forget a player's choice (quit). */
    public void forget(UUID playerId) {
        last.remove(playerId);
        lastClick.remove(playerId);
        lastKeys.remove(playerId);
    }

    private boolean hasNode(Player player) {
        return player.hasPermission(NavigateCommand.NODE) || (knkPermission != null && knkPermission.test(player, NavigateCommand.NODE));
    }
}
