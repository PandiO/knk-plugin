package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.siege.ObjectiveState.CaptureEvent;
import net.knightsandkings.knk.core.survival.end.SurvivalEndReason;
import net.knightsandkings.knk.core.visibility.GhostTeam;
import net.knightsandkings.knk.paper.integration.WorldGuardIntegration;
import net.knightsandkings.knk.paper.npc.NpcPlatform;
import net.knightsandkings.knk.paper.siege.SiegeLobbyRuntime;
import net.knightsandkings.knk.paper.siege.SiegeMatch;
import net.knightsandkings.knk.paper.siege.SiegeMatchObserver;
import net.knightsandkings.knk.paper.siege.SiegeService;
import net.knightsandkings.knk.paper.siege.npc.MatchActorRegistry;
import net.knightsandkings.knk.paper.siege.npc.NpcGateDamage;
import net.knightsandkings.knk.paper.visibility.PlayerVisibilityArbiter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;

/**
 * The survival runtime of the plugin (PandiO/knk-workspace#41 link S5): one {@link SurvivalMatch} per lobby whose
 * drawn scenario has a survival configuration, started from the Siege observer callbacks and ticked every server
 * tick; the Bukkit events (deaths, respawns, mob deaths, damage, chunk loads) are routed to the matches. Lobbies
 * without a survival configuration are never touched. Main thread.
 */
public final class SurvivalMatchRuntime implements SiegeMatchObserver, Listener, SurvivalGhostListener.Ghosts {

    /** Refresh period of the scenario cache (also refreshed at the draw with the lobby id). */
    static final long CACHE_REFRESH_TICKS = 20L * 60 * 5;

    private final Plugin plugin;
    private final SiegeService siegeService;
    private final SurvivalConfigurationCache cache;
    private final NpcPlatform platform;
    private final MatchActorRegistry actors;
    private final GateManager gates;
    private final NpcGateDamage gateDamage;
    private final PlayerVisibilityArbiter arbiter;
    private final Supplier<WorldGuardIntegration> worldGuard;
    private final SurvivalSettings settings;
    private final RandomGenerator random;
    private final Logger logger;
    private final SurvivalSpawner spawner;
    private final SurvivalModeHooks hooks;

    private final Map<Integer, SurvivalMatch> matches = new LinkedHashMap<>();
    private final Map<Integer, SurvivalConfiguration> frozenByLobby = new LinkedHashMap<>();
    private final Map<Integer, ScenarioArea> areaByLobby = new LinkedHashMap<>();
    private BukkitTask ticker;
    private BukkitTask refresher;
    private boolean shuttingDown;

    public SurvivalMatchRuntime(Plugin plugin, SiegeService siegeService, SurvivalConfigurationCache cache, NpcPlatform platform,
                                MatchActorRegistry actors, GateManager gates, NpcGateDamage gateDamage, PlayerVisibilityArbiter arbiter,
                                Supplier<WorldGuardIntegration> worldGuard, SurvivalSettings settings, RandomGenerator random, Logger logger) {
        this.plugin = plugin;
        this.siegeService = siegeService;
        this.cache = cache;
        this.platform = platform;
        this.actors = actors;
        this.gates = gates;
        this.gateDamage = gateDamage;
        this.arbiter = arbiter;
        this.worldGuard = worldGuard == null ? () -> null : worldGuard;
        this.settings = settings;
        this.random = random;
        this.logger = logger;
        this.spawner = new SurvivalSpawner(platform, actors, SurvivalSpawner.Worlds.bukkit(), random, settings, logger);
        this.hooks = new SurvivalModeHooks(cache, this::matchOf, id -> Optional.ofNullable(frozenByLobby.get(id)), logger);
    }

    /** The hooks to install on the Siege service. */
    public SurvivalModeHooks hooks() {
        return hooks;
    }

    public SurvivalConfigurationCache cache() {
        return cache;
    }

    public SurvivalSettings settings() {
        return settings;
    }

    public Optional<SurvivalMatch> matchOf(int lobbyId) {
        return Optional.ofNullable(matches.get(lobbyId));
    }

    public Collection<SurvivalMatch> matches() {
        return List.copyOf(matches.values());
    }

    /** Starts the ticker, the cache refresh and the leftover sweep of the loaded worlds (S61: a restart leaves no mobs). */
    public void start() {
        cache.refreshAll();
        ticker = Bukkit.getScheduler().runTaskTimer(plugin,
            net.knightsandkings.knk.core.metrics.PluginMetrics.timed("siege.survival.tick", this::tick), 1L, 1L);
        refresher = Bukkit.getScheduler().runTaskTimer(plugin, cache::refreshAll, CACHE_REFRESH_TICKS, CACHE_REFRESH_TICKS);
        int swept = 0;
        for (World world : Bukkit.getWorlds()) {
            swept += sweep(world.getEntities());
        }
        if (swept > 0) logger.info("[Survival] Removed " + swept + " leftover match mobs from the loaded worlds");
    }

    /**
     * Stops everything: every running survival match is cleaned up. Called by the Siege shutdown (observer) after it
     * aborted the matches, and again by the plugin's onDisable; idempotent.
     */
    @Override
    public void shutdown() {
        shuttingDown = true;
        if (ticker != null) ticker.cancel();
        if (refresher != null) refresher.cancel();
        for (SurvivalMatch match : new ArrayList<>(matches.values())) {
            try {
                match.cleanup();
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "[Survival] Cleanup of lobby " + match.lobby().key() + " failed", e);
            }
        }
        matches.clear();
        frozenByLobby.clear();
        areaByLobby.clear();
    }

    private void tick() {
        if (shuttingDown) return;
        for (SurvivalMatch match : new ArrayList<>(matches.values())) {
            try {
                match.tick();
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "[Survival] Tick of lobby " + match.lobby().key() + " failed", e);
            }
        }
    }

    // ---- Siege observer ----

    @Override
    public void scenarioDrawn(SiegeLobbyRuntime lobby, KnkSiegeScenario scenario) {
        frozenByLobby.remove(lobby.id());
        if (cache.knownPvp(scenario.id())) return;
        // Refresh with the lobby id (its all-captured override); whatever is cached when the match starts is frozen.
        cache.refresh(scenario.id(), lobby.id(), result -> result.ifPresent(c -> {
            if (lobby.drawnScenario().map(s -> s.id() == scenario.id()).orElse(false)) frozenByLobby.put(lobby.id(), c);
        }));
    }

    @Override
    public void matchStarted(SiegeLobbyRuntime lobby, SiegeMatch match) {
        Optional<SurvivalConfiguration> config = hooks.configurationFor(lobby.id(), match.scenario());
        if (config.isEmpty()) return; // ordinary PvP Siege
        SurvivalConfiguration c = config.get();
        if (!c.readiness().ready()) {
            logger.warning("[Survival] Lobby " + lobby.key() + " starts scenario " + c.scenarioId() + " although its survival readiness"
                + " reports errors " + c.readiness().errors() + " (the lobby-level configuration changed after the draw)");
        }
        int humanTeam = hooks.singleTeam(lobby, match.scenario()).orElse(c.humanTeamId());
        GhostTeam team = new GhostTeam("siege:" + lobby.id() + ":" + humanTeam,
            id -> match.roster().teamOf(id).orElse(-1) == humanTeam);
        SurvivalGhosts ghosts = new SurvivalGhosts(arbiter, team, siegeService::respawnLocation);
        SurvivalMatch.Services services = new SurvivalMatch.Services(spawner, platform, actors, gateDamage, gates,
            Bukkit::getPlayer, System::currentTimeMillis, random, logger);
        SurvivalMatch survival = new SurvivalMatch(lobby, match, c, settings, services, ghosts,
            reason -> requestEnd(lobby, reason));
        matches.put(lobby.id(), survival);
        areaByLobby.put(lobby.id(), ScenarioArea.of(match.scenario(), worldGuard.get()));
        try {
            survival.start();
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "[Survival] Lobby " + lobby.key() + " could not start the survival match", e);
        }
    }

    /** The survival side ended: the Siege match ends next tick (never re-entrantly from inside a Siege callback). */
    private void requestEnd(SiegeLobbyRuntime lobby, SurvivalEndReason reason) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (matches.containsKey(lobby.id())) siegeService.endMatch(lobby, SurvivalEndMapping.siegeReason(reason));
        });
    }

    @Override
    public void objectiveCaptured(SiegeLobbyRuntime lobby, SiegeMatch match, CaptureEvent capture) {
        matchOf(lobby.id()).ifPresent(m -> m.onCapture(capture));
    }

    @Override
    public void memberLeft(SiegeLobbyRuntime lobby, UUID playerId, Integer userId, String cause, boolean duringMatch) {
        matchOf(lobby.id()).ifPresent(m -> {
            m.ghosts().release(playerId, Bukkit.getPlayer(playerId), false);
            // The roster drops the member after this callback: evaluate on the next tick.
            if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> matchOf(lobby.id()).ifPresent(SurvivalMatch::evaluateEnd));
        });
    }

    @Override
    public void matchEnded(SiegeLobbyRuntime lobby, SiegeMatch match) {
        SurvivalMatch survival = matches.remove(lobby.id());
        areaByLobby.remove(lobby.id());
        frozenByLobby.remove(lobby.id());
        if (survival == null) return;
        try {
            survival.cleanup();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "[Survival] Cleanup of lobby " + lobby.key() + " failed", e);
        }
        logger.info("[Survival] Lobby " + lobby.key() + " survival match over: " + survival.endReason().map(Enum::name).orElse("?")
            + ", waves " + survival.record());
    }

    @Override
    public void roundReleased(SiegeLobbyRuntime lobby) {
        SurvivalMatch survival = matches.remove(lobby.id());
        areaByLobby.remove(lobby.id());
        frozenByLobby.remove(lobby.id());
        if (survival != null) survival.cleanup();
        actors.clear(lobby.id());
    }

    // ---- Bukkit events ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        matchOfPlayer(victim.getUniqueId()).ifPresent(m -> m.onPlayerDeath(victim));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        matchOfPlayer(player.getUniqueId()).ifPresent(m -> m.onPlayerRespawn(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        actors.actorOf(mob).ifPresent(actor -> {
            event.getDrops().clear();
            event.setDroppedExp(0);
            matchOf(actor.lobbyId()).ifPresent(m -> m.onMobDeath(mob, mob.getKiller()));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) damager = shooter;
        if (!(damager instanceof Mob)) return;
        Entity source = damager;
        actors.actorOf(source).ifPresent(actor -> matchOf(actor.lobbyId()).ifPresent(m -> m.onMobDealtDamage(source)));
    }

    /** Leftover match mobs from an earlier run (tagged, not registered) are removed when their chunk loads (S61). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        sweep(event.getEntities());
    }

    int sweep(Collection<? extends Entity> entities) {
        int removed = 0;
        for (Entity entity : entities) {
            if (!(entity instanceof Mob)) continue;
            if (actors.tagOf(entity).isEmpty() || actors.actorOf(entity).isPresent()) continue;
            entity.remove();
            removed++;
        }
        return removed;
    }

    private Optional<SurvivalMatch> matchOfPlayer(UUID playerId) {
        return siegeService.activeLobbyOf(playerId).flatMap(rt -> matchOf(rt.id()));
    }

    // ---- ghosts (SurvivalGhostListener.Ghosts) ----

    @Override
    public boolean isGhost(UUID playerId) {
        for (SurvivalMatch m : matches.values()) {
            if (m.ghosts().isGhost(playerId)) return true;
        }
        return false;
    }

    @Override
    public Optional<SurvivalGhostListener.GhostContext> contextOf(UUID playerId) {
        for (SurvivalMatch m : matches.values()) {
            if (m.ghosts().isGhost(playerId)) {
                ScenarioArea area = areaByLobby.getOrDefault(m.lobby().id(), ScenarioArea.EVERYWHERE);
                return Optional.of(new SurvivalGhostListener.GhostContext(m.ghosts(), area, siegeService::respawnLocation));
            }
        }
        return Optional.empty();
    }

    /** Admin status: every running survival match, or a note when there is none. */
    public List<String> statusLines(String lobbyKey) {
        List<String> lines = new ArrayList<>();
        lines.add("Survival scenarios known: " + cache.survivalScenarioIds() + "; running survival matches: " + matches.size()
            + "; adopted mobs " + platform.adoptedCount() + "; match actors " + actors.size());
        for (SurvivalMatch m : matches.values()) {
            if (lobbyKey != null && !lobbyKey.isBlank() && !m.lobby().key().equalsIgnoreCase(lobbyKey)) continue;
            lines.addAll(m.statusLines());
        }
        return lines;
    }

    /** The scenario area of a lobby's running match (tests). */
    Optional<ScenarioArea> areaOf(int lobbyId) {
        return Optional.ofNullable(areaByLobby.get(lobbyId));
    }

    /** Where a ghost is sent back to (S60): the Siege respawn resolution. */
    Optional<Location> spawnOf(Player player) {
        return siegeService.respawnLocation(player);
    }
}
