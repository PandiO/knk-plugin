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
import net.knightsandkings.knk.paper.siege.SiegeMessages;
import net.knightsandkings.knk.paper.siege.SiegeService;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.EquipmentMode;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.EquipmentSpec;
import net.knightsandkings.knk.paper.siege.survival.kits.SurvivalKitPicker;
import net.knightsandkings.knk.paper.siege.survival.mysterybox.MysteryBoxAnimation;
import net.knightsandkings.knk.paper.siege.survival.mysterybox.MysteryBoxStations;
import net.knightsandkings.knk.paper.siege.survival.purchase.PurchaseShop;
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
import java.util.OptionalInt;
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
    // Phase 4 (link S7b): optional services; the per-match shops and Mystery Box stations by lobby id.
    private SurvivalEconomyServices economy;
    private final Map<Integer, PurchaseShop> shopByLobby = new LinkedHashMap<>();
    private final Map<Integer, MysteryBoxStations> boxesByLobby = new LinkedHashMap<>();
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

    /** Phase 4 (link S7b): installs the economy services (kits, shops, Mystery Box, match items). Null = features off. */
    public void setEconomyServices(SurvivalEconomyServices services) {
        this.economy = services;
    }

    public Optional<SurvivalEconomyServices> economyServices() {
        return Optional.ofNullable(economy);
    }

    public Optional<SurvivalKitPicker> kitPicker() {
        return economyServices().map(SurvivalEconomyServices::kitPicker);
    }

    public Optional<PurchaseShop> shopOf(SurvivalMatch match) {
        return Optional.ofNullable(shopByLobby.get(match.lobby().id()));
    }

    public Optional<MysteryBoxStations> boxesOf(SurvivalMatch match) {
        return Optional.ofNullable(boxesByLobby.get(match.lobby().id()));
    }

    /** The running survival match of a match actor (mob). */
    public Optional<SurvivalMatch> matchOfActor(Entity entity) {
        return actors.actorOf(entity).flatMap(actor -> matchOf(actor.lobbyId()));
    }

    /** The lobby a player is a member of (any phase), for the kit picker before the match starts. */
    public Optional<SiegeLobbyRuntime> lobbyOfMember(UUID playerId) {
        return siegeService.lobbyOf(playerId);
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
        if (economy != null) {
            int entities = 0;
            int items = 0;
            for (World world : Bukkit.getWorlds()) {
                entities += MysteryBoxAnimation.sweep(world.getEntities(), MysteryBoxAnimation.key(plugin));
                items += economy.items().sweep(world.getEntities());
            }
            if (entities + items > 0) logger.info("[Survival] Removed " + entities + " leftover Mystery Box entities and " + items + " match items");
        }
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
        shopByLobby.clear();
        boxesByLobby.clear();
        if (economy != null) {
            for (String key : economy.items().trackedMatches()) economy.items().removeGround(key, Bukkit.getWorlds());
        }
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
        try {
            startPhase4(lobby, match, survival);
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "[Survival] Lobby " + lobby.key() + ": equipment/economy setup failed", e);
        }
    }

    // ---- phase 4 (link S7b): equipment at the start, shops, Mystery Boxes, match items ----

    private void startPhase4(SiegeLobbyRuntime lobby, SiegeMatch match, SurvivalMatch survival) {
        if (economy == null) {
            logger.info("[Survival] Lobby " + lobby.key() + ": economy services not installed; equipment modes, shops and Mystery Boxes are off");
            return;
        }
        SurvivalConfiguration c = survival.config();
        if (!c.purchasePoints().isEmpty()) {
            PurchaseShop shop = new PurchaseShop(survival, economy.itemFactory(), SurvivalSpawner.Worlds.bukkit(), economy.mainThread(), logger);
            shopByLobby.put(lobby.id(), shop);
            survival.addHook(shop);
            shop.prepare();
        }
        if (!c.mysteryBoxPoints().isEmpty()) {
            MysteryBoxStations boxes = new MysteryBoxStations(survival, economy.matchApi(), lobby::apiMatchId, lobby::userIdOf,
                economy.itemFactory(), MysteryBoxAnimation.key(plugin), SurvivalSpawner.Worlds.bukkit(),
                MysteryBoxAnimation.TickScheduler.bukkit(plugin), MysteryBoxAnimation.Effects.bukkit(), economy.mainThread(),
                economy.reelMaxStepTicks(), logger);
            boxesByLobby.put(lobby.id(), boxes);
            survival.addHook(boxes);
            boxes.start();
        }
        economy.kitPicker().lock(lobby.id());
        for (UUID id : match.roster().playerIds()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) grantEquipment(lobby, survival, player);
        }
        logger.info("[Survival] Lobby " + lobby.key() + ": equipment mode " + c.equipment().mode() + ", " + c.purchasePoints().size()
            + " purchase points, " + c.mysteryBoxPoints().size() + " Mystery Box points, match key " + survival.matchKey());
    }

    /** The entry loadout (DESIGN §8 table): kits mode grants the picked/default kit once; match progression the baseline. */
    private void grantEquipment(SiegeLobbyRuntime lobby, SurvivalMatch survival, Player player) {
        EquipmentSpec equipment = survival.config().equipment();
        OptionalInt kitId;
        boolean defaulted = false;
        switch (equipment.mode()) {
            case OWN_INVENTORY -> {
                return;
            }
            case KITS -> {
                var selection = economy.kitPicker().selection(lobby.id()).orElseGet(() -> economy.kitPicker().begin(lobby.id(), equipment.newSelection()));
                var resolved = selection.resolve(player.getUniqueId());
                if (resolved.isEmpty()) {
                    logger.warning("[Survival] Lobby " + lobby.key() + ": kits mode without a kit for " + player.getName() + "; empty inventory");
                    economy.kitGrants().clear(player);
                    return;
                }
                kitId = OptionalInt.of(resolved.get().kitId());
                defaulted = resolved.get().defaulted();
            }
            case MATCH_PROGRESSION -> kitId = equipment.baselineKitId();
            default -> {
                return;
            }
        }
        final boolean usedDefault = defaulted;
        String kitLabel = kitId.isPresent() ? "kit " + kitId.getAsInt() + (usedDefault ? " (default)" : "") : "empty inventory";
        economy.kitGrants().grant(player, kitId, survival.matchKey()).whenComplete((outcome, ex) -> {
            if (ex != null) {
                logger.log(Level.WARNING, "[Survival] Lobby " + lobby.key() + ": " + kitLabel + " for " + player.getName() + " failed", ex);
                return;
            }
            logger.info("[Survival] Lobby " + lobby.key() + ": " + equipment.mode() + " → " + kitLabel + " for " + player.getName()
                + (outcome.kitFound() ? ": " + outcome.placed() + " placed, " + outcome.dropped() + " dropped" : ": KIT MISSING, empty inventory"));
            if (outcome.kitFound() && kitId.isPresent()) {
                player.sendMessage(SiegeMessages.info(equipment.mode() == EquipmentMode.KITS
                    ? "Your kit is in your inventory" + (usedDefault ? " (the default kit)" : "") + "; it stays with you on death and goes away after the match."
                    : "Your baseline gear is in your inventory; it goes away after the match."));
            } else if (!outcome.kitFound()) {
                player.sendMessage(SiegeMessages.bad("Your kit could not be granted (it no longer exists); you start empty-handed. Tell an admin."));
            }
        });
    }

    @Override
    public void memberReadiedAtHub(SiegeLobbyRuntime lobby, KnkSiegeScenario scenario, Player player) {
        if (economy == null) return;
        Optional<SurvivalConfiguration> config = hooks.configurationFor(lobby.id(), scenario);
        if (config.isEmpty() || config.get().equipment().mode() != EquipmentMode.KITS) return;
        EquipmentSpec equipment = config.get().equipment();
        var selection = economy.kitPicker().selection(lobby.id()).orElseGet(() -> economy.kitPicker().begin(lobby.id(), equipment.newSelection()));
        if (selection.locked()) return;
        if (!selection.worthAsking()) {
            player.sendMessage(SiegeMessages.info("Kits mode: you play with " + selection.options().stream().findFirst().map(o -> o.name()).orElse("the scenario kit") + "."));
            return;
        }
        player.sendMessage(SiegeMessages.info("Kits mode: pick your kit before the match starts (" + String.join(", ", SurvivalKitPicker.names(selection))
            + "); no pick = " + selection.defaultKitId().stream().mapToObj(id -> selection.option(id).map(o -> o.name()).orElse("#" + id)).findFirst().orElse("the first kit")
            + ". Use /siege kit [name] to open the picker again."));
        if (plugin.isEnabled()) {
            // After the hub teleport (the service teleports right after this callback; a menu opened now would close).
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && lobby.isMember(player.getUniqueId()) && !selection.locked()) economy.kitPicker().open(player, lobby.id());
            }, 5L);
        }
    }

    @Override
    public void memberJoinedMatch(SiegeLobbyRuntime lobby, SiegeMatch match, Player player) {
        matchOf(lobby.id()).ifPresent(m -> {
            m.memberJoined(player.getUniqueId());
            if (economy != null) grantEquipment(lobby, m, player);
        });
    }

    @Override
    public void memberRemoved(SiegeLobbyRuntime lobby, SiegeMatch match, Player player) {
        if (economy == null) return;
        matchOf(lobby.id()).ifPresent(m -> {
            int stripped = economy.items().stripFromPlayer(player, m.matchKey());
            if (stripped > 0) logger.info("[Survival] Lobby " + lobby.key() + ": removed " + stripped + " match item stacks from " + player.getName() + " before the restore");
        });
    }

    private void endPhase4(SiegeLobbyRuntime lobby, SurvivalMatch survival) {
        shopByLobby.remove(lobby.id());
        boxesByLobby.remove(lobby.id());
        if (economy == null) return;
        economy.kitPicker().forget(lobby.id());
        if (survival == null) return;
        int ground = economy.items().removeGround(survival.matchKey(), Bukkit.getWorlds());
        for (UUID id : survival.siege().roster().playerIds()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) economy.items().stripFromPlayer(p, survival.matchKey());
        }
        if (ground > 0) logger.info("[Survival] Lobby " + lobby.key() + ": removed " + ground + " match ground items");
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
        if (survival == null) {
            endPhase4(lobby, null);
            return;
        }
        try {
            survival.cleanup();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "[Survival] Cleanup of lobby " + lobby.key() + " failed", e);
        }
        endPhase4(lobby, survival);
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
        endPhase4(lobby, survival);
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
        if (economy != null) MysteryBoxAnimation.sweep(event.getEntities(), MysteryBoxAnimation.key(plugin));
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

    /** The running survival match a player plays in. */
    public Optional<SurvivalMatch> matchOfPlayer(UUID playerId) {
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
            + "; adopted mobs " + platform.adoptedCount() + "; match actors " + actors.size()
            + (economy == null ? "; economy services off" : "; match ground items tracked for " + economy.items().trackedMatches()));
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
