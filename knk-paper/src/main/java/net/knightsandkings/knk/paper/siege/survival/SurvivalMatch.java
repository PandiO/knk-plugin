package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.siege.ObjectiveState.CaptureEvent;
import net.knightsandkings.knk.core.survival.SurvivalPoint;
import net.knightsandkings.knk.core.survival.economy.KillBlow;
import net.knightsandkings.knk.core.survival.economy.MatchEconomy;
import net.knightsandkings.knk.paper.siege.survival.economy.KillBlows;
import net.knightsandkings.knk.core.survival.SurvivalSubstate;
import net.knightsandkings.knk.core.survival.end.EndConditionEvaluator;
import net.knightsandkings.knk.core.survival.end.EndConditionSnapshot;
import net.knightsandkings.knk.core.survival.end.SurvivalEndReason;
import net.knightsandkings.knk.core.survival.end.SurvivalObjective;
import net.knightsandkings.knk.core.survival.scaling.ScaledStat;
import net.knightsandkings.knk.core.survival.scaling.WaveScaling;
import net.knightsandkings.knk.core.survival.spawn.EnemyIdSequence;
import net.knightsandkings.knk.core.survival.spawn.SpawnFrontier;
import net.knightsandkings.knk.core.survival.spawn.SpawnZone;
import net.knightsandkings.knk.core.survival.spawn.SpawnZoneSelector;
import net.knightsandkings.knk.core.survival.spawn.WavePopulationPlanner;
import net.knightsandkings.knk.core.survival.stuck.ProgressSample;
import net.knightsandkings.knk.core.survival.stuck.StuckDecision;
import net.knightsandkings.knk.core.survival.stuck.StuckEvent;
import net.knightsandkings.knk.core.survival.stuck.StuckMonitor;
import net.knightsandkings.knk.core.survival.stuck.StuckTelemetrySink;
import net.knightsandkings.knk.core.survival.wave.Intermission;
import net.knightsandkings.knk.core.survival.wave.KillCause;
import net.knightsandkings.knk.core.survival.wave.PlannedEnemy;
import net.knightsandkings.knk.core.survival.wave.RoundSpawnQueue;
import net.knightsandkings.knk.core.survival.wave.SurvivalRounds;
import net.knightsandkings.knk.core.survival.wave.WaveRecord;
import net.knightsandkings.knk.core.targeting.EnemyGoalMachine;
import net.knightsandkings.knk.core.targeting.EnemyGoalMachine.Action;
import net.knightsandkings.knk.core.targeting.EnemyGoalMachine.Decision;
import net.knightsandkings.knk.core.targeting.EnemyGoalMachine.Observation;
import net.knightsandkings.knk.core.targeting.TargetCandidate;
import net.knightsandkings.knk.core.targeting.TargetKind;
import net.knightsandkings.knk.core.targeting.TargetPolicy;
import net.knightsandkings.knk.paper.npc.NpcPlatform;
import net.knightsandkings.knk.paper.siege.SiegeLobbyRuntime;
import net.knightsandkings.knk.paper.siege.SiegeMatch;
import net.knightsandkings.knk.paper.siege.SiegeMessages;
import net.knightsandkings.knk.paper.siege.npc.MatchActorRegistry;
import net.knightsandkings.knk.paper.siege.npc.NpcGateDamage;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.ZoneSpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;

/**
 * One running survival match on a Siege lobby (DESIGN §3 lifecycle, §3a end conditions, §3c stuck handling, §4
 * targeting, §6 spawnzones, §7 scaling). The S1 core classes are the engine; this class drives them from the server
 * tick and executes their decisions on the mobs. Main thread only.
 *
 * <p>Substates: PREPARING (a short countdown after the Siege start) → WAVE_ACTIVE → INTERMISSION → WAVE_ACTIVE …
 * → ENDING. The end conditions are evaluated after every state change ({@link #evaluateEnd()}); the match then
 * asks the runtime to end the Siege match ({@code onEnd}), which restores players and gates through the ordinary
 * Siege ending.
 */
public final class SurvivalMatch {

    /** What the match needs from the server. */
    public record Services(SurvivalSpawner spawner, NpcPlatform platform, MatchActorRegistry actors, NpcGateDamage gateDamage,
                           GateManager gates, Function<UUID, Player> onlinePlayer, LongSupplier clockMillis,
                           RandomGenerator random, Logger logger) {
    }

    private final SiegeLobbyRuntime lobby;
    private final SiegeMatch siege;
    private final SurvivalConfiguration config;
    private final SurvivalSettings settings;
    private final Services services;
    private final SurvivalGhosts ghosts;
    private final SurvivalTargeting targeting;
    private final Consumer<SurvivalEndReason> onEnd;
    private final TargetPolicy policy;

    private final SurvivalRounds rounds;
    private final WaveScaling scaling;
    private final WavePopulationPlanner planner;
    private final EnemyIdSequence ids = new EnemyIdSequence();
    private final SpawnFrontier frontier;
    private final SpawnZoneSelector selector;
    private final EndConditionEvaluator evaluator;
    private final int npcAllianceGroup;

    private final Map<UUID, SurvivalEnemy> enemiesByMob = new LinkedHashMap<>();
    private final Map<Long, SurvivalEnemy> enemiesById = new LinkedHashMap<>();
    // Phase 4 (link S7b): the match economy (one ledger per match, S44), the last player hits and the feature hooks.
    private final MatchEconomy economy;
    private final KillBlows killBlows = new KillBlows();
    private final List<MatchHook> hooks = new ArrayList<>();

    private StuckMonitor stuck;
    private Map<ScaledStat, Double> roundMultipliers = Map.of();
    private int playersAtRoundStart;
    private long prepareUntilMillis;
    private long nextSpawnAtMillis;
    private int lastAnnouncedSecond = -1;
    private SurvivalEndReason endReason;
    private boolean ending;
    private boolean endRequested;
    private int spawnFailures;
    private long startedAtMillis;

    public SurvivalMatch(SiegeLobbyRuntime lobby, SiegeMatch siege, SurvivalConfiguration config, SurvivalSettings settings,
                         Services services, SurvivalGhosts ghosts, Consumer<SurvivalEndReason> onEnd) {
        this.lobby = Objects.requireNonNull(lobby, "lobby");
        this.siege = Objects.requireNonNull(siege, "siege");
        this.config = Objects.requireNonNull(config, "config");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.services = Objects.requireNonNull(services, "services");
        this.ghosts = Objects.requireNonNull(ghosts, "ghosts");
        this.onEnd = Objects.requireNonNull(onEnd, "onEnd");
        this.policy = settings.policyFor(config.difficulty());
        this.targeting = new SurvivalTargeting(config, siege, services.gates(), ghosts::isGhost);
        this.rounds = new SurvivalRounds(Math.max(1_000L, config.intermissionMillis()));
        this.scaling = new WaveScaling(config.difficulty().scaling());
        this.planner = new WavePopulationPlanner(scaling, config.monsterMix(), config.specialSchedule(), config.specialProfiles());
        this.frontier = new SpawnFrontier(config.spawnZones(), config.allowRecapture());
        this.selector = new SpawnZoneSelector(config.favorNearPlayersPercent(), config.nearZoneCount());
        this.evaluator = new EndConditionEvaluator(config.endConditions());
        this.npcAllianceGroup = siege.alliances().knows(config.npcTeamId()) ? siege.alliances().allianceOf(config.npcTeamId()) : -1;
        this.economy = new MatchEconomy(siege.matchToken() == null ? ("lobby-" + lobby.id()) : siege.matchToken(), config.killRewards());
        for (UUID id : siege.roster().playerIds()) economy.join(id);
    }

    /** The match clock (milliseconds). */
    public long now() {
        return services.clockMillis().getAsLong();
    }

    /** The match's id for ledgers and item tags: the Siege match token. */
    public String matchKey() {
        return economy.matchId();
    }

    public MatchEconomy economy() {
        return economy;
    }

    public KillBlows killBlows() {
        return killBlows;
    }

    /** Registers a phase 4 feature (shops, boxes, kits) for this match's events. */
    public void addHook(MatchHook hook) {
        hooks.add(Objects.requireNonNull(hook, "hook"));
    }

    /** A member joined the running match (rejoin/admin join): a balance from 0. */
    public void memberJoined(UUID playerId) {
        economy.join(playerId);
    }

    // ---- accessors ----

    public SiegeLobbyRuntime lobby() {
        return lobby;
    }

    public SiegeMatch siege() {
        return siege;
    }

    public SurvivalConfiguration config() {
        return config;
    }

    public SurvivalGhosts ghosts() {
        return ghosts;
    }

    public SurvivalRounds rounds() {
        return rounds;
    }

    public SpawnFrontier frontier() {
        return frontier;
    }

    public Optional<SurvivalEndReason> endReason() {
        return Optional.ofNullable(endReason);
    }

    public boolean ending() {
        return ending;
    }

    public int npcAllianceGroup() {
        return npcAllianceGroup;
    }

    public Collection<SurvivalEnemy> enemies() {
        return enemiesByMob.values();
    }

    public Optional<SurvivalEnemy> enemyOf(UUID mobId) {
        return Optional.ofNullable(enemiesByMob.get(mobId));
    }

    public WaveRecord record() {
        return rounds.record();
    }

    // ---- lifecycle ----

    /** The Siege match started: PREPARING for one intermission length, then wave 1 (DESIGN §3 step 3). */
    public void start() {
        long now = services.clockMillis().getAsLong();
        startedAtMillis = now;
        prepareUntilMillis = now + Math.max(1_000L, config.intermissionMillis());
        frontier.update(targeting.npcHeldObjectiveIds());
        broadcast(SiegeMessages.prefixed(Component.text("Survival: ", SiegeMessages.HIGHLIGHT)
            .append(Component.text("defend " + config.scenarioName() + " against the waves. Wave 1 starts in "
                + SiegeMessages.duration((int) Math.ceil((prepareUntilMillis - now) / 1000.0)) + ".", SiegeMessages.INFO))));
        log("match started: scenario " + config.scenarioId() + ", difficulty " + config.difficulty().name() + ", "
            + siege.roster().size() + " players, active zones " + frontier.activeZoneIds());
    }

    /** One server tick. */
    public void tick() {
        long tickNow = services.clockMillis().getAsLong();
        killBlows.prune(tickNow);
        for (MatchHook hook : hooks) hook.ticked(tickNow);
        if (ending) return;
        long now = services.clockMillis().getAsLong();
        switch (rounds.substate()) {
            case PREPARING -> {
                announceCountdown("Wave 1 starts in ", prepareUntilMillis, now);
                if (now >= prepareUntilMillis) startWave(now);
            }
            case INTERMISSION -> {
                rounds.intermission().ifPresent(i -> announceCountdown("Round " + rounds.nextRound() + " starts in ", i.endMillis(), now));
                if (rounds.intermissionOver(now)) startWave(now);
            }
            default -> { }
        }
        // A round that just started spawns its first enemy on the same tick.
        if (!ending && rounds.substate() == SurvivalSubstate.WAVE_ACTIVE) tickWave(now);
    }

    private void startWave(long now) {
        playersAtRoundStart = siege.roster().size(); // S55: every player in the match, ghosts included
        int round = rounds.nextRound();
        List<PlannedEnemy> planned;
        try {
            planned = planner.plan(round, Math.max(1, playersAtRoundStart), ids, services.random());
        } catch (IllegalStateException e) {
            log("round " + round + " cannot be planned (" + e.getMessage() + "); ending the match");
            end(SurvivalEndReason.TECHNICAL_LIMIT);
            return;
        }
        roundMultipliers = scaling.statMultipliers(round, Math.max(1, playersAtRoundStart));
        RoundSpawnQueue queue = rounds.startWave(planned, config.difficulty().scaling().maxAlive());
        stuck = new StuckMonitor(config.stuck(), telemetry());
        nextSpawnAtMillis = now;
        lastAnnouncedSecond = -1;
        boolean special = planned.stream().anyMatch(PlannedEnemy::special);
        List<UUID> releasedAtWave = ghosts.onTransition(SurvivalSubstate.WAVE_ACTIVE, services.onlinePlayer());
        ghostsReleased(releasedAtWave);
        Title title = Title.title(Component.text("Round " + round, NamedTextColor.DARK_RED),
            Component.text(queue.plannedCount() + (special ? " enemies (special wave)" : " enemies"), SiegeMessages.INFO));
        members().forEach(p -> p.showTitle(title));
        log("round " + round + " started: " + queue.plannedCount() + " planned, max alive " + config.difficulty().scaling().maxAlive()
            + ", stats " + describe(roundMultipliers) + ", players " + playersAtRoundStart);
        evaluateEnd();
    }

    private void tickWave(long now) {
        RoundSpawnQueue queue = rounds.currentQueue().orElse(null);
        if (queue == null) return;
        // Spawn pacing (DESIGN §7: the rest of the queue spawns as others die; one spawn per interval).
        if (queue.canSpawn() && now >= nextSpawnAtMillis) {
            nextSpawnAtMillis = now + config.difficulty().spawnIntervalMillis();
            queue.nextSpawn(now).ifPresent(enemy -> spawn(enemy, queue, now, false));
        }
        // Enemies.
        for (SurvivalEnemy enemy : new ArrayList<>(enemiesByMob.values())) {
            Mob mob = enemy.mob();
            if (!mob.isValid() || mob.isDead()) {
                // Gone without a death event (unloaded, despawned by something else): dead for completion, not a kill.
                removeEnemy(enemy, KillCause.ENVIRONMENT, queue, "vanished");
                continue;
            }
            tickEnemy(enemy, queue, now);
        }
        // Rules 2 and 4 of §3c.
        if (stuck != null) {
            for (long id : stuck.lastEnemies(queue.isQueueExhausted(), queue.aliveIds(), now)) {
                SurvivalEnemy enemy = enemiesById.get(id);
                if (enemy == null) continue;
                enemy.setGlowing(true);
                enemy.setPursuesDirectly(true);
                enemy.mob().setGlowing(true);
            }
            OptionalLong lastSpawn = queue.lastPlannedSpawnMillis().map(OptionalLong::of).orElse(OptionalLong.empty());
            for (long id : stuck.backstop(lastSpawn, queue.aliveIds(), now)) {
                SurvivalEnemy enemy = enemiesById.get(id);
                if (enemy != null) removeEnemy(enemy, KillCause.SYSTEM, queue, "backstop");
                else queue.recordDeath(id, KillCause.SYSTEM);
            }
        }
        completeWaveIfDone(now);
    }

    private void completeWaveIfDone(long now) {
        if (ending) return;
        Optional<Intermission> intermission = rounds.completeWaveIfDone(now);
        if (intermission.isEmpty()) return;
        int completed = rounds.record().completed();
        lastAnnouncedSecond = -1;
        List<UUID> released = ghosts.onTransition(SurvivalSubstate.INTERMISSION, services.onlinePlayer());
        ghostsReleased(released);
        broadcast(SiegeMessages.prefixed(Component.text("Round " + completed + " cleared! ", SiegeMessages.GOOD)
            .append(Component.text("Next round in " + intermission.get().remainingSecondsCeil(now) + " s.", SiegeMessages.INFO))));
        log("round " + completed + " completed; " + released.size() + " ghosts respawned; record " + rounds.record());
        evaluateEnd();
    }

    private void spawn(PlannedEnemy enemy, RoundSpawnQueue queue, long now, boolean respawn) {
        List<ZoneSpec> candidates = eligibleZones(enemy.round(), enemy.special());
        if (candidates.isEmpty()) {
            spawnFailure(enemy, queue, "no active usable spawnzone for round " + enemy.round());
            return;
        }
        List<SpawnZone> zones = candidates.stream().map(ZoneSpec::zone).toList();
        List<SurvivalPoint> living = livingPlayerPositions();
        Optional<SpawnZone> zone = respawn
            ? selector.selectNearestPlayers(zones, living, services.random())
            : selector.select(zones, living, services.random());
        if (zone.isEmpty()) {
            spawnFailure(enemy, queue, "zone selection returned nothing");
            return;
        }
        ZoneSpec spec = config.zone(zone.get().id()).orElseThrow();
        Optional<Mob> mob = services.spawner().spawn(enemy, spec, lobby.id(), config.npcTeamId(),
            config.difficulty().captureContributionWeight(), roundMultipliers);
        if (mob.isEmpty()) {
            spawnFailure(enemy, queue, "spawn failed in zone " + spec.name());
            return;
        }
        Location at = mob.get().getLocation();
        World world = at.getWorld();
        List<Location> lane = spec.lanePoints().stream()
            .map(p -> new Location(world, p.x(), p.y(), p.z()))
            .toList();
        EnemyGoalMachine goals = new EnemyGoalMachine(policy, at.getX(), at.getY(), at.getZ(), settings.blockedRetries(),
            settings.blockedCooldownMillis());
        MobMover mover = new MobMover(mob.get(), settings, lane, spec.laneAutoReroute());
        SurvivalEnemy live = new SurvivalEnemy(enemy, mob.get(), goals, mover, spec.zone().id());
        enemiesByMob.put(mob.get().getUniqueId(), live);
        enemiesById.put(enemy.id(), live);
        if (stuck != null) stuck.track(enemy, now);
    }

    private void spawnFailure(PlannedEnemy enemy, RoundSpawnQueue queue, String why) {
        spawnFailures++;
        if (spawnFailures <= 5 || spawnFailures % 50 == 0) log("enemy " + enemy.id() + " (" + enemy.monsterType() + ") not spawned: " + why);
        // Dead for completion so the round can finish; a system death, never an earned kill.
        queue.recordDeath(enemy.id(), KillCause.SYSTEM);
    }

    private List<ZoneSpec> eligibleZones(int round, boolean special) {
        List<ZoneSpec> out = new ArrayList<>();
        List<ZoneSpec> fallback = new ArrayList<>();
        for (SpawnZone zone : frontier.activeZones()) {
            config.zone(zone.id()).ifPresent(spec -> {
                if (!spec.usable()) return;
                fallback.add(spec);
                if (spec.acceptsWave(round, special)) out.add(spec);
            });
        }
        return out.isEmpty() ? fallback : out;
    }

    private void tickEnemy(SurvivalEnemy enemy, RoundSpawnQueue queue, long now) {
        Mob mob = enemy.mob();
        services.platform().refresh(mob);
        if (enemy.behaviourDue(settings.behaviourTicks())) behave(enemy, now);
        if (enemy.goals().state() == EnemyGoalMachine.State.APPROACHING) enemy.mover().tick();
        if (enemy.goals().state() == EnemyGoalMachine.State.BREACHING && enemy.breachHitDue(settings.breachHitTicks())) {
            breach(enemy);
        }
        if (stuck != null && enemy.sampleDue(settings.stuckSampleTicks())) sample(enemy, queue, now);
    }

    private void behave(SurvivalEnemy enemy, long now) {
        Mob mob = enemy.mob();
        Location at = mob.getLocation();
        String world = at.getWorld() == null ? "" : at.getWorld().getName();
        List<TargetCandidate> candidates = targeting.candidates(members(), world, at.getX(), at.getY(), at.getZ());
        boolean arrived = arrived(enemy);
        boolean blocked = enemy.mover().blocked();
        Decision decision = enemy.goals().observe(new Observation(at.getX(), at.getY(), at.getZ(), candidates, arrived, blocked,
            enemy.pursuesDirectly(), now));
        if (blocked) enemy.mover().acknowledgeBlocked();
        enemy.setLastTargetDescription(decision.target().map(TargetCandidate::id).orElse(""));
        switch (decision.action()) {
            case Action.AttackPlayer attack -> {
                LivingEntity player = playerOf(attack.target().id());
                if (player != null && mob.getTarget() != player) {
                    enemy.mover().stop();
                    mob.setTarget(player);
                }
            }
            case Action.MoveTo move -> {
                if (mob.getTarget() != null) mob.setTarget(null);
                enemy.mover().moveTo(move.target().id(), new Location(at.getWorld(), move.target().x(), move.target().y(), move.target().z()));
            }
            case Action.HitGate hit -> {
                if (mob.getTarget() != null) mob.setTarget(null);
                enemy.mover().stop();
            }
            case Action.Capture capture -> {
                if (mob.getTarget() != null) mob.setTarget(null);
                enemy.mover().stop();
            }
            case Action.Idle idle -> {
                if (mob.getTarget() != null) mob.setTarget(null);
                enemy.mover().stop();
            }
        }
    }

    /** Arrived at the current structural target: the gate stand point, or inside the objective's capture ring. */
    private boolean arrived(SurvivalEnemy enemy) {
        Optional<TargetCandidate> target = enemy.goals().target();
        if (target.isEmpty()) return false;
        TargetCandidate t = target.get();
        Location at = enemy.mob().getLocation();
        double horizontal = Math.hypot(at.getX() - t.x(), at.getZ() - t.z());
        return switch (t.kind()) {
            case GATE -> horizontal <= settings.gateArriveDistance() && Math.abs(at.getY() - t.y()) <= 3.0;
            case OBJECTIVE -> horizontal <= Math.max(1.0, objectiveRadius(t.id()) - 0.5) && Math.abs(at.getY() - t.y()) <= 3.0;
            default -> false;
        };
    }

    private double objectiveRadius(String targetId) {
        try {
            int id = Integer.parseInt(targetId.substring("objective:".length()));
            return siege.scenario().objective(id).map(KnkSiegeObjective::captureRadius).orElse(2.5);
        } catch (RuntimeException e) {
            return 2.5;
        }
    }

    /** At the stand point: hit the nearest closed door block of the gate (link S3's team-checked NPC gate damage). */
    private void breach(SurvivalEnemy enemy) {
        Optional<TargetCandidate> target = enemy.goals().target();
        if (target.isEmpty() || target.get().kind() != TargetKind.GATE || services.gateDamage() == null || services.gates() == null) return;
        int gateId;
        try {
            gateId = Integer.parseInt(target.get().id().substring("gate:".length()));
        } catch (RuntimeException e) {
            return;
        }
        Mob mob = enemy.mob();
        Location at = mob.getLocation();
        CachedGateDoor bestDoor = null;
        Vector bestBlock = null;
        double best = Double.MAX_VALUE;
        for (CachedGateDoor door : targeting.closedDoors(gateId)) {
            for (Vector v : services.gates().closedFootprint(door.getId())) {
                double d = at.toVector().distanceSquared(new Vector(v.getX() + 0.5, v.getY() + 0.5, v.getZ() + 0.5));
                if (d < best) {
                    best = d;
                    bestDoor = door;
                    bestBlock = v;
                }
            }
        }
        if (bestDoor == null || at.getWorld() == null) return;
        Block block = at.getWorld().getBlockAt(bestBlock.getBlockX(), bestBlock.getBlockY(), bestBlock.getBlockZ());
        mob.lookAt(bestBlock.getX() + 0.5, bestBlock.getY() + 0.5, bestBlock.getZ() + 0.5);
        mob.swingMainHand();
        NpcGateDamage.Outcome outcome = services.gateDamage().hit(mob, bestDoor, block, config.difficulty().gateDamageFactor());
        if (outcome == NpcGateDamage.Outcome.HIT) enemy.markDealtDamage();
    }

    private void sample(SurvivalEnemy enemy, RoundSpawnQueue queue, long now) {
        Mob mob = enemy.mob();
        Location at = mob.getLocation();
        Optional<TargetCandidate> target = enemy.goals().target();
        Optional<Double> distance = target.map(t -> t.distanceTo(at.getX(), at.getY(), at.getZ()));
        ProgressSample sample = new ProgressSample(now, new SurvivalPoint(at.getX(), at.getY(), at.getZ()),
            target.map(TargetCandidate::id), distance, enemy.consumeDamageProgress(), enemy.mover().blocked());
        StuckDecision decision = stuck.report(enemy.id(), sample);
        switch (decision) {
            case StuckDecision.None none -> { }
            case StuckDecision.Respawn respawn -> respawnStuck(enemy, queue, now);
            case StuckDecision.SystemKill kill -> removeEnemy(enemy, KillCause.SYSTEM, queue, kill.reason().name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** §3c rule 1: the same planned enemy comes back at an active zone nearest the players, keeping its round stats. */
    private void respawnStuck(SurvivalEnemy enemy, RoundSpawnQueue queue, long now) {
        boolean glowing = enemy.glowing();
        boolean direct = enemy.pursuesDirectly();
        forgetMob(enemy);
        spawn(enemy.planned(), queue, now, true);
        SurvivalEnemy again = enemiesById.get(enemy.id());
        if (again != null) {
            again.setGlowing(glowing);
            again.setPursuesDirectly(direct);
            if (glowing) again.mob().setGlowing(true);
        }
    }

    // ---- events from the runtime ----

    /** A match actor of this lobby died; {@code killer} is the killing player, if any. */
    public void onMobDeath(Mob mob, Player killer) {
        SurvivalEnemy enemy = enemiesByMob.get(mob.getUniqueId());
        if (enemy == null) return;
        RoundSpawnQueue queue = rounds.currentQueue().orElse(null);
        KillCause cause = killer != null ? KillCause.PLAYER : KillCause.ENVIRONMENT;
        long now = services.clockMillis().getAsLong();
        if (killer != null) {
            KillBlow blow = killBlows.consume(mob.getUniqueId(), killer.getUniqueId(), now);
            creditKill(enemy, blow, killer);
        } else {
            killBlows.forget(mob.getUniqueId());
        }
        forgetMob(enemy);
        if (queue != null) queue.recordDeath(enemy.id(), cause);
        if (stuck != null) stuck.forget(enemy.id());
        completeWaveIfDone(services.clockMillis().getAsLong());
    }

    /** A player hit a match actor: remembered as the possible killing blow (headshot/critical bonus, S33). */
    public void onMobHitByPlayer(Mob mob, KillBlow blow) {
        if (enemiesByMob.containsKey(mob.getUniqueId())) killBlows.record(mob.getUniqueId(), blow, services.clockMillis().getAsLong());
    }

    private void creditKill(SurvivalEnemy enemy, KillBlow blow, Player killer) {
        economy.creditKill(enemy.id(), blow).ifPresent(credit -> {
            String bonus = credit.bonus() ? (blow.headshot() ? " (headshot)" : " (critical)") : "";
            killer.sendActionBar(Component.text("+" + credit.reward().matchCurrency() + " match coins" + bonus
                + " · balance " + credit.balanceAfter(), SiegeMessages.HIGHLIGHT));
            log("kill: enemy " + enemy.id() + " by " + killer.getName() + bonus + " +" + credit.reward().matchCurrency()
                + " (balance " + credit.balanceAfter() + "), permanent +" + credit.reward().coins() + " coins / +" + credit.reward().xp() + " xp accrued");
        });
    }

    private void ghostsReleased(List<UUID> released) {
        if (released == null || released.isEmpty()) return;
        for (UUID id : released) economy.setGhost(id, false);
        for (MatchHook hook : hooks) hook.ghostsReleased(released);
    }

    /** A match actor hurt something (progress for the stuck check). */
    public void onMobDealtDamage(Entity damager) {
        SurvivalEnemy enemy = enemiesByMob.get(damager.getUniqueId());
        if (enemy != null) enemy.markDealtDamage();
    }

    /** A member died (DESIGN §3b): a ghost in WAVE_ACTIVE / INTERMISSION; the wipe check follows. */
    public void onPlayerDeath(Player player) {
        boolean ghost = ghosts.onDeath(player, rounds.substate());
        if (ghost) {
            economy.setGhost(player.getUniqueId(), true);
            for (MatchHook hook : hooks) hook.playerBecameGhost(player.getUniqueId());
            player.sendMessage(SiegeMessages.prefixed(Component.text("You died. You are a ghost until the next round"
                + (rounds.substate() == SurvivalSubstate.INTERMISSION ? " starts" : " break") + ": invulnerable, unseen by the enemy, no interaction.", SiegeMessages.INFO)));
            // Every NPC targeting this player drops it (§3b "invalidates every current NPC target on that player").
            for (SurvivalEnemy enemy : enemiesByMob.values()) {
                if (enemy.mob().getTarget() == player) enemy.mob().setTarget(null);
            }
        }
        evaluateEnd();
    }

    /** A member respawned (clicked the button). */
    public void onPlayerRespawn(Player player) {
        boolean ghost = ghosts.applyOnRespawn(player, rounds.substate());
        if (ghost) {
            player.sendActionBar(Component.text("Ghost: you respawn when the round break starts.", SiegeMessages.INFO));
        }
    }

    /** A member left the match (leave, quit, kick): ghost state ends; the end conditions are re-evaluated. */
    public void onMemberLeft(UUID playerId) {
        ghosts.release(playerId, services.onlinePlayer().apply(playerId), false);
        economy.leave(playerId);
        for (MatchHook hook : hooks) hook.memberLeft(playerId);
        evaluateEnd();
    }

    /** An objective changed hands: the spawn frontier follows ownership (§6, S10, S63); the end conditions follow. */
    public void onCapture(CaptureEvent capture) {
        SpawnFrontier.Change change = frontier.update(targeting.npcHeldObjectiveIds());
        if (!change.isEmpty()) {
            log("objective " + capture.objectiveId() + " captured by team " + capture.newHolderTeamId() + ": zones activated "
                + change.activated() + ", deactivated " + change.deactivated());
            if (!change.activated().isEmpty()) {
                broadcast(SiegeMessages.prefixed(Component.text("The enemy pushes forward: new spawn zones are active.", SiegeMessages.BAD)));
            }
        }
        evaluateEnd();
    }

    // ---- end conditions ----

    /** DESIGN §3a: evaluated after each state change; first condition wins with the S47 precedence. */
    public void evaluateEnd() {
        if (ending) return;
        EndConditionSnapshot snapshot = snapshot();
        evaluator.evaluate(snapshot).ifPresent(this::end);
    }

    public EndConditionSnapshot snapshot() {
        List<UUID> players = siege.roster().playerIds();
        int living = 0;
        for (UUID id : players) {
            Player p = services.onlinePlayer().apply(id);
            if (p == null || p.isDead() || ghosts.isGhost(id)) continue;
            living++;
        }
        List<SurvivalObjective> objectives = new ArrayList<>();
        for (SurvivalConfiguration.ObjectiveSpec o : config.objectives()) {
            objectives.add(new SurvivalObjective(String.valueOf(o.id()), o.instaLose(), targeting.npcHeld(o.id())));
        }
        return new EndConditionSnapshot(rounds.substate(), players.size(), Math.min(living, players.size()), objectives);
    }

    /** Ends the survival side of the match (§3 step 7) and asks the runtime to end the Siege match. */
    public void end(SurvivalEndReason reason) {
        if (ending) return;
        ending = true;
        endReason = reason;
        List<PlannedEnemy> cancelled = rounds.end();
        removeAllMobs();
        endEconomy();
        log("ending: " + reason + ", record " + rounds.record() + ", " + cancelled.size() + " spawns cancelled");
        broadcast(SiegeMessages.prefixed(Component.text(endMessage(reason), reason.settlesRewards() ? SiegeMessages.BAD : SiegeMessages.INFO)
            .append(Component.text(" Waves reached " + rounds.record().reached() + ", completed " + rounds.record().completed() + ".", SiegeMessages.INFO))));
        if (!endRequested) {
            endRequested = true;
            onEnd.accept(reason);
        }
    }

    /** The Siege match ended for a reason the runtime didn't trigger (abort, membership): record it and clean up. */
    public void endedExternally(SurvivalEndReason reason) {
        if (endReason == null) endReason = reason;
        ending = true;
        endRequested = true;
        rounds.end();
        removeAllMobs();
        endEconomy();
        log("ended by the Siege lifecycle: " + reason + ", record " + rounds.record());
    }

    /** Match end / restore: every mob gone, every ghost released (the Siege release restores the players). */
    public void cleanup() {
        ending = true;
        removeAllMobs();
        endEconomy();
        ghosts.releaseAll(services.onlinePlayer());
        services.actors().clear(lobby.id());
    }

    /** The ledger closes and every feature removes its entities/items (idempotent). */
    private void endEconomy() {
        if (!economy.ended()) {
            economy.end();
            log("economy closed: balances " + describeBalances() + ", accrued kills " + economy.accrual().earnedKills() + " (settlement is S8)");
        }
        for (MatchHook hook : hooks) {
            try {
                hook.ended();
            } catch (RuntimeException e) {
                services.logger().log(java.util.logging.Level.WARNING, "[Survival] Lobby " + lobby.key() + ": feature cleanup failed", e);
            }
        }
    }

    private String describeBalances() {
        List<String> out = new ArrayList<>();
        economy.balances().forEach((id, balance) -> {
            Player p = services.onlinePlayer().apply(id);
            out.add((p == null ? id.toString().substring(0, 8) : p.getName()) + "=" + balance);
        });
        return out.toString();
    }

    private void removeAllMobs() {
        for (SurvivalEnemy enemy : new ArrayList<>(enemiesByMob.values())) {
            forgetMob(enemy);
        }
        services.actors().clear(lobby.id());
    }

    private void removeEnemy(SurvivalEnemy enemy, KillCause cause, RoundSpawnQueue queue, String why) {
        forgetMob(enemy);
        if (queue != null) queue.recordDeath(enemy.id(), cause);
        if (stuck != null) stuck.forget(enemy.id());
        if (cause == KillCause.SYSTEM) log("enemy " + enemy.id() + " system-killed: " + why);
    }

    private void forgetMob(SurvivalEnemy enemy) {
        enemiesByMob.remove(enemy.mobId());
        enemiesById.remove(enemy.id());
        services.platform().forget(enemy.mobId());
        services.actors().unregister(enemy.mobId());
        Mob mob = enemy.mob();
        if (mob.isValid()) mob.remove();
    }

    // ---- helpers ----

    private StuckTelemetrySink telemetry() {
        return event -> log("stuck " + event.kind() + ": enemy " + event.enemyId() + " round " + event.round()
            + " at " + event.lastPosition().map(p -> String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", p.x(), p.y(), p.z())).orElse("?")
            + " target " + event.target().orElse("-") + " respawns " + event.respawnCount());
    }

    private List<SurvivalPoint> livingPlayerPositions() {
        List<SurvivalPoint> out = new ArrayList<>();
        for (Player p : members()) {
            if (p.isDead() || ghosts.isGhost(p.getUniqueId())) continue;
            Location at = p.getLocation();
            out.add(new SurvivalPoint(at.getX(), at.getY(), at.getZ()));
        }
        return out;
    }

    /** The online roster members (living and ghosts). */
    private List<Player> members() {
        List<Player> out = new ArrayList<>();
        for (UUID id : siege.roster().playerIds()) {
            Player p = services.onlinePlayer().apply(id);
            if (p != null && p.isOnline()) out.add(p);
        }
        return out;
    }

    private LivingEntity playerOf(String targetId) {
        try {
            return services.onlinePlayer().apply(UUID.fromString(targetId.substring("player:".length())));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void announceCountdown(String prefix, long untilMillis, long now) {
        int seconds = (int) Math.ceil(Math.max(0, untilMillis - now) / 1000.0);
        if (seconds == lastAnnouncedSecond) return;
        lastAnnouncedSecond = seconds;
        Component bar = Component.text(prefix + seconds + " s", SiegeMessages.HIGHLIGHT);
        members().forEach(p -> p.sendActionBar(bar));
    }

    private void broadcast(Component message) {
        members().forEach(p -> p.sendMessage(message));
    }

    private void log(String message) {
        if (services.logger() != null) services.logger().info("[Survival] Lobby " + lobby.key() + ": " + message);
    }

    static String endMessage(SurvivalEndReason reason) {
        return switch (reason) {
            case TEAM_WIPE -> "Your team was wiped out.";
            case INSTA_LOSE_OBJECTIVE_CAPTURED -> "The enemy captured a decisive objective.";
            case ALL_OBJECTIVES_CAPTURED -> "The enemy holds every objective.";
            case NO_PLAYERS_LEFT -> "No players left; the match ends.";
            case ADMIN_ABORT -> "The match was stopped by an admin.";
            case SERVER_RESTART -> "The server restarts; the match is aborted.";
            case TECHNICAL_LIMIT -> "The match hit a technical limit and ends.";
        };
    }

    private static String describe(Map<ScaledStat, Double> multipliers) {
        StringBuilder b = new StringBuilder();
        multipliers.forEach((k, v) -> b.append(k.name().toLowerCase(java.util.Locale.ROOT)).append('=')
            .append(String.format(java.util.Locale.ROOT, "%.2f", v)).append(' '));
        return b.toString().trim();
    }

    /** Admin status lines ({@code /knk siege survival status}). */
    public List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        long now = services.clockMillis().getAsLong();
        lines.add("Lobby " + lobby.key() + ": scenario " + config.scenarioId() + " '" + config.scenarioName() + "', difficulty "
            + config.difficulty().name() + ", substate " + rounds.substate() + (ending ? " (ending: " + endReason + ")" : "")
            + ", running " + ((now - startedAtMillis) / 1000) + " s");
        lines.add("Waves: reached " + rounds.record().reached() + ", completed " + rounds.record().completed()
            + rounds.currentQueue().map(q -> "; round " + q.round() + " queue " + q.queuedCount() + " alive " + q.aliveCount()
                + " earned " + q.earnedKills() + " system " + q.systemKills() + " other " + q.otherDeaths()).orElse("")
            + rounds.intermission().map(i -> "; intermission " + i.remainingSecondsCeil(now) + " s left").orElse(""));
        lines.add("Stats this round: " + describe(roundMultipliers) + "; players at round start " + playersAtRoundStart);
        lines.add("Frontier: active zones " + frontier.activeZoneIds() + " (recapture " + frontier.recaptureEnabled() + ")");
        lines.add("Players: " + siege.roster().size() + " in match, " + livingPlayerPositions().size() + " alive, " + ghosts.size()
            + " ghosts " + ghosts.ghostIds().stream().map(id -> {
                Player p = services.onlinePlayer().apply(id);
                return p == null ? id.toString().substring(0, 8) : p.getName();
            }).toList());
        lines.add("Economy: balances " + describeBalances() + ", kill reward " + economy.settings().matchCurrencyPerKill()
            + " ×" + economy.settings().bonusMultiplier() + " bonus, ghosts " + economy.ledger().balances().keySet().stream()
                .filter(economy::isGhost).count() + ", accrued kills " + economy.accrual().earnedKills()
            + ", equipment " + config.equipment().mode());
        for (MatchHook hook : hooks) lines.addAll(hook.statusLines());
        EndConditionSnapshot snapshot = snapshot();
        lines.add("End conditions: all-captured " + config.endConditions().allCapturedEnabled() + ", objectives "
            + snapshot.objectives().stream().map(o -> o.id() + (o.instaLose() ? "!" : "") + (o.npcHeld() ? ":NPC" : ":def")).toList()
            + ", verdict now " + evaluator.evaluate(snapshot).map(Enum::name).orElse("continue"));
        int shown = 0;
        for (SurvivalEnemy e : enemiesByMob.values()) {
            if (shown++ >= 12) {
                lines.add("  … " + (enemiesByMob.size() - 12) + " more");
                break;
            }
            Location at = e.mob().getLocation();
            lines.add("  #" + e.id() + " " + e.planned().monsterType().toLowerCase(java.util.Locale.ROOT) + " " + e.goals().state()
                + " -> " + e.lastTargetDescription() + " at " + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ()
                + (e.glowing() ? " glow" : "") + (e.mover().blocked() ? " blocked" : "") + " hp " + String.format(java.util.Locale.ROOT, "%.0f", e.mob().getHealth()));
        }
        return lines;
    }
}
