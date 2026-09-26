package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.dataaccess.FetchResult;
import net.knightsandkings.knk.core.dataaccess.SiegeDataAccess;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeLobby;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.Completion;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.ObjectiveResult;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.Participant;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.ParticipantResult;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.ParticipantReward;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.RewardSummary;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeRuntimeConfig;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeSpawnpoint;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;
import net.knightsandkings.knk.core.domain.siege.SiegeLobbyMode;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.SiegeMatchesCommandApi;
import net.knightsandkings.knk.core.ports.api.TitleBracketsQueryApi;
import net.knightsandkings.knk.core.siege.AllianceResolver;
import net.knightsandkings.knk.core.siege.ObjectiveState;
import net.knightsandkings.knk.core.siege.SiegeCombatRules;
import net.knightsandkings.knk.core.siege.SiegeCommandFilter;
import net.knightsandkings.knk.core.siege.ObjectiveState.CaptureEvent;
import net.knightsandkings.knk.core.siege.ObjectiveState.Presence;
import net.knightsandkings.knk.core.siege.SiegeDisplayText;
import net.knightsandkings.knk.core.siege.SiegeEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.AnnounceEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.CancelMatchmakingEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.CancelReason;
import net.knightsandkings.knk.core.siege.SiegeEffect.DrawScenarioEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.EndMatchEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.LobbyDisabledEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.MatchmakingStartedEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.PhaseChangedEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.RefreshConfigEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.SendToHubEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.SplitTeamsEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.StartMatchEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.StartMessageEffect;
import net.knightsandkings.knk.core.siege.SiegeLobbyStateMachine;
import net.knightsandkings.knk.core.siege.SiegeLobbyStateMachine.SkipOutcome;
import net.knightsandkings.knk.core.siege.SiegeLobbyStateMachine.SkipRequester;
import net.knightsandkings.knk.core.siege.SiegeLobbyStateMachine.SkipResult;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster.MemberView;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster.SpawnKind;
import net.knightsandkings.knk.core.siege.SiegeObjectiveBoard.BoardStep;
import net.knightsandkings.knk.core.siege.SiegePhase;
import net.knightsandkings.knk.core.siege.SiegeRuntimeLocks;
import net.knightsandkings.knk.core.siege.SiegeSpawnOptions;
import net.knightsandkings.knk.core.siege.SiegeSpawnOptions.SpawnOption;
import net.knightsandkings.knk.core.siege.SiegeTitleRanks;
import net.knightsandkings.knk.core.siege.TeamPartitioner;
import net.knightsandkings.knk.core.siege.VoteTally;
import net.knightsandkings.knk.core.siege.VoteTally.VoteChoice;
import net.knightsandkings.knk.core.siege.VoteTally.VoteResult;
import net.knightsandkings.knk.core.siege.WinResolver;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;

/**
 * The siege runtime (DESIGN §5, §6; siege IMPLEMENTATION_PLAN Phase 5): owns one
 * {@link SiegeLobbyRuntime} per enabled {@code Continuous} lobby and drives them all from <b>one
 * synchronous 1 s ticker</b>. Each tick calls {@link SiegeLobbyStateMachine#tick} and applies the
 * returned {@link SiegeEffect}s in order, then runs the capture step of every running match.
 * HTTP never blocks the main thread: the runtime config, title brackets and the Phase 6 match API
 * are {@link CompletableFuture}s whose consequences hop back with {@code runTask}.
 * <p>
 * Every player-facing operation ({@link #join}, {@link #leave}, {@link #vote}, {@link #spawn},
 * {@link #skip}, admin start/stop/skip/kick/reload) is one public method returning a {@link Reply};
 * {@code SiegeCommand} calls them now and the Phase 8b menu actions call the same methods.
 * Main thread only.
 */
public final class SiegeService {

    /** The outcome of a player or admin operation: whether it did something, and what to tell them. */
    public record Reply(boolean ok, Component message) {
        static Reply ok(Component message) {
            return new Reply(true, message);
        }

        static Reply fail(Component message) {
            return new Reply(false, message);
        }
    }

    /**
     * Phase 8b: how the service opens the siege menus without depending on the menu engine
     * ({@code SiegeMenuBridge} implements it once the menu service exists). Each returns false when
     * the menu isn't available (not seeded / blocked by validation) - callers then use the chat fallback.
     */
    public interface MenuHooks {
        boolean openOverview(Player player);

        boolean openInformation(Player player, int lobbyId);

        boolean openSpawnPicker(Player player);
    }

    public static final String PERMISSION_PLAY = "knk.siege.play";
    public static final String PERMISSION_SKIP = "knk.siege.skip";
    public static final String PERMISSION_ADMIN_LIST = "knk.siege.admin.list";
    public static final String PERMISSION_ADMIN_CONTROL = "knk.siege.admin.control";
    public static final String PERMISSION_ADMIN_RELOAD = "knk.siege.admin.reload";
    public static final String PERMISSION_ADMIN_MANAGE = "knk.siege.admin.manage";
    public static final String PERMISSION_BYPASS_COMMANDS = "knk.siege.bypass.commands";

    /** DESIGN §6.1: lobbies start matchmaking about two seconds after enable (v1/v2 used 2 s). */
    private static final long BOOTSTRAP_START_DELAY_TICKS = 40L;
    /** How long after match start / a respawn a spawn pick still teleports (then it only sets the choice). */
    private static final Duration SPAWN_PICK_WINDOW = Duration.ofSeconds(20);

    private final Plugin plugin;
    private final Logger logger;
    private final SiegeDataAccess dataAccess;
    private final TitleBracketsQueryApi titleBracketsApi;
    private final SiegeMatchesCommandApi matchApi;
    private final SiegePlayerVault vault;
    private final KnkPermissible permissions;
    private final UserCache userCache;
    private final SiegeRuntimeLocks locks = new SiegeRuntimeLocks();
    /** One long-lived generator: a new Random per draw would correlate consecutive draws. */
    private final RandomGenerator random;

    private final Map<Integer, SiegeLobbyRuntime> lobbies = new LinkedHashMap<>();
    /** Lobbies an admin stopped: a config refresh must not restart them. */
    private final Set<Integer> adminStopped = new HashSet<>();
    private final Set<Integer> skippedModeLogged = new HashSet<>();
    private final List<SiegeMatchObserver> observers = new ArrayList<>();

    private SiegeTitleRanks titleRanks = SiegeTitleRanks.empty();
    private BukkitTask ticker;
    private boolean refreshInFlight;
    private final List<CommandSender> refreshWaiters = new ArrayList<>();
    private boolean shuttingDown;
    private MenuHooks menuHooks;

    public SiegeService(
            Plugin plugin,
            SiegeDataAccess dataAccess,
            TitleBracketsQueryApi titleBracketsApi,
            SiegeMatchesCommandApi matchApi,
            SiegePlayerVault vault,
            KnkPermissible permissions,
            UserCache userCache,
            RandomGenerator random
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        this.dataAccess = Objects.requireNonNull(dataAccess, "dataAccess");
        this.titleBracketsApi = titleBracketsApi;
        this.matchApi = Objects.requireNonNull(matchApi, "matchApi");
        this.vault = Objects.requireNonNull(vault, "vault");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.userCache = Objects.requireNonNull(userCache, "userCache");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Phase 8b: set by {@code SiegeMenuBridge}; null keeps every chat fallback. */
    public void setMenuHooks(MenuHooks menuHooks) {
        this.menuHooks = menuHooks;
    }

    /**
     * Phase 8b: {@code /siege} (and the menu entry): the viewer's own siege Information, else the
     * overview. False when the menus aren't available (the caller prints the chat list instead).
     */
    public boolean openMenu(Player player) {
        if (menuHooks == null) return false;
        Optional<SiegeLobbyRuntime> own = lobbyOf(player.getUniqueId());
        if (own.isPresent() && menuHooks.openInformation(player, own.get().id())) return true;
        return menuHooks.openOverview(player);
    }

    /**
     * {@code /siege menu} (playtest 2026-09-26): the member's own siege Information menu, from matchmaking
     * to the end of the match. Empty when the player isn't in a siege; false when the menus aren't
     * available (the caller falls back to the chat info).
     */
    public Optional<Boolean> openGameMenu(Player player) {
        Optional<SiegeLobbyRuntime> own = lobbyOf(player.getUniqueId());
        if (own.isEmpty()) return Optional.empty();
        return Optional.of(menuHooks != null && menuHooks.openInformation(player, own.get().id()));
    }

    public void addObserver(SiegeMatchObserver observer) {
        observers.add(Objects.requireNonNull(observer, "observer"));
    }

    // ==================== Lifecycle ====================

    /** Bootstrap (DESIGN §6.1): fetch the runtime config off-thread, then build lobbies on the main thread. */
    public void start() {
        refreshTitleBrackets();
        dataAccess.getRuntimeConfigAsync().whenComplete((result, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (shuttingDown) return;
                    onRuntimeConfig(result, error, true);
                }));
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        logger.info("[Siege] Runtime started (1 s ticker); fetching runtime config");
    }

    /** {@code onDisable}: stop every lobby ({@code SERVER_RESTART}: aborts matches, restores everyone). */
    public void shutdown() {
        shuttingDown = true;
        if (ticker != null) ticker.cancel();
        for (SiegeLobbyRuntime rt : new ArrayList<>(lobbies.values())) {
            try {
                apply(rt, rt.machine().stop(SiegeEndReason.SERVER_RESTART));
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "[Siege] Failed to stop lobby " + rt.key() + " on shutdown", e);
            }
            // Anyone left (shouldn't be) is still restored.
            for (UUID member : new ArrayList<>(rt.mutableMembers())) {
                removeMember(rt, member, LeaveCause.SHUTDOWN, false);
            }
        }
        observers.forEach(o -> safely("shutdown", () -> o.shutdown()));
        lobbies.clear();
    }

    // ==================== Ticker ====================

    private void tick() {
        for (SiegeLobbyRuntime rt : new ArrayList<>(lobbies.values())) {
            try {
                apply(rt, rt.machine().tick(rt.memberCount()));
                if (rt.phase() == SiegePhase.IN_PROGRESS) {
                    rt.match().ifPresent(match -> stepMatch(rt, match));
                }
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "[Siege] Tick failed for lobby " + rt.key(), e);
            }
        }
    }

    private void stepMatch(SiegeLobbyRuntime rt, SiegeMatch match) {
        Map<Integer, List<Presence>> presence = presence(match);
        BoardStep step = match.board().step(presence);
        Instant now = Instant.now();
        for (CaptureEvent capture : step.captures()) {
            match.roster().recordCapture(capture.capturerId());
            match.recordCaptureTime(capture.objectiveId(), capture.captureNumber(), now);
            announceCapture(rt, match, capture);
            resetLostObjectiveChoices(match, capture);
            observers.forEach(o -> safely("objectiveCaptured", () -> o.objectiveCaptured(rt, match, capture)));
        }
        observers.forEach(o -> safely("secondTicked", () -> o.secondTicked(rt, match, step, presence)));
        if (step.instantVictoryCapture().isPresent()) {
            apply(rt, rt.machine().endMatch(SiegeEndReason.INSTANT_VICTORY));
        }
    }

    /**
     * Playtest 2026-09-26: members who chose the objective that was just lost get their team's default
     * spawnpoint as their remembered choice (not an empty one, so they aren't asked again). They can pick
     * another option from the siege menu.
     */
    private void resetLostObjectiveChoices(SiegeMatch match, CaptureEvent capture) {
        List<UUID> reset = match.roster().resetObjectiveChoice(capture.objectiveId(), capture.newHolderTeamId(),
                teamId -> match.scenario().team(teamId).flatMap(SiegeSpawnOptions::defaultChoice).orElse(null));
        if (reset.isEmpty()) return;
        String name = match.scenario().objective(capture.objectiveId())
                .map(o -> SiegeDisplayText.clean(o.name(), "#" + o.id())).orElse("#" + capture.objectiveId());
        Component msg = SiegeMessages.info("Your team lost " + name + ": you respawn at your team's spawnpoint again. Change it in ")
                .append(SiegeMessages.command("/siege menu"));
        for (UUID id : reset) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(msg);
        }
    }

    /** Living, non-spectating members within each objective's capture radius (DESIGN §7.2). */
    private Map<Integer, List<Presence>> presence(SiegeMatch match) {
        Map<Integer, List<Presence>> result = new HashMap<>();
        List<Player> living = new ArrayList<>();
        for (UUID id : match.roster().playerIds()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && !p.isDead() && p.getGameMode() != GameMode.SPECTATOR) living.add(p);
        }
        for (KnkSiegeObjective objective : match.scenario().objectives()) {
            // Measured from the floor under the capture point, like the ring is drawn (playtest 2026-09-26).
            Optional<Location> center = SiegeBukkit.toLocation(objective.captureLocation()).map(SiegeBukkit::floorOf);
            if (center.isEmpty()) continue;
            List<Presence> present = new ArrayList<>();
            double radius = objective.captureRadius();
            for (Player p : living) {
                if (!Objects.equals(p.getWorld(), center.get().getWorld())) continue;
                double distance = p.getLocation().distance(center.get());
                if (distance <= radius) {
                    int teamId = match.roster().teamOf(p.getUniqueId()).orElse(-1);
                    present.add(new Presence(p.getUniqueId(), teamId, distance));
                }
            }
            if (!present.isEmpty()) result.put(objective.id(), present);
        }
        return result;
    }

    // ==================== Effects ====================

    private void apply(SiegeLobbyRuntime rt, List<SiegeEffect> effects) {
        for (SiegeEffect effect : effects) {
            try {
                applyOne(rt, effect);
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "[Siege] Applying " + effect.getClass().getSimpleName()
                        + " failed for lobby " + rt.key(), e);
            }
        }
    }

    private void applyOne(SiegeLobbyRuntime rt, SiegeEffect effect) {
        switch (effect) {
            case PhaseChangedEffect e -> {
                if (e.from() == SiegePhase.DISABLED) rt.setDisabledLogged(false);
                notifyChanged(rt);
            }
            case AnnounceEffect e -> announce(rt, e);
            case MatchmakingStartedEffect e -> {
                // Voting opens for e.candidates(); the MATCHMAKING_OPENED announcement that follows lists them.
            }
            case DrawScenarioEffect e -> onDraw(rt, e);
            case SendToHubEffect e -> onSendToHub(rt, e.scenario());
            case SplitTeamsEffect e -> onSplit(rt, e.scenario());
            case StartMatchEffect e -> onStartMatch(rt, e);
            case StartMessageEffect e -> onStartMessage(rt, e.scenario());
            case EndMatchEffect e -> onEndMatch(rt, e.reason());
            case CancelMatchmakingEffect e -> onCancel(rt, e);
            case RefreshConfigEffect e -> refreshConfig(null);
            case LobbyDisabledEffect e -> {
                if (!rt.disabledLogged()) {
                    logger.warning("[Siege] Lobby " + rt.key() + " is disabled: " + switch (e.reason()) {
                        case NO_READY_SCENARIO -> "its rotation has no ready scenario (check readiness in the web app)";
                        case STOPPED -> "stopped";
                    });
                    rt.setDisabledLogged(true);
                }
            }
        }
    }

    private void announce(SiegeLobbyRuntime rt, AnnounceEffect e) {
        String name = rt.displayName();
        Component body = switch (e.announcement()) {
            case MATCHMAKING_OPENED -> {
                String candidates = rt.machine().candidates().stream()
                        .map(s -> SiegeDisplayText.clean(s.name(), "Scenario " + s.id()))
                        .reduce((a, b) -> a + ", " + b).orElse("?");
                yield Component.text("Matchmaking for ", SiegeMessages.INFO)
                        .append(Component.text(name, SiegeMessages.HIGHLIGHT))
                        .append(Component.text(" has started! The siege begins in " + SiegeMessages.duration(e.seconds())
                                + ". Vote between: " + candidates + ". ", SiegeMessages.INFO));
            }
            case MATCHMAKING_COUNTDOWN -> Component.text("The siege ", SiegeMessages.INFO)
                    .append(Component.text(name, SiegeMessages.HIGHLIGHT))
                    .append(Component.text(" begins in " + SiegeMessages.duration(e.seconds()) + ". ", SiegeMessages.INFO));
            case VOTING_CLOSED -> Component.text("Voting is closed. You will be teleported to the hub in "
                    + SiegeMessages.duration(e.seconds()) + ".", SiegeMessages.INFO);
        };
        if (e.joinable()) {
            body = body.append(Component.text("Join with ", SiegeMessages.INFO))
                    .append(SiegeMessages.command("/siege join " + rt.key()));
        }
        Component message = SiegeMessages.prefixed(body);
        switch (e.audience()) {
            case ONLINE -> Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(message));
            case MEMBERS -> sendToMembers(rt, message);
        }
    }

    // ---------- Draw (T-25) ----------

    private void onDraw(SiegeLobbyRuntime rt, DrawScenarioEffect e) {
        KnkSiegeScenario scenario = e.scenario();
        String token = UUID.randomUUID().toString();
        rt.startRound(token, guarded("createMatch", matchApi.createMatch(rt.id(), scenario.id())));

        // DESIGN §6.2: remove members the drawn scenario excludes by title, then the latest joiners over capacity.
        int minXp = scenario.minTitleExperience() == null ? 0 : scenario.minTitleExperience();
        List<UUID> ordered = new ArrayList<>(rt.mutableMembers());
        List<UUID> kept = new ArrayList<>();
        for (UUID id : ordered) {
            if (minXp > 0 && experienceOf(id) < minXp) {
                tell(id, SiegeMessages.bad("The drawn scenario " + scenarioName(scenario) + " requires the title "
                        + titleRanks.requirementLabel(minXp) + "; you have been removed from the siege."));
                removeMember(rt, id, LeaveCause.EXCLUDED_AT_DRAW, false);
            } else {
                kept.add(id);
            }
        }
        for (int i = scenario.playersMax(); i < kept.size(); i++) {
            UUID id = kept.get(i);
            tell(id, SiegeMessages.bad("The drawn scenario " + scenarioName(scenario) + " is full ("
                    + scenario.playersMax() + " players); the latest joiners have been removed."));
            removeMember(rt, id, LeaveCause.EXCLUDED_AT_DRAW, false);
        }

        int toHub = rt.machine().timeline().draw() - rt.machine().timeline().hub();
        sendToMembers(rt, SiegeMessages.prefixed(Component.text("Scenario drawn: ", SiegeMessages.INFO)
                .append(Component.text(scenarioName(scenario), SiegeMessages.HIGHLIGHT))
                .append(Component.text(" (" + SiegeMessages.drawMethod(e.draw().method()) + "). Teleport to the hub in "
                        + SiegeMessages.duration(toHub) + ".", SiegeMessages.INFO))));
        notifyChanged(rt);
    }

    // ---------- Hub (T-15) ----------

    private void onSendToHub(SiegeLobbyRuntime rt, KnkSiegeScenario scenario) {
        Optional<Location> hub = SiegeBukkit.toLocation(scenario.hubLocation());
        if (hub.isEmpty()) {
            logger.severe("[Siege] Scenario " + scenario.id() + " hub location can't be resolved (world not loaded?); "
                    + "members stay where they are until the start");
        }
        String token = rt.matchToken().orElseGet(() -> UUID.randomUUID().toString());
        for (UUID id : new ArrayList<>(rt.mutableMembers())) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                removeMember(rt, id, LeaveCause.QUIT, false);
                continue;
            }
            if (player.isDead()) player.spigot().respawn();
            player.closeInventory();
            if (!vault.snapshot(player, rt.id(), token)) {
                player.sendMessage(SiegeMessages.bad("Your inventory couldn't be saved safely, so you can't take part "
                        + "in this siege. Please tell an admin."));
                removeMember(rt, id, LeaveCause.SNAPSHOT_FAILED, false);
                continue;
            }
            hub.ifPresent(player::teleport);
            player.sendMessage(SiegeMessages.info("You are at the hub of " + scenarioName(scenario)
                    + ". Your inventory and position are saved and will be restored after the siege."));
        }
        // Phase 7a: gates and area entry lock down now, before the start (DESIGN §6.5 "lock scenario" first).
        observers.forEach(o -> safely("areaLockdownStarted", () -> o.areaLockdownStarted(rt, scenario)));
        notifyChanged(rt);
    }

    // ---------- Split (T-10) ----------

    private void onSplit(SiegeLobbyRuntime rt, KnkSiegeScenario scenario) {
        rt.setSplit(partition(rt, scenario, rt.mutableMembers()));
        rt.split().ifPresent(split -> split.forEach((teamId, players) -> {
            KnkSiegeTeam team = scenario.team(teamId).orElse(null);
            Component msg = SiegeMessages.prefixed(Component.text("You are on team ", SiegeMessages.INFO)
                    .append(SiegeBukkit.teamComponent(team))
                    .append(Component.text(team != null && team.isDefender() ? " (defenders)." : " (attackers).", SiegeMessages.INFO)));
            players.forEach(p -> tell(p, msg));
        }));
        notifyChanged(rt);
    }

    private Map<Integer, List<UUID>> partition(SiegeLobbyRuntime rt, KnkSiegeScenario scenario, Collection<UUID> players) {
        List<TeamPartitioner.Entry> entries = new ArrayList<>();
        for (UUID id : players) {
            UserSummary user = userCache.getStale(id).orElse(null);
            int rank = user == null ? 0 : titleRanks.rank(user.titleBracketId(), user.experiencePoints());
            entries.add(new TeamPartitioner.Entry(id, rank));
        }
        List<Integer> teamIds = scenario.teams().stream().map(KnkSiegeTeam::id).toList();
        return TeamPartitioner.partition(entries, teamIds, random);
    }

    // ---------- Start (T-0) ----------

    private void onStartMatch(SiegeLobbyRuntime rt, StartMatchEffect e) {
        KnkSiegeScenario scenario = e.scenario();
        Map<Integer, List<UUID>> teams = currentTeams(rt, scenario);
        String token = rt.matchToken().orElseGet(() -> UUID.randomUUID().toString());
        SiegeMatch match = new SiegeMatch(scenario, rt.machine().configuration(), token, teams);
        rt.setMatch(match);

        for (MemberView member : match.roster().members()) {
            Player player = Bukkit.getPlayer(member.playerId());
            if (player == null) continue;
            KnkSiegeTeam team = scenario.team(member.teamId()).orElse(null);
            if (team == null) continue;
            SiegeSpawnOptions.resolveRespawn(team, match.board(), null)
                    .flatMap(o -> SiegeBukkit.toLocation(o.location()))
                    .ifPresent(player::teleport);
            player.showTitle(Title.title(Component.text("The siege begins!", NamedTextColor.GOLD),
                    Component.text("You fight for ", SiegeMessages.INFO).append(SiegeBukkit.teamComponent(team))));
            player.sendMessage(SiegeMessages.info("The match lasts " + SiegeMessages.duration(e.durationSeconds())
                    + ". Objectives: " + objectiveSummary(scenario) + ". Use /siege info for the state of the match."));
            if (SiegeSpawnOptions.pickerWorthOpening(team, match.board())) {
                offerSpawnPicker(player, match, team);
            }
        }

        observers.forEach(o -> safely("matchStarted", () -> o.matchStarted(rt, match)));

        List<Participant> participants = new ArrayList<>();
        for (MemberView member : match.roster().members()) {
            Integer userId = rt.userIds().get(member.playerId());
            if (userId != null) participants.add(new Participant(userId, member.teamId()));
        }
        withMatchId(rt, id -> guarded("startMatch", matchApi.startMatch(id, participants)));
        logger.info("[Siege] Lobby " + rt.key() + " started scenario " + scenario.id() + " with "
                + match.roster().size() + " members for " + e.durationSeconds() + " s");
        notifyChanged(rt);
    }

    /** The split, limited to current members; anyone missing from it joins the smallest team. */
    private Map<Integer, List<UUID>> currentTeams(SiegeLobbyRuntime rt, KnkSiegeScenario scenario) {
        Map<Integer, List<UUID>> split = rt.split().orElseGet(() -> partition(rt, scenario, rt.mutableMembers()));
        Map<Integer, List<UUID>> teams = new LinkedHashMap<>();
        scenario.teams().forEach(t -> teams.put(t.id(), new ArrayList<>()));
        Set<UUID> placed = new HashSet<>();
        split.forEach((teamId, players) -> players.stream()
                .filter(rt.mutableMembers()::contains)
                .forEach(p -> {
                    teams.computeIfAbsent(teamId, k -> new ArrayList<>()).add(p);
                    placed.add(p);
                }));
        for (UUID member : rt.mutableMembers()) {
            if (placed.contains(member)) continue;
            teams.values().stream().min(Comparator.comparingInt(List::size)).ifPresent(l -> l.add(member));
        }
        return teams;
    }

    private void onStartMessage(SiegeLobbyRuntime rt, KnkSiegeScenario scenario) {
        rt.match().ifPresent(match -> scenario.teams().forEach(team -> {
            String text = SiegeDisplayText.clean(team.startMessage());
            if (text.isEmpty()) return;
            Component bar = Component.text(text, SiegeBukkit.color(team));
            match.roster().membersOf(team.id()).forEach(id -> {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.sendActionBar(bar);
            });
        }));
    }

    // ---------- End ----------

    private void onEndMatch(SiegeLobbyRuntime rt, SiegeEndReason reason) {
        SiegeMatch match = rt.match().orElse(null);
        if (match == null) {
            logger.warning("[Siege] Lobby " + rt.key() + " ended (" + reason + ") without a running match; releasing members");
            releaseAll(rt);
            return;
        }
        Set<Integer> alliancesLeft = match.alliances().alliancesWithMembers(match.roster().membersPerTeam());
        WinResolver.Result result = match.winResolver().resolve(reason, match.board(), alliancesLeft);
        announceResult(rt, match, result);

        if (!result.isAborted()) {
            // The rewards themselves come from the server's complete answer (printRewardSummary).
            boolean unrecorded = isUnrecorded(rt);
            for (MemberView member : match.roster().members()) {
                tell(member.playerId(), statsLine(member));
                if (unrecorded) {
                    tell(member.playerId(), SiegeMessages.bad("This match couldn't be recorded (the server couldn't reach "
                            + "the API when it was drawn), so no rewards are granted this round."));
                }
            }
        }

        Completion completion = completion(rt, match, result);
        Map<Integer, UUID> playerByUserId = new HashMap<>();
        rt.userIds().forEach((uuid, userId) -> playerByUserId.put(userId, uuid));
        withMatchId(rt, id -> result.isAborted()
                ? guarded("abortMatch", matchApi.abortMatch(id, reason))
                : guarded("completeMatch", matchApi.completeMatch(id, completion))
                        .thenAccept(summary -> printRewardSummary(summary, playerByUserId)));

        observers.forEach(o -> safely("matchEnded", () -> o.matchEnded(rt, match)));
        releaseAll(rt);
        logger.info("[Siege] Lobby " + rt.key() + " match ended: " + reason + " -> " + result.decision()
                + result.winningAllianceGroup().stream().mapToObj(g -> " (alliance " + g + ")").findFirst().orElse(""));
    }

    private Completion completion(SiegeLobbyRuntime rt, SiegeMatch match, WinResolver.Result result) {
        List<ParticipantResult> participants = new ArrayList<>();
        for (MemberView m : match.roster().members()) {
            Integer userId = rt.userIds().get(m.playerId());
            if (userId == null) continue;
            participants.add(new ParticipantResult(userId, m.teamId(), m.kills(), m.deaths(), m.highestKillStreak(), m.captures()));
        }
        List<ObjectiveResult> objectives = new ArrayList<>();
        for (ObjectiveState state : match.board().objectives()) {
            if (state.captures().isEmpty()) {
                objectives.add(new ObjectiveResult(state.objectiveId(), state.holderTeamId(), null, null));
                continue;
            }
            for (CaptureEvent c : state.captures()) {
                objectives.add(new ObjectiveResult(state.objectiveId(), c.newHolderTeamId(),
                        rt.userIds().get(c.capturerId()), match.captureTime(c.objectiveId(), c.captureNumber())));
            }
        }
        return new Completion(result.reason(), result.winnerOrNull(), participants, objectives);
    }

    private void announceResult(SiegeLobbyRuntime rt, SiegeMatch match, WinResolver.Result result) {
        Component body;
        if (result.isAborted()) {
            body = Component.text("The siege ", SiegeMessages.INFO)
                    .append(Component.text(rt.displayName(), SiegeMessages.HIGHLIGHT))
                    .append(Component.text(" was aborted (" + SiegeMessages.endReason(result.reason()) + "). Nobody wins; "
                            + "everyone's inventory is restored.", SiegeMessages.INFO));
        } else if (result.winningAllianceGroup().isEmpty()) {
            body = Component.text("The siege ", SiegeMessages.INFO)
                    .append(Component.text(rt.displayName(), SiegeMessages.HIGHLIGHT))
                    .append(Component.text(" ended in a draw (" + SiegeMessages.endReason(result.reason()) + ").", SiegeMessages.INFO));
        } else {
            int alliance = result.winningAllianceGroup().getAsInt();
            Component winners = Component.empty();
            boolean first = true;
            for (Integer teamId : match.alliances().teamsOf(alliance)) {
                if (!first) winners = winners.append(Component.text(" & ", SiegeMessages.INFO));
                winners = winners.append(SiegeBukkit.teamComponent(match.scenario().team(teamId).orElse(null)));
                first = false;
            }
            body = winners.append(Component.text(" won the siege ", SiegeMessages.INFO))
                    .append(Component.text(rt.displayName(), SiegeMessages.HIGHLIGHT))
                    .append(Component.text(" - " + SiegeMessages.decision(result.decision()) + " ("
                            + SiegeMessages.endReason(result.reason()) + ").", SiegeMessages.INFO));
        }
        Component message = SiegeMessages.prefixed(body);
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(message));
    }

    private static Component statsLine(MemberView member) {
        return SiegeMessages.info("Your match: " + member.kills() + " kills, " + member.deaths() + " deaths, best streak "
                + member.highestKillStreak() + ", " + member.captures() + " captures.");
    }

    /** The server's breakdown for one player (DESIGN §7.6: win, objectives gained, captures). */
    private static Component rewardLine(ParticipantReward r) {
        if (!r.hasRewards()) {
            return SiegeMessages.info("No rewards this time.");
        }
        StringBuilder parts = new StringBuilder();
        if (r.coins() > 0) {
            parts.append("+").append(r.coins()).append(" coins ");
            // Smoke test 2026-09-26: the server applies the personal salary x rank (premium) multiplier.
            if (Math.abs(r.coinMultiplier() - 1.0) > 0.001) {
                parts.append("(x").append(new java.text.DecimalFormat("0.##",
                        java.text.DecimalFormatSymbols.getInstance(Locale.ROOT)).format(r.coinMultiplier())).append(" bonus) ");
            }
        }
        if (r.experience() > 0) parts.append("+").append(r.experience()).append(" XP ");
        if (r.gems() > 0) parts.append("+").append(r.gems()).append(" gems ");
        List<String> why = new ArrayList<>();
        if (r.won()) why.add("win");
        if (r.holdingCount() > 0) why.add(r.holdingCount() + " objective(s) gained");
        if (r.captureCount() > 0) why.add(r.captureCount() + " capture(s)");
        return SiegeMessages.prefixed(Component.text("Rewards granted: " + parts.toString().trim(), SiegeMessages.GOOD)
                .append(Component.text(why.isEmpty() ? "" : " (" + String.join(", ", why) + ")", SiegeMessages.INFO)));
    }

    /**
     * Phase 6: prints the server's reward breakdown (the API grants the rewards). A null summary means
     * the complete call failed and was spooled by SiegeMatchRecorder (or refused - then it's logged).
     */
    private void printRewardSummary(RewardSummary summary, Map<Integer, UUID> playerByUserId) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (summary == null) {
                playerByUserId.values().forEach(id -> tell(id, SiegeMessages.bad("Your rewards couldn't be confirmed yet: "
                        + "the server couldn't reach the API. The result is kept and recorded automatically later.")));
                return;
            }
            for (ParticipantReward r : summary.rewards()) {
                UUID id = playerByUserId.get(r.userId());
                if (id == null || !r.presentAtEnd()) continue;
                tell(id, rewardLine(r));
            }
        });
    }

    /** True when the round's match row could not be created (the round runs unrecorded, no rewards). */
    private static boolean isUnrecorded(SiegeLobbyRuntime rt) {
        CompletableFuture<Long> matchId = rt.matchIdFuture();
        if (matchId == null || !matchId.isDone()) return false;
        return matchId.isCompletedExceptionally() || matchId.getNow(null) == null;
    }

    // ---------- Cancel ----------

    private void onCancel(SiegeLobbyRuntime rt, CancelMatchmakingEffect e) {
        int min = rt.drawnScenario().map(KnkSiegeScenario::playersMin)
                .orElseGet(() -> rt.lobby().rotation().stream().mapToInt(r -> r.scenario().playersMin()).min().orElse(0));
        Component message = SiegeMessages.bad("The siege " + rt.displayName() + " was called off: "
                + SiegeMessages.cancelReason(e.reason(), min) + "."
                + (e.reason() == CancelReason.ADMIN_STOPPED || e.reason() == CancelReason.SERVER_RESTART ? ""
                : " Next matchmaking in " + SiegeMessages.duration(rt.lobby().cooldownSeconds()) + "."));
        sendToMembers(rt, message);
        SiegeEndReason abortReason = switch (e.reason()) {
            case ADMIN_STOPPED -> SiegeEndReason.ADMIN_STOPPED;
            case SERVER_RESTART -> SiegeEndReason.SERVER_RESTART;
            case NOT_ENOUGH_PLAYERS, NO_SCENARIO_AVAILABLE -> SiegeEndReason.NOT_ENOUGH_PLAYERS;
        };
        // A match row exists only after the draw (DESIGN §6.3: a cancel at the draw records nothing).
        withMatchId(rt, id -> guarded("abortMatch", matchApi.abortMatch(id, abortReason)));
        releaseAll(rt);
    }

    /** Restores (when snapshotted) and releases every member, and clears the round. */
    private void releaseAll(SiegeLobbyRuntime rt) {
        for (UUID member : new ArrayList<>(rt.mutableMembers())) {
            removeMember(rt, member, LeaveCause.ROUND_OVER, false);
        }
        locks.releasePlayers(rt.id());
        rt.userIds().clear();
        // Phase 7a: gates and area entry are restored while the round (match id, scenario) is still known.
        observers.forEach(o -> safely("roundReleased", () -> o.roundReleased(rt)));
        rt.clearRound();
        notifyChanged(rt);
    }

    // ==================== Membership ====================

    enum LeaveCause { LEAVE, QUIT, KICK, EXCLUDED_AT_DRAW, SNAPSHOT_FAILED, ROUND_OVER, SHUTDOWN }

    /**
     * Removes a member: drops their vote and lock, removes them from the split/roster (Phase 6
     * {@code participantLeft}), restores their vault if they had one, and - during a match - checks
     * whether the match must end (DESIGN §6.8).
     */
    private void removeMember(SiegeLobbyRuntime rt, UUID id, LeaveCause cause, boolean checkMembership) {
        if (!rt.mutableMembers().remove(id)) return;
        Integer userId = rt.userIds().remove(id);
        locks.releasePlayer(id);
        rt.machine().removeMember(id);
        rt.split().ifPresent(split -> {
            Map<Integer, List<UUID>> copy = new LinkedHashMap<>();
            split.forEach((team, players) -> copy.put(team, players.stream().filter(p -> !p.equals(id)).toList()));
            rt.setSplit(copy);
        });

        Player player = Bukkit.getPlayer(id);
        SiegeMatch match = rt.match().orElse(null);
        if (match != null && match.roster().contains(id)) {
            if (cause != LeaveCause.ROUND_OVER && cause != LeaveCause.SHUTDOWN) {
                match.roster().remove(id);
                if (userId != null) {
                    Instant leftAt = Instant.now();
                    withMatchId(rt, matchId -> guarded("participantLeft", matchApi.participantLeft(matchId, userId, leftAt)));
                }
            }
            match.closeSpawnPick(id);
            if (player != null) observers.forEach(o -> safely("memberRemoved", () -> o.memberRemoved(rt, match, player)));
        }

        if (player != null && vault.has(id)) {
            if (cause == LeaveCause.QUIT) vault.restoreOnQuit(player);
            else vault.restore(player);
        }

        if (checkMembership && match != null && rt.phase() == SiegePhase.IN_PROGRESS) {
            match.winResolver().membershipEnd(match.roster().membersPerTeam(), match.scenario().playersMin())
                    .ifPresent(reason -> apply(rt, rt.machine().endMatch(reason)));
        }
        notifyChanged(rt);
    }

    // ==================== Player operations ====================

    /** {@code /siege join [lobbyKey]} (DESIGN §6.2). With no key and a single joinable lobby, that one. */
    public Reply join(Player player, String lobbyKey) {
        UUID id = player.getUniqueId();
        if (!hasPermission(player, PERMISSION_PLAY)) {
            return Reply.fail(SiegeMessages.bad("You don't have permission to play sieges."));
        }
        Optional<SiegeLobbyRuntime> current = lobbyOf(id);
        SiegeLobbyRuntime rt;
        if (lobbyKey == null || lobbyKey.isBlank()) {
            List<SiegeLobbyRuntime> joinable = lobbies.values().stream().filter(l -> l.machine().isJoinable()).toList();
            if (joinable.size() == 1) {
                rt = joinable.get(0);
            } else if (lobbies.isEmpty()) {
                return Reply.fail(SiegeMessages.bad("There are no siege lobbies right now."));
            } else {
                return Reply.fail(SiegeMessages.bad("Say which lobby: /siege join <lobby>. Lobbies: " + lobbyKeys() + "."));
            }
        } else {
            Optional<SiegeLobbyRuntime> byKey = lobbyByKey(lobbyKey);
            if (byKey.isEmpty()) {
                return Reply.fail(SiegeMessages.bad("There is no siege lobby '" + lobbyKey + "'. Lobbies: " + lobbyKeys() + "."));
            }
            rt = byKey.get();
        }
        if (current.isPresent()) {
            return Reply.fail(current.get() == rt
                    ? SiegeMessages.info("You are already in " + rt.displayName() + ".")
                    : SiegeMessages.bad("You are already in " + current.get().displayName() + ". Leave it first with /siege leave."));
        }
        if (vault.hasFile(id)) {
            vault.restoreOnJoin(player);
            return Reply.fail(SiegeMessages.bad("Your items from an earlier siege were still being restored; that is done now. "
                    + "Try joining again."));
        }
        SiegeLobbyStateMachine machine = rt.machine();
        if (!machine.isJoinable()) {
            return Reply.fail(SiegeMessages.bad(notJoinableReason(rt)));
        }
        int capacity = machine.joinCapacity();
        if (rt.memberCount() >= capacity) {
            return Reply.fail(SiegeMessages.bad(rt.displayName() + " is full (" + capacity + " players)."));
        }
        UserSummary user = userCache.getStale(id).orElse(null);
        if (user == null || user.id() == null) {
            return Reply.fail(SiegeMessages.bad("Your profile is still loading; try again in a moment."));
        }
        int minXp = machine.joinMinTitleExperience();
        if (minXp > 0 && user.experiencePoints() < minXp) {
            return Reply.fail(SiegeMessages.bad("You need at least the title " + titleRanks.requirementLabel(minXp)
                    + " to join " + rt.displayName() + "."));
        }
        if (!locks.tryClaimPlayer(id, rt.id())) {
            return Reply.fail(SiegeMessages.bad("You are already in another siege."));
        }
        rt.mutableMembers().add(id);
        rt.userIds().put(id, user.id());
        notifyChanged(rt);

        Component msg = SiegeMessages.prefixed(Component.text("You joined ", SiegeMessages.GOOD)
                .append(Component.text(rt.displayName(), SiegeMessages.HIGHLIGHT))
                .append(Component.text(" (" + rt.memberCount() + "/" + capacity + "). The siege begins in "
                        + SiegeMessages.duration(machine.secondsRemaining()) + ". ", SiegeMessages.GOOD)));
        if (machine.isVotingOpen()) {
            msg = msg.append(Component.text("Vote with ", SiegeMessages.INFO)).append(SiegeMessages.command("/siege vote"));
        }
        return Reply.ok(msg);
    }

    private String notJoinableReason(SiegeLobbyRuntime rt) {
        int s = rt.machine().secondsRemaining();
        return switch (rt.phase()) {
            case DISABLED -> rt.displayName() + " is disabled right now.";
            case HUB, IN_PROGRESS, ENDING -> rt.displayName() + " is already under way; join the next round.";
            case COOLDOWN -> rt.displayName() + " opens for the next round in " + SiegeMessages.duration(s) + ".";
            case MATCHMAKING -> rt.displayName() + " can't be joined right now.";
        };
    }

    /** {@code /siege leave}: before the hub nothing but the vote is dropped; later the vault is restored. */
    public Reply leave(Player player) {
        Optional<SiegeLobbyRuntime> rt = lobbyOf(player.getUniqueId());
        if (rt.isEmpty()) return Reply.fail(SiegeMessages.bad("You are not in a siege."));
        boolean active = rt.get().phase().isMatchActive();
        removeMember(rt.get(), player.getUniqueId(), LeaveCause.LEAVE, true);
        return Reply.ok(SiegeMessages.info("You left " + rt.get().displayName() + "."
                + (active ? " Your inventory and position were restored; you get no rewards for this match." : "")));
    }

    /** {@code PlayerQuitEvent}: leave, restoring the inventory now and the location on the next join. */
    public void handleQuit(Player player) {
        lobbyOf(player.getUniqueId()).ifPresent(rt -> removeMember(rt, player.getUniqueId(), LeaveCause.QUIT, true));
    }

    /** {@code PlayerJoinEvent} (one tick later): finish a quit restore or restore a crash leftover. */
    public void handleJoin(Player player) {
        UUID id = player.getUniqueId();
        if (lobbyOf(id).isPresent() || !vault.hasFile(id)) return;
        if (vault.restoreOnJoin(player)) {
            player.sendMessage(SiegeMessages.info("You were returned to where you were before your last siege, "
                    + "with your pre-siege inventory."));
        }
    }

    /** {@code /siege vote} with no choice: the candidates and their votes (chat fallback of the menu). */
    public Reply voteOptions(Player player) {
        Optional<SiegeLobbyRuntime> found = lobbyOf(player.getUniqueId());
        if (found.isEmpty()) return Reply.fail(SiegeMessages.bad("Join a siege first: /siege join <lobby>."));
        SiegeLobbyRuntime rt = found.get();
        Optional<VoteTally> tally = rt.machine().voteTally();
        if (!rt.machine().isVotingOpen() || tally.isEmpty()) {
            return Reply.fail(SiegeMessages.bad("Voting is closed for " + rt.displayName() + "."));
        }
        Optional<VoteChoice> mine = tally.get().choiceOf(player.getUniqueId());
        Component msg = SiegeMessages.info("Vote for the next scenario of " + rt.displayName() + " (voting closes in "
                + SiegeMessages.duration(rt.machine().secondsRemaining() - rt.machine().timeline().voteClose()) + "):");
        int index = 1;
        for (KnkSiegeScenario s : rt.machine().candidates()) {
            boolean chosen = mine.isPresent() && !mine.get().isRandom() && mine.get().scenarioId() == s.id();
            msg = msg.append(Component.newline())
                    .append(SiegeMessages.command("/siege vote " + index))
                    .append(Component.text(" " + scenarioName(s) + " - " + tally.get().votesFor(s.id()) + " vote(s)"
                            + (chosen ? " (your vote)" : ""), chosen ? SiegeMessages.GOOD : SiegeMessages.INFO));
            index++;
        }
        if (tally.get().allowRandomVote()) {
            boolean chosen = mine.isPresent() && mine.get().isRandom();
            msg = msg.append(Component.newline())
                    .append(SiegeMessages.command("/siege vote random"))
                    .append(Component.text(" Random - " + tally.get().randomVotes() + " vote(s)" + (chosen ? " (your vote)" : ""),
                            chosen ? SiegeMessages.GOOD : SiegeMessages.INFO));
        }
        return Reply.ok(msg.append(Component.newline())
                .append(Component.text("Voting for your current choice withdraws your vote.", NamedTextColor.DARK_GRAY)));
    }

    /** {@code /siege vote <n|name|random>} (DESIGN §6.3). */
    public Reply vote(Player player, String choiceText) {
        Optional<SiegeLobbyRuntime> found = lobbyOf(player.getUniqueId());
        if (found.isEmpty()) return Reply.fail(SiegeMessages.bad("Join a siege first: /siege join <lobby>."));
        SiegeLobbyRuntime rt = found.get();
        if (choiceText == null || choiceText.isBlank()) return voteOptions(player);

        VoteChoice choice;
        String label;
        if (choiceText.equalsIgnoreCase("random")) {
            choice = VoteChoice.random();
            label = "Random";
        } else {
            Optional<KnkSiegeScenario> scenario = resolveCandidate(rt, choiceText);
            if (scenario.isEmpty()) {
                return Reply.fail(SiegeMessages.voteResult(VoteResult.NOT_A_CANDIDATE, "'" + choiceText + "'"));
            }
            choice = VoteChoice.scenario(scenario.get().id());
            label = scenarioName(scenario.get());
        }
        VoteResult result = rt.machine().vote(player.getUniqueId(), choice);
        notifyChanged(rt);
        boolean ok = result == VoteResult.CAST || result == VoteResult.CHANGED || result == VoteResult.REMOVED;
        return new Reply(ok, SiegeMessages.voteResult(result, label));
    }

    /**
     * Phase 8b ({@code siege.vote} menu action): votes for an exact choice - a candidate scenario id or
     * Random - in the player's lobby. (The text form treats small numbers as list positions.)
     */
    public Reply vote(Player player, VoteChoice choice) {
        Optional<SiegeLobbyRuntime> found = lobbyOf(player.getUniqueId());
        if (found.isEmpty()) return Reply.fail(SiegeMessages.bad("Join a siege first: /siege join <lobby>."));
        SiegeLobbyRuntime rt = found.get();
        String label;
        if (choice.isRandom()) {
            label = "Random";
        } else {
            Optional<KnkSiegeScenario> scenario = rt.machine().candidates().stream()
                    .filter(s -> s.id() == choice.scenarioId()).findFirst();
            if (scenario.isEmpty()) {
                return Reply.fail(SiegeMessages.voteResult(VoteResult.NOT_A_CANDIDATE, "that scenario"));
            }
            label = scenarioName(scenario.get());
        }
        VoteResult result = rt.machine().vote(player.getUniqueId(), choice);
        notifyChanged(rt);
        boolean ok = result == VoteResult.CAST || result == VoteResult.CHANGED || result == VoteResult.REMOVED;
        return new Reply(ok, SiegeMessages.voteResult(result, label));
    }

    /**
     * Phase 8b ({@code siege.join-eligible}, the Information join line): why this player can't join
     * this lobby right now, or empty when {@link #join} would let them in. Mirrors join's checks
     * without changing anything.
     */
    public Optional<String> joinDenial(Player player, SiegeLobbyRuntime rt) {
        UUID id = player.getUniqueId();
        if (!hasPermission(player, PERMISSION_PLAY)) return Optional.of("You don't have permission to play sieges.");
        Optional<SiegeLobbyRuntime> current = lobbyOf(id);
        if (current.isPresent()) {
            return Optional.of(current.get() == rt ? "You are already in " + rt.displayName() + "."
                    : "You are already in " + current.get().displayName() + ".");
        }
        SiegeLobbyStateMachine machine = rt.machine();
        if (!machine.isJoinable()) return Optional.of(notJoinableReason(rt));
        int capacity = machine.joinCapacity();
        if (rt.memberCount() >= capacity) return Optional.of(rt.displayName() + " is full (" + capacity + " players).");
        UserSummary user = userCache.getStale(id).orElse(null);
        if (user == null || user.id() == null) return Optional.of("Your profile is still loading; try again in a moment.");
        int minXp = machine.joinMinTitleExperience();
        if (minXp > 0 && user.experiencePoints() < minXp) {
            return Optional.of("You need at least the title " + titleRanks.requirementLabel(minXp) + ".");
        }
        Optional<Integer> claimed = locks.lobbyOfPlayer(id);
        if (claimed.isPresent() && claimed.get() != rt.id()) return Optional.of("You are already in another siege.");
        return Optional.empty();
    }

    /** Phase 8b: a player's cached profile (names/titles in the menus); never blocks. */
    public Optional<UserSummary> cachedUser(UUID playerId) {
        return userCache.getStale(playerId);
    }

    private Optional<KnkSiegeScenario> resolveCandidate(SiegeLobbyRuntime rt, String text) {
        List<KnkSiegeScenario> candidates = rt.machine().candidates();
        String t = text.trim();
        try {
            int n = Integer.parseInt(t);
            if (n >= 1 && n <= candidates.size()) return Optional.of(candidates.get(n - 1));
            return candidates.stream().filter(s -> s.id() == n).findFirst();
        } catch (NumberFormatException ignored) { }
        String lower = t.toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(s -> scenarioName(s).toLowerCase(Locale.ROOT).startsWith(lower))
                .findFirst();
    }

    /** {@code /siege spawn}: the member's spawn options, clickable (chat fallback of {@code siege.spawnpoint}). */
    public Reply spawnOptions(Player player) {
        Optional<SiegeMatch> found = runningMatchOf(player.getUniqueId());
        if (found.isEmpty()) return Reply.fail(SiegeMessages.bad("You are not in a running siege."));
        SiegeMatch match = found.get();
        KnkSiegeTeam team = match.teamOf(player.getUniqueId()).orElse(null);
        if (team == null) return Reply.fail(SiegeMessages.bad("You have no team in this siege."));
        return Reply.ok(spawnPickerMessage(match, team, player.getUniqueId()));
    }

    private Component spawnPickerMessage(SiegeMatch match, KnkSiegeTeam team, UUID playerId) {
        List<SpawnOption> options = SiegeSpawnOptions.forTeam(team, match.board(),
                match.roster().spawnChoice(playerId).orElse(null));
        Component msg = SiegeMessages.info("Choose where you spawn:");
        for (SpawnOption option : options) {
            String arg = (option.kind() == SpawnKind.OBJECTIVE ? "objective:" : "spawnpoint:") + option.id();
            String label = (option.kind() == SpawnKind.OBJECTIVE ? "Objective " : "Spawnpoint ")
                    + SiegeDisplayText.clean(option.name(), "#" + option.id());
            msg = msg.append(Component.newline());
            if (option.available()) {
                msg = msg.append(SiegeMessages.command("/siege spawn " + arg))
                        .append(Component.text(" " + label + (option.current() ? " (current)" : ""),
                                option.current() ? SiegeMessages.GOOD : SiegeMessages.INFO));
            } else {
                msg = msg.append(Component.text(label + " - being captured, can't spawn here", SiegeMessages.BAD));
            }
        }
        return msg;
    }

    private void offerSpawnPicker(Player player, SiegeMatch match, KnkSiegeTeam team) {
        // Playtest 2026-09-26: the choice is remembered; ignoring the picker keeps the team default, so
        // afterRespawn doesn't ask again.
        if (match.roster().spawnChoice(player.getUniqueId()).isEmpty()) {
            SiegeSpawnOptions.defaultChoice(team).ifPresent(c -> match.roster().setSpawnChoice(player.getUniqueId(), c));
        }
        match.openSpawnPick(player.getUniqueId(), SPAWN_PICK_WINDOW.toNanos());
        // Phase 8b: the siege.spawnpoint menu when it's available, else the clickable chat list.
        if (menuHooks != null && menuHooks.openSpawnPicker(player)) return;
        player.sendMessage(spawnPickerMessage(match, team, player.getUniqueId()));
    }

    /**
     * {@code /siege spawn <option>}: sets the member's respawn choice (DESIGN §6.6). Right after match
     * start or a respawn it also teleports there; otherwise it only changes where they respawn next.
     * Option: {@code spawnpoint:<id>}, {@code objective:<id>}, a 1-based number from the list, or a name.
     */
    public Reply spawn(Player player, String optionText) {
        if (optionText == null || optionText.isBlank()) return spawnOptions(player);
        Optional<SiegeMatch> found = runningMatchOf(player.getUniqueId());
        if (found.isEmpty()) return Reply.fail(SiegeMessages.bad("You are not in a running siege."));
        SiegeMatch match = found.get();
        KnkSiegeTeam team = match.teamOf(player.getUniqueId()).orElse(null);
        if (team == null) return Reply.fail(SiegeMessages.bad("You have no team in this siege."));

        List<SpawnOption> options = SiegeSpawnOptions.forTeam(team, match.board(), null);
        Optional<SpawnOption> option = resolveSpawnOption(options, optionText.trim());
        if (option.isEmpty()) {
            if (optionText.toLowerCase(Locale.ROOT).startsWith("objective:")) {
                return Reply.fail(SiegeMessages.bad("Your team no longer holds that objective."));
            }
            return Reply.fail(SiegeMessages.bad("That isn't one of your spawn options. Use /siege spawn to list them."));
        }
        if (!option.get().available()) {
            return Reply.fail(SiegeMessages.bad("This objective is being captured! Pick another spawn."));
        }
        match.roster().setSpawnChoice(player.getUniqueId(), option.get().choice());
        String name = SiegeDisplayText.clean(option.get().name(), "#" + option.get().id());
        if (match.consumeSpawnPick(player.getUniqueId()) && !player.isDead()) {
            Optional<Location> target = SiegeBukkit.toLocation(option.get().location());
            if (target.isPresent()) {
                player.teleport(target.get());
                player.sendActionBar(Component.text("You have spawned at " + name, SiegeMessages.INFO));
                return Reply.ok(SiegeMessages.good("You spawned at " + name + "; you will respawn here too."));
            }
        }
        return Reply.ok(SiegeMessages.good("You will respawn at " + name + "."));
    }

    private static Optional<SpawnOption> resolveSpawnOption(List<SpawnOption> options, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String prefix : List.of("spawnpoint:", "objective:")) {
            if (lower.startsWith(prefix)) {
                SpawnKind kind = prefix.startsWith("spawn") ? SpawnKind.SPAWNPOINT : SpawnKind.OBJECTIVE;
                try {
                    int id = Integer.parseInt(text.substring(prefix.length()).trim());
                    return options.stream().filter(o -> o.kind() == kind && o.id() == id).findFirst();
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
            }
        }
        try {
            int n = Integer.parseInt(text);
            return n >= 1 && n <= options.size() ? Optional.of(options.get(n - 1)) : Optional.empty();
        } catch (NumberFormatException ignored) { }
        return options.stream()
                .filter(o -> SiegeDisplayText.clean(o.name()).toLowerCase(Locale.ROOT).startsWith(lower))
                .findFirst();
    }

    /** {@code /siege skip [lobby]} (player, {@code knk.siege.skip}) and {@code /siege admin skip <lobby>}. */
    public Reply skip(CommandSender sender, String lobbyKey, boolean admin) {
        Optional<SiegeLobbyRuntime> found = lobbyKey == null || lobbyKey.isBlank()
                ? (sender instanceof Player p ? lobbyOf(p.getUniqueId()) : Optional.empty())
                : lobbyByKey(lobbyKey);
        if (found.isEmpty() && (lobbyKey == null || lobbyKey.isBlank()) && lobbies.size() == 1) {
            found = lobbies.values().stream().findFirst();
        }
        if (found.isEmpty()) {
            return Reply.fail(SiegeMessages.bad("Say which lobby: /siege " + (admin ? "admin " : "") + "skip <lobby>. Lobbies: "
                    + lobbyKeys() + "."));
        }
        SiegeLobbyRuntime rt = found.get();
        SiegePhase before = rt.phase();
        SkipOutcome outcome = rt.machine().skip(admin ? SkipRequester.ADMIN : SkipRequester.PLAYER);
        apply(rt, outcome.effects());
        int left = rt.machine().secondsRemaining();
        if (outcome.skipped()) {
            logger.info("[Siege] " + sender.getName() + " skipped " + rt.key() + ": " + outcome.result());
            if (outcome.result() == SkipResult.MATCHMAKING_SHORTENED) {
                sendToMembers(rt, SiegeMessages.info(sender.getName() + " shortened matchmaking: the siege begins in "
                        + SiegeMessages.duration(left) + "."));
            }
            notifyChanged(rt);
        }
        return new Reply(outcome.skipped(), SiegeMessages.skipResult(outcome.result(), rt.displayName(), before, admin, left));
    }

    // ==================== Listener support (5b) ====================

    /**
     * The player as a combatant (DESIGN §6.7), or null when they aren't away in a siege match
     * (HUB/IN_PROGRESS). Safe zones are their own team's spawnpoints only.
     */
    public SiegeCombatRules.Combatant combatantOf(Player player) {
        Optional<SiegeLobbyRuntime> rt = activeLobbyOf(player.getUniqueId());
        if (rt.isEmpty()) return null;
        SiegeMatch match = rt.get().match().orElse(null);
        boolean inProgress = rt.get().phase() == SiegePhase.IN_PROGRESS && match != null
                && match.roster().contains(player.getUniqueId());
        int teamId = inProgress ? match.roster().teamOf(player.getUniqueId()).orElse(-1) : -1;
        boolean safe = inProgress && isInOwnSafeZone(player, match);
        return new SiegeCombatRules.Combatant(rt.get().id(), inProgress, teamId, safe);
    }

    /**
     * True when the siege rules allow this hit (enemies in the same running match, neither in their
     * spawn area). The KNG-11 combat safezone check uses it so custom enchantments keep working in
     * sieges fought inside towns.
     */
    public boolean allowsCombat(Player attacker, Player victim) {
        SiegeCombatRules.Combatant a = combatantOf(attacker);
        SiegeCombatRules.Combatant v = combatantOf(victim);
        if (a == null || v == null || a.lobbyId() != v.lobbyId()) return false;
        return SiegeCombatRules.decide(a, v, alliancesOf(v.lobbyId())) == SiegeCombatRules.Outcome.ALLOW;
    }

    /** The alliances of the lobby's running match, or null. */
    public AllianceResolver alliancesOf(int lobbyId) {
        SiegeLobbyRuntime rt = lobbies.get(lobbyId);
        return rt == null ? null : rt.match().map(SiegeMatch::alliances).orElse(null);
    }

    public double headshotMultiplier(int lobbyId) {
        SiegeLobbyRuntime rt = lobbies.get(lobbyId);
        return rt == null ? 1.0 : rt.machine().configuration().headshotMultiplier();
    }

    private static boolean isInOwnSafeZone(Player player, SiegeMatch match) {
        KnkSiegeTeam team = match.teamOf(player.getUniqueId()).orElse(null);
        if (team == null) return false;
        Location at = player.getLocation();
        for (KnkSiegeSpawnpoint spawn : team.spawnpoints()) {
            Optional<Location> center = SiegeBukkit.toLocation(spawn.location());
            if (center.isEmpty() || !Objects.equals(center.get().getWorld(), at.getWorld())) continue;
            if (SiegeCombatRules.withinRadius(at.getX() - center.get().getX(), at.getY() - center.get().getY(),
                    at.getZ() - center.get().getZ(), spawn.safeZoneRadius())) {
                return true;
            }
        }
        return false;
    }

    /**
     * A member of a running match died (DESIGN §6.6): death for the victim, kill and streak for a
     * killer in the same match, announcements to the killer's team at the configured kill counts and
     * streaks (N12: the streak message carries the streak). Null-safe for non-PvP deaths.
     *
     * @return true when the victim is in a running match (the listener then applies keepInventory etc.)
     */
    public boolean handleMemberDeath(Player victim, Player killer) {
        Optional<SiegeMatch> found = runningMatchOf(victim.getUniqueId());
        if (found.isEmpty()) return false;
        SiegeMatch match = found.get();
        match.closeSpawnPick(victim.getUniqueId());
        UUID killerId = killer != null && match.roster().contains(killer.getUniqueId()) ? killer.getUniqueId() : null;
        match.roster().recordDeath(victim.getUniqueId(), killerId, match.configuration()).ifPresent(credit -> {
            int team = match.roster().teamOf(credit.killerId()).orElse(-1);
            String name = killer.getName();
            credit.killsMilestone().ifPresent(kills -> tellTeam(match, team, SiegeMessages.prefixed(
                    Component.text(name + " is an efficient killer with a total of ", SiegeMessages.INFO)
                            .append(Component.text(kills + " kills!", SiegeMessages.HIGHLIGHT)))));
            credit.streak().ifPresent(streak -> tellTeam(match, team, SiegeMessages.prefixed(
                    Component.text(name + " is on a killstreak of ", SiegeMessages.BAD)
                            .append(Component.text(streak + " kills!", SiegeMessages.HIGHLIGHT)))));
            killer.sendActionBar(Component.text("You killed " + victim.getName(), SiegeMessages.GOOD));
        });
        return true;
    }

    private static void tellTeam(SiegeMatch match, int teamId, Component message) {
        match.roster().membersOf(teamId).forEach(id -> tell(id, message));
    }

    /**
     * Where the player respawns: a member restored while dead goes back to their pre-siege location;
     * a member of a running match respawns at their current spawn choice, falling back to the team's
     * default spawnpoint (DESIGN §6.6). Empty: not a siege matter.
     */
    public Optional<Location> respawnLocation(Player player) {
        Optional<Location> restored = vault.takePendingRespawn(player.getUniqueId());
        if (restored.isPresent()) return restored;
        Optional<SiegeLobbyRuntime> hub = activeLobbyOf(player.getUniqueId()).filter(rt -> rt.phase() == SiegePhase.HUB);
        if (hub.isPresent()) {
            return hub.get().drawnScenario().flatMap(s -> SiegeBukkit.toLocation(s.hubLocation()));
        }
        Optional<SiegeMatch> found = runningMatchOf(player.getUniqueId());
        if (found.isEmpty()) return Optional.empty();
        SiegeMatch match = found.get();
        KnkSiegeTeam team = match.teamOf(player.getUniqueId()).orElse(null);
        if (team == null) return Optional.empty();
        return SiegeSpawnOptions.resolveRespawn(team, match.board(), match.roster().spawnChoice(player.getUniqueId()).orElse(null))
                .flatMap(o -> SiegeBukkit.toLocation(o.location()));
    }

    /**
     * After a member respawned: open the spawn picker after the configured delay, only when the member
     * has no remembered choice yet and the team has 2+ options (playtest 2026-09-26: not on every death;
     * the choice is changed from the siege menu).
     */
    public void afterRespawn(Player player) {
        Optional<SiegeMatch> found = runningMatchOf(player.getUniqueId());
        if (found.isEmpty()) return;
        long delay = Math.max(1, found.get().configuration().spawnPickerDelayTicks());
        later(delay, () -> {
            if (!player.isOnline() || player.isDead()) return;
            runningMatchOf(player.getUniqueId()).ifPresent(match -> match.teamOf(player.getUniqueId()).ifPresent(team -> {
                if (match.roster().spawnChoice(player.getUniqueId()).isEmpty()
                        && SiegeSpawnOptions.pickerWorthOpening(team, match.board())) {
                    offerSpawnPicker(player, match, team);
                }
            }));
        });
    }

    /** DESIGN §6.9: may this member run this command right now? True for everyone not away in a match. */
    public boolean isCommandAllowed(Player player, String message) {
        Optional<SiegeLobbyRuntime> rt = activeLobbyOf(player.getUniqueId());
        if (rt.isEmpty()) return true;
        if (hasPermission(player, PERMISSION_BYPASS_COMMANDS)) return true;
        return SiegeCommandFilter.isAllowed(message, rt.get().machine().configuration().allowedCommands());
    }

    /** The allowed-command list for the member's match, for the denial message. */
    public List<String> allowedCommandsFor(Player player) {
        return activeLobbyOf(player.getUniqueId()).map(rt -> rt.machine().configuration().allowedCommands()).orElse(List.of());
    }

    // ==================== Admin operations ====================

    /** {@code /siege admin start <lobby>}: start matchmaking now (DISABLED or COOLDOWN). */
    public Reply adminStart(CommandSender sender, String lobbyKey) {
        Optional<SiegeLobbyRuntime> found = lobbyByKey(lobbyKey);
        if (found.isEmpty()) return Reply.fail(unknownLobby(lobbyKey));
        SiegeLobbyRuntime rt = found.get();
        if (!rt.phase().isBetweenMatches()) {
            return Reply.fail(SiegeMessages.bad(rt.displayName() + " is already running (" + SiegeMessages.phaseLabel(rt.phase()) + ")."));
        }
        adminStopped.remove(rt.id());
        rt.setDisabledLogged(false);
        apply(rt, rt.machine().start());
        logger.info("[Siege] " + sender.getName() + " started lobby " + rt.key() + " -> " + rt.phase());
        if (rt.phase() == SiegePhase.MATCHMAKING) {
            return Reply.ok(SiegeMessages.good("Matchmaking started for " + rt.displayName() + "."));
        }
        return Reply.fail(SiegeMessages.bad(rt.displayName() + " could not start: "
                + (rt.lobby().hasReadyScenario() ? "every scenario is in use by another lobby; it retries after its cooldown."
                : "its rotation has no ready scenario (check readiness in the web app).")));
    }

    /** {@code /siege admin stop <lobby> [reason]}: abort (restore, no rewards) and disable. */
    public Reply adminStop(CommandSender sender, String lobbyKey, String reason) {
        Optional<SiegeLobbyRuntime> found = lobbyByKey(lobbyKey);
        if (found.isEmpty()) return Reply.fail(unknownLobby(lobbyKey));
        SiegeLobbyRuntime rt = found.get();
        if (rt.phase() == SiegePhase.DISABLED) {
            adminStopped.add(rt.id());
            return Reply.fail(SiegeMessages.info(rt.displayName() + " is already stopped."));
        }
        if (reason != null && !reason.isBlank()) {
            sendToMembers(rt, SiegeMessages.bad("An admin is stopping this siege: " + reason));
        }
        adminStopped.add(rt.id());
        apply(rt, rt.machine().stop(SiegeEndReason.ADMIN_STOPPED));
        logger.info("[Siege] " + sender.getName() + " stopped lobby " + rt.key() + (reason == null ? "" : ": " + reason));
        return Reply.ok(SiegeMessages.good("Stopped " + rt.displayName() + ". It stays stopped until /siege admin start "
                + rt.key() + "."));
    }

    /** {@code /siege admin kick <player>}: remove a member (restore applies). */
    public Reply adminKick(CommandSender sender, Player target) {
        Optional<SiegeLobbyRuntime> found = lobbyOf(target.getUniqueId());
        if (found.isEmpty()) return Reply.fail(SiegeMessages.bad(target.getName() + " is not in a siege."));
        removeMember(found.get(), target.getUniqueId(), LeaveCause.KICK, true);
        target.sendMessage(SiegeMessages.bad("An admin removed you from " + found.get().displayName() + "."));
        logger.info("[Siege] " + sender.getName() + " kicked " + target.getName() + " from " + found.get().key());
        return Reply.ok(SiegeMessages.good("Removed " + target.getName() + " from " + found.get().displayName() + "."));
    }

    /**
     * {@code /siege admin reload}: refresh the runtime config for lobbies not in a match (running
     * matches keep theirs until cooldown) and the title brackets. The answer comes asynchronously.
     */
    public Reply reload(CommandSender sender) {
        refreshTitleBrackets();
        refreshConfig(sender);
        return Reply.ok(SiegeMessages.info("Refreshing siege configuration..."));
    }

    // ==================== Configuration ====================

    private void refreshConfig(CommandSender replyTo) {
        if (replyTo != null) refreshWaiters.add(replyTo);
        if (refreshInFlight) return;
        refreshInFlight = true;
        dataAccess.refreshRuntimeConfigAsync().whenComplete((result, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    refreshInFlight = false;
                    if (shuttingDown) return;
                    onRuntimeConfig(result, error, false);
                }));
    }

    private void refreshTitleBrackets() {
        if (titleBracketsApi == null) return;
        titleBracketsApi.getAll().whenComplete((brackets, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || brackets == null) {
                logger.warning("[Siege] Could not fetch title brackets (" + (error == null ? "no data" : error.getMessage())
                        + "); join denials show the XP number and the team split ranks by raw XP");
                return;
            }
            titleRanks = new SiegeTitleRanks(brackets);
        }));
    }

    private void onRuntimeConfig(FetchResult<KnkSiegeRuntimeConfig> result, Throwable error, boolean bootstrap) {
        List<CommandSender> waiters = new ArrayList<>(refreshWaiters);
        refreshWaiters.clear();
        Optional<KnkSiegeRuntimeConfig> config = error == null && result != null ? result.value() : Optional.empty();
        if (config.isEmpty()) {
            String why = error != null ? error.getMessage()
                    : result != null ? result.error().map(Throwable::getMessage).orElse(String.valueOf(result.status())) : "no result";
            logger.warning("[Siege] Runtime config unavailable (" + why + "); "
                    + (bootstrap ? "no lobbies started - use /siege admin reload once the API is up" : "keeping the current configuration"));
            waiters.forEach(w -> w.sendMessage(SiegeMessages.bad("Siege configuration refresh failed: " + why)));
            return;
        }
        applyRuntimeConfig(config.get(), bootstrap);
        String summary = "Siege configuration refreshed: " + lobbies.size() + " lobby(ies) - " + lobbyKeys() + "."
                + (result.isStale() ? " (API unreachable: served the last cached copy)" : "");
        waiters.forEach(w -> w.sendMessage(SiegeMessages.good(summary)));
    }

    private void applyRuntimeConfig(KnkSiegeRuntimeConfig config, boolean bootstrap) {
        Set<Integer> seen = new HashSet<>();
        for (KnkSiegeLobby lobby : config.lobbies()) {
            if (lobby.mode() != SiegeLobbyMode.CONTINUOUS) {
                if (skippedModeLogged.add(lobby.id())) {
                    logger.info("[Siege] Lobby " + lobby.key() + " is " + lobby.mode() + "; only Continuous lobbies run (Scheduled is Phase 10)");
                }
                continue;
            }
            seen.add(lobby.id());
            SiegeLobbyRuntime rt = lobbies.get(lobby.id());
            if (rt == null) {
                createLobby(lobby, config, bootstrap);
                continue;
            }
            try {
                boolean applied = rt.machine().offerConfiguration(lobby, config.configuration());
                if (!applied) {
                    logger.info("[Siege] Lobby " + lobby.key() + " is in a match; new configuration applies at its next cooldown");
                }
            } catch (IllegalArgumentException e) {
                logger.warning("[Siege] Lobby " + lobby.key() + " configuration rejected (" + e.getMessage() + "); keeping the old one");
                continue;
            }
            // A lobby disabled for lack of a ready scenario starts once one exists (not if an admin stopped it).
            if (rt.phase() == SiegePhase.DISABLED && !adminStopped.contains(rt.id()) && rt.lobby().hasReadyScenario()) {
                apply(rt, rt.machine().start());
            }
        }
        for (SiegeLobbyRuntime rt : new ArrayList<>(lobbies.values())) {
            if (seen.contains(rt.id())) continue;
            if (rt.phase().isBetweenMatches()) {
                apply(rt, rt.machine().stop(SiegeEndReason.ADMIN_STOPPED));
                lobbies.remove(rt.id());
                logger.info("[Siege] Lobby " + rt.key() + " is no longer enabled; removed");
            } else {
                logger.info("[Siege] Lobby " + rt.key() + " is no longer enabled; it stops after the current round");
            }
        }
    }

    private void createLobby(KnkSiegeLobby lobby, KnkSiegeRuntimeConfig config, boolean bootstrap) {
        SiegeLobbyStateMachine machine;
        try {
            machine = new SiegeLobbyStateMachine(lobby, config.configuration(), locks, random);
        } catch (IllegalArgumentException e) {
            logger.warning("[Siege] Lobby " + lobby.key() + " skipped: " + e.getMessage());
            return;
        }
        SiegeLobbyRuntime rt = new SiegeLobbyRuntime(machine);
        lobbies.put(lobby.id(), rt);
        logger.info("[Siege] Lobby " + lobby.key() + " loaded (" + lobby.rotation().size() + " ready scenario(s), "
                + lobby.skippedScenarios().size() + " not ready)");
        Runnable begin = () -> {
            if (!shuttingDown && lobbies.get(lobby.id()) == rt && rt.phase() == SiegePhase.DISABLED) {
                apply(rt, rt.machine().start());
            }
        };
        if (bootstrap) Bukkit.getScheduler().runTaskLater(plugin, begin, BOOTSTRAP_START_DELAY_TICKS);
        else begin.run();
    }

    // ==================== Queries ====================

    public List<SiegeLobbyRuntime> lobbies() {
        return List.copyOf(lobbies.values());
    }

    public Optional<SiegeLobbyRuntime> lobbyByKey(String key) {
        if (key == null) return Optional.empty();
        String wanted = key.trim().toLowerCase(Locale.ROOT);
        return lobbies.values().stream()
                .filter(l -> l.key() != null && l.key().toLowerCase(Locale.ROOT).equals(wanted))
                .findFirst();
    }

    /** The lobby the player is a member of (any phase). */
    public Optional<SiegeLobbyRuntime> lobbyOf(UUID playerId) {
        return locks.lobbyOfPlayer(playerId).map(lobbies::get).filter(rt -> rt.isMember(playerId));
    }

    /** The lobby when the player is snapshotted and away (HUB or IN_PROGRESS: filter, guards, lockdown). */
    public Optional<SiegeLobbyRuntime> activeLobbyOf(UUID playerId) {
        return lobbyOf(playerId).filter(rt -> rt.phase().isMatchActive());
    }

    /** The running match the player plays in (IN_PROGRESS and on the roster). */
    public Optional<SiegeMatch> runningMatchOf(UUID playerId) {
        return lobbyOf(playerId)
                .filter(rt -> rt.phase() == SiegePhase.IN_PROGRESS)
                .flatMap(SiegeLobbyRuntime::match)
                .filter(m -> m.roster().contains(playerId));
    }

    /** Tokens of every match running now (5c: siege markers of other tokens are stale). */
    public Set<String> activeMatchTokens() {
        Set<String> tokens = new HashSet<>();
        lobbies.values().forEach(rt -> rt.match().ifPresent(m -> tokens.add(m.matchToken())));
        return tokens;
    }

    public SiegeRuntimeLocks locks() {
        return locks;
    }

    public SiegePlayerVault vault() {
        return vault;
    }

    /**
     * Siege permission check through the in-house system ({@link KnkPermissible}: op bypass, grants,
     * groups; fails closed when nothing is granted). {@code knk.siege.play} alone also accepts its
     * {@code plugin.yml} default ({@code true}, DESIGN §11.1): otherwise no ordinary player could
     * join until someone granted the node to everyone.
     */
    public boolean hasPermission(Player player, String node) {
        if (permissions.hasPermission(player, node)) return true;
        return PERMISSION_PLAY.equals(node) && player.hasPermission(node);
    }

    public SiegeTitleRanks titleRanks() {
        return titleRanks;
    }

    // ==================== Helpers ====================

    private int experienceOf(UUID id) {
        return userCache.getStale(id).map(UserSummary::experiencePoints).orElse(0);
    }

    private String lobbyKeys() {
        return lobbies.isEmpty() ? "none" : String.join(", ", lobbies.values().stream().map(SiegeLobbyRuntime::key).toList());
    }

    private Component unknownLobby(String key) {
        return SiegeMessages.bad("There is no siege lobby '" + key + "'. Lobbies: " + lobbyKeys() + ".");
    }

    static String scenarioName(KnkSiegeScenario scenario) {
        return SiegeDisplayText.clean(scenario.name(), "Scenario " + scenario.id());
    }

    private static String objectiveSummary(KnkSiegeScenario scenario) {
        List<String> names = new ArrayList<>();
        for (KnkSiegeObjective o : scenario.objectives()) {
            names.add(SiegeDisplayText.clean(o.name(), "#" + o.id()) + (o.instantVictory() ? " (main)" : ""));
        }
        return names.isEmpty() ? "none" : String.join(", ", names);
    }

    private void announceCapture(SiegeLobbyRuntime rt, SiegeMatch match, CaptureEvent capture) {
        KnkSiegeScenario scenario = match.scenario();
        String objective = scenario.objective(capture.objectiveId()).map(o -> SiegeDisplayText.clean(o.name(), "#" + o.id())).orElse("?");
        KnkSiegeTeam newHolder = scenario.team(capture.newHolderTeamId()).orElse(null);
        Player capturer = Bukkit.getPlayer(capture.capturerId());
        String capturerName = capturer != null ? capturer.getName() : "Someone";
        int winnerAlliance = match.alliances().allianceOf(capture.newHolderTeamId());
        int loserAlliance = match.alliances().knows(capture.previousHolderTeamId())
                ? match.alliances().allianceOf(capture.previousHolderTeamId()) : Integer.MIN_VALUE;
        for (UUID id : match.roster().playerIds()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            int alliance = match.alliances().allianceOf(match.roster().teamOf(id).orElse(capture.newHolderTeamId()));
            if (alliance == winnerAlliance) {
                p.sendMessage(SiegeMessages.good(capturerName + " captured " + objective + "!"));
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            } else if (alliance == loserAlliance) {
                p.sendMessage(SiegeMessages.prefixed(Component.text("Lost objective " + objective + " to ", SiegeMessages.BAD)
                        .append(SiegeBukkit.teamComponent(newHolder)).append(Component.text("!", SiegeMessages.BAD))));
                p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 1f);
            } else {
                p.sendMessage(SiegeMessages.prefixed(SiegeBukkit.teamComponent(newHolder)
                        .append(Component.text(" captured " + objective + ".", SiegeMessages.INFO))));
            }
        }
    }

    void sendToMembers(SiegeLobbyRuntime rt, Component message) {
        for (UUID id : rt.mutableMembers()) tell(id, message);
    }

    private static void tell(UUID id, Component message) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) p.sendMessage(message);
    }

    private void notifyChanged(SiegeLobbyRuntime rt) {
        observers.forEach(o -> safely("lobbyChanged", () -> o.lobbyChanged(rt)));
    }

    /** Runs once the round's match id is known (Phase 6); no-op when the draw never happened. */
    private void withMatchId(SiegeLobbyRuntime rt, java.util.function.Function<Long, CompletableFuture<?>> call) {
        CompletableFuture<Long> matchId = rt.matchIdFuture();
        if (matchId == null) return;
        matchId.thenCompose(id -> id == null ? CompletableFuture.completedFuture(null) : call.apply(id))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "[Siege] Match API call failed for lobby " + rt.key(), ex);
                    return null;
                });
    }

    /** Never lets a failed Phase 6 call break the runtime: logs and completes with null. */
    private <T> CompletableFuture<T> guarded(String what, CompletableFuture<T> future) {
        if (future == null) return CompletableFuture.completedFuture(null);
        return future.exceptionally(ex -> {
            logger.log(Level.WARNING, "[Siege] Match API " + what + " failed", ex);
            return null;
        });
    }

    private void safely(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "[Siege] Observer " + what + " failed", e);
        }
    }

    /** For listeners: run on the main thread later (e.g. after other join handlers). */
    public void later(long ticks, Runnable task) {
        Bukkit.getScheduler().runTaskLater(plugin, task, ticks);
    }

    /** Consumer form for the vault's after-restore hook and similar. */
    public Consumer<Player> onlineOnly(Consumer<Player> action) {
        return p -> {
            if (p != null && p.isOnline()) action.accept(p);
        };
    }

    public Plugin plugin() {
        return plugin;
    }
}
