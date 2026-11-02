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
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.siege.SiegeCompassTargets;
import net.knightsandkings.knk.core.siege.SiegeNavigationItem;
import net.knightsandkings.knk.core.siege.SiegeCompassTargets.Target;
import net.knightsandkings.knk.paper.navigation.NavigationDestinations.Located;
import net.knightsandkings.knk.paper.navigation.NavigationDestinations.Resolution;
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
    }

    private boolean hasNode(Player player) {
        return player.hasPermission(NavigateCommand.NODE) || (knkPermission != null && knkPermission.test(player, NavigateCommand.NODE));
    }
}
