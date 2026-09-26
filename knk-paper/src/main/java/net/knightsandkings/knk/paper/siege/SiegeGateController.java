package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.gates.CachedGateStructure;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeGateRecords.DoorState;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeGateRecords.LockdownEntry;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.ports.api.SiegeGatesCommandApi;
import net.knightsandkings.knk.core.siege.AllianceResolver;
import net.knightsandkings.knk.core.siege.ObjectiveState.CaptureEvent;
import net.knightsandkings.knk.core.siege.SiegeGatePlan;
import net.knightsandkings.knk.core.siege.SiegeGatePlan.Control;
import net.knightsandkings.knk.paper.gates.HealthSystem;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Siege Phase 7a: the match's gates (DESIGN §8). At the hub ({@link #areaLockdownStarted}) it snapshots
 * every affected gate structure (selected gates and the other gates of the scenario area), has the API
 * persist the snapshot + CurrentSiegeId + overrides (crash-safe, §8.2), then drives the runtime state:
 * pass-through off, no respawn, invincible per role, area gates forced open, selected gates to their
 * initial state. An objective capture hands the objective's gate to the capturer's team and forces it
 * to GateStateOnCapture ({@link #objectiveCaptured}). At the end ({@link #roundReleased}) everything is
 * restored from the snapshot and the API deletes it. {@link #recoverOnStartup()} restores whatever a
 * crash left behind. Control/damage decisions for {@code SiegeGateListener} come from
 * {@link SiegeGatePlan}.
 * <p>
 * Main thread only, except the API callbacks, which hop back with {@code runTask}.
 * <b>AnimateDuringSiege is not honoured yet:</b> every change animates (the gate package has no
 * public instant-placement entry point).
 */
public final class SiegeGateController implements SiegeMatchObserver {

    /** A structure's values before the lockdown (the local mirror of the API snapshot). */
    private record StructureSnapshot(Integer currentSiegeId, boolean siegeObjective, Boolean invincibleOverride,
                                     Boolean allowPassThroughOverride, Boolean canRespawnOverride, List<DoorState> doors) {}

    private static final class Lockdown {
        final SiegeLobbyRuntime runtime;
        final KnkSiegeScenario scenario;
        final SiegeGatePlan plan;
        final AllianceResolver alliances;
        final Map<Integer, StructureSnapshot> snapshots = new LinkedHashMap<>();
        Long matchId;

        Lockdown(SiegeLobbyRuntime runtime, KnkSiegeScenario scenario) {
            this.runtime = runtime;
            this.scenario = scenario;
            this.plan = SiegeGatePlan.of(scenario);
            this.alliances = AllianceResolver.of(scenario);
        }
    }

    private final Plugin plugin;
    private final Logger logger;
    private final GateManager gateManager;
    private final HealthSystem healthSystem;
    private final SiegeGatesCommandApi api;
    private final Map<Integer, Lockdown> byLobby = new HashMap<>();
    private final Map<Integer, Lockdown> byGate = new HashMap<>();

    public SiegeGateController(Plugin plugin, GateManager gateManager, HealthSystem healthSystem, SiegeGatesCommandApi api) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.gateManager = gateManager;
        this.healthSystem = healthSystem;
        this.api = api;
    }

    // ==================== Startup recovery (DESIGN §8.4) ====================

    /** Restores gates a crash left locked down (nothing runs yet), then reloads the gate cache. */
    public void recoverOnStartup() {
        api.restoreStale().whenComplete((result, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "[Siege] Gate recovery (restore-stale-gates) failed; gates a crash left "
                        + "locked down stay so until the next start", error);
                return;
            }
            if (result == null || result.isEmpty()) return;
            logger.warning("[Siege] Gate recovery restored " + result.restored().size() + " gate structure(s) and cleared "
                    + result.clearedGateStructureIds().size() + " stale siege marker(s); reloading gates");
            runOnMain(() -> gateManager.reloadGates().whenComplete((ignored, reloadError) -> {
                if (reloadError != null) logger.log(Level.WARNING, "[Siege] Gate reload after recovery failed", reloadError);
            }));
        });
    }

    // ==================== Observer ====================

    @Override
    public void areaLockdownStarted(SiegeLobbyRuntime lobby, KnkSiegeScenario scenario) {
        if (byLobby.containsKey(lobby.id())) return;
        Lockdown lockdown = new Lockdown(lobby, scenario);
        List<LockdownEntry> entries = new ArrayList<>();
        for (SiegeGatePlan.Entry entry : lockdown.plan.entries()) {
            CachedGateStructure structure = gateManager.getStructure(entry.gateStructureId());
            if (structure == null) {
                logger.warning("[Siege] Gate structure " + entry.gateStructureId() + " of scenario " + scenario.id()
                        + " isn't loaded; it is left out of the lockdown");
                continue;
            }
            Lockdown holder = byGate.get(entry.gateStructureId());
            if (holder != null) {
                logger.warning("[Siege] Gate structure " + entry.gateStructureId() + " is already locked down by lobby "
                        + holder.runtime.key() + "; left out of lobby " + lobby.key());
                continue;
            }
            List<DoorState> doors = new ArrayList<>();
            for (CachedGateDoor door : gateManager.getDoorsForStructure(entry.gateStructureId())) {
                doors.add(new DoorState(door.getId(), isOpen(door), door.getHealthCurrent(), door.isDestroyed()));
            }
            lockdown.snapshots.put(entry.gateStructureId(), new StructureSnapshot(structure.getCurrentSiegeId(),
                    structure.isSiegeObjective(), structure.getIsInvincibleOverride(), structure.getAllowPassThroughOverride(),
                    structure.getCanRespawnOverride(), doors));
            entries.add(new LockdownEntry(entry.gateStructureId(), entry.objectiveGate(), entry.invincible(), entry.forcedOpen(), doors));
            byGate.put(entry.gateStructureId(), lockdown);
        }
        byLobby.put(lobby.id(), lockdown);
        if (entries.isEmpty()) return;

        // Persist first (snapshot before any change, §8.2); apply when the API answered - or failed,
        // in which case the match still gets its gates, only without crash safety.
        CompletableFuture<Long> matchId = lobby.matchIdFuture();
        if (matchId == null) {
            apply(lockdown, null);
            return;
        }
        matchId.thenCompose(id -> {
                    if (id == null) return CompletableFuture.completedFuture((Long) null);
                    return api.lockdown(id, entries).handle((ignored, error) -> {
                        if (error != null) {
                            logger.log(Level.WARNING, "[Siege] Persisting the gate lockdown of match " + id
                                    + " failed; the gates are locked down anyway, but a crash now leaves them locked", error);
                        }
                        return id;
                    });
                })
                .exceptionally(error -> null)
                .thenAccept(id -> runOnMain(() -> apply(lockdown, id)));
    }

    @Override
    public void objectiveCaptured(SiegeLobbyRuntime lobby, SiegeMatch match, CaptureEvent capture) {
        Lockdown lockdown = byLobby.get(lobby.id());
        if (lockdown == null) return;
        lockdown.plan.onCapture(capture.objectiveId(), capture.newHolderTeamId()).ifPresent(transfer -> {
            if (!lockdown.snapshots.containsKey(transfer.gateStructureId())) return;
            setOpen(transfer.gateStructureId(), transfer.open());
            logger.info("[Siege] Lobby " + lobby.key() + ": gate " + transfer.gateStructureId() + " now belongs to team "
                    + transfer.newOwnerTeamId() + " (" + (transfer.open() ? "opened" : "closed") + " on capture)");
        });
    }

    @Override
    public void roundReleased(SiegeLobbyRuntime lobby) {
        Lockdown lockdown = byLobby.remove(lobby.id());
        if (lockdown == null) return;
        lockdown.snapshots.keySet().forEach(id -> byGate.remove(id, lockdown));

        Long matchId = lockdown.matchId;
        if (matchId == null) {
            CompletableFuture<Long> future = lobby.matchIdFuture();
            if (future != null && future.isDone() && !future.isCompletedExceptionally()) matchId = future.getNow(null);
        }
        if (plugin.isEnabled()) {
            for (Map.Entry<Integer, StructureSnapshot> e : lockdown.snapshots.entrySet()) {
                try {
                    restoreRuntime(e.getKey(), e.getValue());
                } catch (RuntimeException ex) {
                    logger.log(Level.SEVERE, "[Siege] Restoring gate structure " + e.getKey() + " failed", ex);
                }
            }
        }
        // (On disable no block changes are possible any more; the API restore - or, if it doesn't get
        // through, the next start's recovery - puts the database back and the gate reload fixes the world.)
        if (matchId != null && !lockdown.snapshots.isEmpty()) {
            long id = matchId;
            api.restore(id).whenComplete((result, error) -> {
                if (error != null) {
                    logger.log(Level.WARNING, "[Siege] Restoring the persisted gate lockdown of match " + id
                            + " failed; the next start's recovery restores it", error);
                }
            });
        }
    }

    // ==================== Decisions for SiegeGateListener ====================

    /** True when a running lockdown owns this gate structure. */
    public boolean isLocked(int gateStructureId) {
        return byGate.containsKey(gateStructureId);
    }

    /**
     * A player tries to open ({@code open}) or close a locked gate. Opens/closes it when their team's
     * alliance owns it and returns null; otherwise returns the reason it was refused. Callers cancel
     * the original interaction either way.
     */
    public Control control(Player player, int gateStructureId, boolean open) {
        Lockdown lockdown = byGate.get(gateStructureId);
        if (lockdown == null) return Control.NOT_IN_PLAN;
        Control decision = lockdown.plan.canControl(gateStructureId, teamOf(lockdown, player), lockdown.alliances);
        if (decision == Control.ALLOWED) {
            setOpen(gateStructureId, open);
        }
        return decision;
    }

    /** May this player (null: no attributable player) damage the locked gate? True for unlocked gates. */
    public boolean mayDamage(Player attacker, int gateStructureId) {
        Lockdown lockdown = byGate.get(gateStructureId);
        if (lockdown == null) return true;
        Integer team = attacker == null ? null : teamOf(lockdown, attacker);
        return lockdown.plan.canDamage(gateStructureId, team, lockdown.alliances);
    }

    /** The lobby display name holding a locked gate, for messages. */
    public Optional<String> lobbyNameOf(int gateStructureId) {
        Lockdown lockdown = byGate.get(gateStructureId);
        return lockdown == null ? Optional.empty() : Optional.of(lockdown.runtime.displayName());
    }

    // ==================== Internals ====================

    private void apply(Lockdown lockdown, Long matchId) {
        if (byLobby.get(lockdown.runtime.id()) != lockdown) return; // released meanwhile
        lockdown.matchId = matchId;
        for (SiegeGatePlan.Entry entry : lockdown.plan.entries()) {
            if (!lockdown.snapshots.containsKey(entry.gateStructureId())) continue;
            CachedGateStructure structure = gateManager.getStructure(entry.gateStructureId());
            if (structure == null) continue;
            structure.setCurrentSiegeId(matchId == null ? null : Math.toIntExact(matchId));
            structure.setSiegeObjective(entry.objectiveGate());
            structure.setAllowPassThroughOverride(false);
            structure.setCanRespawnOverride(false);
            structure.setIsInvincibleOverride(entry.invincible());
            setOpen(entry.gateStructureId(), entry.initialOpen());
        }
        logger.info("[Siege] Lobby " + lockdown.runtime.key() + ": " + lockdown.snapshots.size()
                + " gate structure(s) locked down" + (matchId == null ? " (match not recorded)" : " for match " + matchId));
    }

    private void restoreRuntime(int gateStructureId, StructureSnapshot snapshot) {
        CachedGateStructure structure = gateManager.getStructure(gateStructureId);
        if (structure != null) {
            structure.setCurrentSiegeId(snapshot.currentSiegeId());
            structure.setSiegeObjective(snapshot.siegeObjective());
            structure.setIsInvincibleOverride(snapshot.invincibleOverride());
            structure.setAllowPassThroughOverride(snapshot.allowPassThroughOverride());
            structure.setCanRespawnOverride(snapshot.canRespawnOverride());
        }
        for (DoorState state : snapshot.doors()) {
            CachedGateDoor door = gateManager.getGate(state.gateDoorId());
            if (door == null) continue;
            if (state.destroyed()) continue; // it was already destroyed before the siege
            if (door.isDestroyed()) {
                healthSystem.respawnGate(door); // back closed at full health
            }
            door.setHealthCurrent(state.healthCurrent());
            if (state.opened()) gateManager.openGate(door.getId());
            else gateManager.closeGate(door.getId());
        }
    }

    /** Opens/closes every door of the structure that isn't destroyed (animated). */
    private void setOpen(int gateStructureId, boolean open) {
        for (CachedGateDoor door : gateManager.getDoorsForStructure(gateStructureId)) {
            if (door.isEffectivelyDestroyed()) continue;
            if (open) gateManager.openGate(door.getId());
            else gateManager.closeGate(door.getId());
        }
    }

    private static Integer teamOf(Lockdown lockdown, Player player) {
        if (player == null || !lockdown.runtime.isMember(player.getUniqueId())) return null;
        return lockdown.runtime.match()
                .map(match -> match.roster().teamOf(player.getUniqueId()))
                .filter(OptionalInt::isPresent)
                .map(OptionalInt::getAsInt)
                .orElse(null);
    }

    private static boolean isOpen(CachedGateDoor door) {
        AnimationState state = door.getCurrentState();
        return state == AnimationState.OPEN || state == AnimationState.OPENING;
    }

    private void runOnMain(Runnable task) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, task);
    }
}
