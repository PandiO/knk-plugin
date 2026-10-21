package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaBattleground;
import net.knightsandkings.knk.core.domain.duels.DuelRefusedException;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.FightStart;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.ItemSummary;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.LockRequest;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.MatchRecord;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Result;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Settlement;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.SideTelemetry;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Telemetry;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.duel.ArenaQueue;
import net.knightsandkings.knk.core.duel.BattlegroundAllocator;
import net.knightsandkings.knk.core.duel.DuelArenaSettings;
import net.knightsandkings.knk.core.duel.DuelCombatRules;
import net.knightsandkings.knk.core.duel.DuelEffect;
import net.knightsandkings.knk.core.duel.DuelEndReason;
import net.knightsandkings.knk.core.duel.DuelEscrowPlan;
import net.knightsandkings.knk.core.duel.DuelNegotiation;
import net.knightsandkings.knk.core.duel.DuelOfferBook;
import net.knightsandkings.knk.core.duel.DuelPhase;
import net.knightsandkings.knk.core.duel.DuelResult;
import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.duel.DuelStateMachine;
import net.knightsandkings.knk.core.duel.DuelTicks;
import net.knightsandkings.knk.core.duel.StakeContract;
import net.knightsandkings.knk.core.duel.StakeItem;
import net.knightsandkings.knk.core.teleport.TeleportDenial;
import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.paper.minigame.MinigamePlayerVault;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The arena duel runtime (arena duels DESIGN §5–§8, plan Phase 4): it owns the offer book (P3), runs the lock-in
 * (§6.5), one P3 {@link DuelStateMachine} per match and one {@link ArenaQueue} per arena, applies the machines'
 * {@link DuelEffect}s, and reports results to the API through the pending-results spool. A 1 s ticker
 * ({@link #tick}) drives the deadlines and the queues; deaths and quits are decided on the next tick.
 * <p>
 * <b>Never blocks on HTTP</b>: every API call is async and hops back to the main thread. Items are always on disk
 * ({@link DuelEscrowSpool}) before they leave a player, and are delivered only after the player's own gear is back.
 * <p>
 * <b>Gear</b> (§8.4): own-gear modes keep the inventory and snapshot health, food and effects; kit modes write the
 * vault in the lobby teleport's commit step ({@code TeleportPlan.beforeMove}), clear the inventory, grant the kit on
 * arrival (loaned, tagged {@code knk_duel_loan}), and restore on every way out: ENDING, cancel/abort, quit, outage,
 * and on join after a crash. Drop mode moves the loser's drops (or a forfeiting quitter's inventory) into the spool
 * as spoils.
 * <p>
 * The proposal UI, commands and menus are P5/P6: they call the public methods below and listen through
 * {@link DuelObserver}. Main thread only, except where noted.
 */
public final class DuelService {

    /** Staff node that lifts the duel teleport guard for staff teleports (P5 registers it in plugin.yml). */
    public static final String ADMIN_NODE = "knk.admin.duel";
    public static final String MINIGAME_ID = "duel";

    /** What the runtime needs from the plugin (fakes in tests). */
    public record Ports(
            net.knightsandkings.knk.core.ports.api.DuelMatchesApi api,
            DuelTeleporter teleporter,
            DuelKitLoans kits,
            DuelItems items,
            MinigamePlayerVault vault,
            DuelEscrowSpool spool,
            DuelPendingResults results,
            DuelRecovery recovery,
            DuelBattlegroundAccess access,
            DuelScheduler scheduler,
            Function<UUID, Player> players,
            Function<UUID, Integer> userIds,
            Function<Integer, Optional<KnkArena>> arenas,
            Function<KnkLocation, Optional<Location>> locations,
            Predicate<UUID> inOtherMinigame,
            Predicate<UUID> frozen,
            Consumer<Player> afterRestore,
            Logger logger
    ) {
        public Ports {
            Objects.requireNonNull(api, "api");
            Objects.requireNonNull(teleporter, "teleporter");
            Objects.requireNonNull(kits, "kits");
            Objects.requireNonNull(items, "items");
            Objects.requireNonNull(vault, "vault");
            Objects.requireNonNull(spool, "spool");
            Objects.requireNonNull(results, "results");
            Objects.requireNonNull(recovery, "recovery");
            Objects.requireNonNull(access, "access");
            Objects.requireNonNull(scheduler, "scheduler");
            Objects.requireNonNull(players, "players");
            Objects.requireNonNull(userIds, "userIds");
            Objects.requireNonNull(arenas, "arenas");
            Objects.requireNonNull(locations, "locations");
            inOtherMinigame = inOtherMinigame == null ? id -> false : inOtherMinigame;
            frozen = frozen == null ? id -> false : frozen;
            afterRestore = afterRestore == null ? p -> { } : afterRestore;
            Objects.requireNonNull(logger, "logger");
        }
    }

    /** The answer to {@link #accept}. */
    public record LockOutcome(boolean ok, String message, DuelMatch match) {
        static LockOutcome refused(String message) {
            return new LockOutcome(false, message, null);
        }
    }

    /** What the death listener must do with the event (DESIGN §8.4–8.6). */
    public record DeathHandling(boolean handled, boolean keepInventory, boolean clearDrops) {
        static final DeathHandling NONE = new DeathHandling(false, false, false);
    }

    /** How a side leaves its match. */
    private enum Exit { TELEPORT, IN_PLACE, QUIT }

    private final Ports ports;
    private volatile DuelConfig config;
    private final DuelOfferBook offers;
    private final DuelStakeItems stakeItems = new DuelStakeItems();
    private final Map<UUID, DuelMatch> matches = new LinkedHashMap<>();
    private final Map<UUID, UUID> matchOfPlayer = new HashMap<>();
    private final Map<Integer, ArenaQueue> queues = new HashMap<>();
    private final Map<UUID, Map<DuelSide, Boolean>> releasedSides = new HashMap<>();
    /** Players to send to the arena exit when they respawn (died in the match). */
    private final Map<UUID, Location> respawnAt = new HashMap<>();
    /** The vault restores without its own teleport while the runtime moves the player itself. */
    private final Set<UUID> restoringInPlace = new HashSet<>();
    private final List<DuelObserver> observers = new CopyOnWriteArrayList<>();
    /** Players shown the duel sidebar (P6's {@code DuelHud}: duelists and spectators), asked by the registry. */
    private volatile java.util.function.Predicate<UUID> duelBoardViewers = id -> false;
    private long lastRetryTick = Long.MIN_VALUE;
    private long lastHeldRefreshTick = Long.MIN_VALUE;
    private boolean shuttingDown;

    public DuelService(Ports ports, DuelConfig config) {
        this.ports = Objects.requireNonNull(ports, "ports");
        this.config = Objects.requireNonNull(config, "config");
        this.offers = new DuelOfferBook(config.maxIncoming(), config.sendCooldownSeconds(),
                item -> config.itemBlocklist().contains(item.material()), stakeItems::valueOf);
        ports.recovery().setLive(matches::containsKey);
    }

    // ==================== Wiring ====================

    public void addObserver(DuelObserver observer) {
        observers.add(Objects.requireNonNull(observer, "observer"));
    }

    /** Who sees the duel sidebar (P6), so rank/salary scoreboard refreshes leave their board alone (DESIGN §8.7). */
    public void setDuelBoardViewers(java.util.function.Predicate<UUID> viewers) {
        this.duelBoardViewers = viewers != null ? viewers : id -> false;
    }

    /** Whether {@code playerId} is shown the duel sidebar right now (a duelist or a spectator, P6). */
    public boolean showsDuelBoard(UUID playerId) {
        return duelBoardViewers.test(playerId);
    }

    public DuelConfig config() {
        return config;
    }

    public void updateConfig(DuelConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /** Startup (DESIGN §7.3): replay pending results, abort what the last run left open, settle every spool file. */
    public CompletableFuture<Void> start() {
        return ports.recovery().recoverOnStartup();
    }

    // ==================== API for P5 / P6 ====================

    /** The offer book (P3): invite, counter, decline, cancel, toggle. {@link #accept} does the accept. */
    public DuelOfferBook offers() {
        return offers;
    }

    /** The item references the proposal editor registers (D3). */
    public DuelStakeItems stakeItems() {
        return stakeItems;
    }

    /** The current server tick (the offer book's and the machines' clock). */
    public long now() {
        return ports.scheduler().currentTick();
    }

    /** Why {@code player} can't send or accept an invite right now (D18), or empty. */
    public Optional<String> inviteRefusal(Player player) {
        UUID id = player.getUniqueId();
        if (!config.enabled()) return Optional.of("Duels are turned off.");
        if (matchOfPlayer.containsKey(id)) return Optional.of("You are already in a duel.");
        if (offers.isLocking(id)) return Optional.of("You are already locking in a duel.");
        if (ports.inOtherMinigame().test(id)) return Optional.of("You can't duel while you are in a minigame.");
        if (ports.teleporter().isCombatTagged(id)) return Optional.of("You can't duel while in combat.");
        if (ports.frozen().test(id)) return Optional.of("You can't duel while frozen.");
        if (player.isDead()) return Optional.of("You can't duel while dead.");
        return Optional.empty();
    }

    /** Why the siege must refuse {@code playerId} (DESIGN §8.7), or empty. */
    public Optional<String> siegeJoinRefusal(UUID playerId) {
        if (matchOfPlayer.containsKey(playerId)) return Optional.of("You are in a duel.");
        if (offers.isNegotiating(playerId)) {
            return Optional.of("You are negotiating a duel. Cancel or finish it before joining a siege.");
        }
        return Optional.empty();
    }

    /** The player's running match (LOCKING..ENDING, until their gear is back). */
    public Optional<DuelMatch> matchOf(UUID playerId) {
        UUID token = matchOfPlayer.get(playerId);
        return token == null ? Optional.empty() : Optional.ofNullable(matches.get(token));
    }

    public Optional<DuelMatch> match(UUID token) {
        return Optional.ofNullable(matches.get(token));
    }

    /** Every running match, oldest first (P6's live-matches menu). */
    public List<DuelMatch> matches() {
        return List.copyOf(matches.values());
    }

    /** The player's phase, or empty when not in a match. */
    public Optional<DuelPhase> phaseOf(UUID playerId) {
        return matchOf(playerId).map(DuelMatch::phase);
    }

    /** Waiting matches and busy battlegrounds of an arena (the arena menu: "2/3 waiting, 5/8 busy"). */
    public Optional<ArenaQueue> queueOf(int arenaId) {
        return Optional.ofNullable(queues.get(arenaId));
    }

    /** "#2 in line" while the match waits for a battleground, else 0. */
    public int queuePosition(DuelMatch match) {
        ArenaQueue queue = queues.get(match.arena().id());
        return queue == null ? 0 : queue.positionOf(match.token().toString());
    }

    /** Stacks waiting for {@code playerId} in the escrow ({@code /duel claim}). */
    public int pendingClaims(UUID playerId) {
        return ports.spool().owedCount(playerId);
    }

    /** {@code /duel claim}: hand over what the escrow owes (not during a match). */
    public DuelEscrowSpool.Delivery claim(Player player) {
        if (matchOfPlayer.containsKey(player.getUniqueId())) return DuelEscrowSpool.Delivery.NOTHING;
        return ports.spool().deliver(player);
    }

    /**
     * Accept the current offer {@code offerId} (DESIGN §6.5): the accept rules, the queue cap, the teleport checks,
     * the items taken into the escrow, the API lock-in; then both lobby teleports start. Completes on the main thread.
     */
    public CompletableFuture<LockOutcome> accept(Player accepter, long offerId) {
        if (!config.enabled()) return done(LockOutcome.refused("Duels are turned off."));
        if (!ports.recovery().isStartupDone()) {
            return done(LockOutcome.refused("Duels aren't available right now; try again in a moment."));
        }
        DuelNegotiation negotiation = offers.byCurrentOfferId(offerId);
        if (negotiation == null) return done(LockOutcome.refused("That offer is no longer current."));
        Player inviter = ports.players().apply(negotiation.inviter());
        Player invitee = ports.players().apply(negotiation.invitee());
        if (inviter == null || invitee == null || !inviter.isOnline() || !invitee.isOnline()) {
            return done(LockOutcome.refused("The other player is offline."));
        }
        for (Player p : List.of(inviter, invitee)) {
            Optional<String> refusal = inviteRefusal(p);
            if (refusal.isPresent()) {
                return done(LockOutcome.refused(p == accepter ? refusal.get()
                        : p.getName() + " can't duel right now: " + refusal.get()));
            }
        }
        Integer challengerUser = ports.userIds().apply(inviter.getUniqueId());
        Integer opponentUser = ports.userIds().apply(invitee.getUniqueId());
        if (challengerUser == null || opponentUser == null) {
            return done(LockOutcome.refused("A profile is still loading; try again in a moment."));
        }
        Optional<KnkArena> arenaFound = ports.arenas().apply(negotiation.arenaId());
        if (arenaFound.isEmpty() || !arenaFound.get().duelEnabled()) {
            return done(LockOutcome.refused("That arena isn't open for duels right now."));
        }
        KnkArena arena = arenaFound.get();
        Optional<Location> lobby = Optional.ofNullable(arena.lobbyLocation()).flatMap(ports.locations());
        Optional<Location> exit = Optional.ofNullable(arena.exitOrDomainLocation()).flatMap(ports.locations());
        if (lobby.isEmpty() || exit.isEmpty()) return done(LockOutcome.refused("That arena isn't set up for duels yet."));

        DuelOfferBook.Outcome accepted = offers.accept(accepter.getUniqueId(), offerId, now());
        if (!accepted.ok()) return done(LockOutcome.refused(DuelTexts.offerStatus(accepted.status())));
        DuelArenaSettings settings = negotiation.settings();
        StakeContract contract = negotiation.currentOffer().contract();
        UUID token = UUID.randomUUID();
        ArenaQueue queue = queueFor(arena, settings);
        if (!queue.admit(token.toString())) {
            return done(failLockBeforeMatch(negotiation, "The arena is full (" + queue.queuedCount() + "/"
                    + queue.maxQueuedMatches() + " waiting); try again shortly."));
        }
        DuelStateMachine machine;
        try {
            machine = DuelStateMachine.forContract(contract, settings, config.agreedDrawWindowSeconds());
        } catch (IllegalArgumentException invalid) {
            queue.remove(token.toString());
            return done(failLockBeforeMatch(negotiation, "That arena has no kit for this gear mode."));
        }
        DuelMatch match = new DuelMatch(token, negotiation.negotiationId(), arena, settings, contract, machine,
                new DuelMatch.Party(inviter.getUniqueId(), inviter.getName(), challengerUser),
                new DuelMatch.Party(invitee.getUniqueId(), invitee.getName(), opponentUser),
                negotiation.offerCount());
        register(match);

        CompletableFuture<LockOutcome> outcome = new CompletableFuture<>();
        CompletableFuture<Optional<TeleportDenial>> inviterCheck = ports.teleporter().checkLobby(inviter, lobby.get());
        CompletableFuture<Optional<TeleportDenial>> inviteeCheck = ports.teleporter().checkLobby(invitee, lobby.get());
        CompletableFuture.allOf(inviterCheck, inviteeCheck).whenComplete((ignored, error) -> main(() -> {
            if (!matches.containsKey(token)) {
                outcome.complete(LockOutcome.refused("The duel was cancelled."));
                return;
            }
            if (error != null) {
                outcome.complete(failLock(match, null, "Teleporting isn't available right now."));
                return;
            }
            Optional<TeleportDenial> denied = inviterCheck.join().or(inviteeCheck::join);
            if (denied.isPresent()) {
                Player who = inviterCheck.join().isPresent() ? inviter : invitee;
                outcome.complete(failLock(match, null, who.getName() + " can't be teleported: " + denied.get().message()));
                return;
            }
            takeStakesAndLock(match, inviter, invitee, outcome);
        }));
        return outcome;
    }

    /** {@code /duel kit <kit>} in WARMUP ({@code KIT_CHOICE}): swaps the loaned kit. */
    public DuelStateMachine.KitChoice chooseKit(Player player, int kitId) {
        Optional<DuelMatch> found = matchOf(player.getUniqueId());
        if (found.isEmpty()) return DuelStateMachine.KitChoice.NOT_IN_WARMUP;
        DuelMatch match = found.get();
        DuelSide side = match.sideOf(player.getUniqueId()).orElseThrow();
        DuelStateMachine.KitChoiceOutcome result = match.machine().chooseKit(side, kitId);
        if (result.status() == DuelStateMachine.KitChoice.CHOSEN) {
            apply(match, result.effects());
            notify(o -> o.kitChosen(match, side, kitId));
        }
        return result.status();
    }

    /** {@code /duel draw} in FIGHT: an offer, or an agreed draw within the window. */
    public DuelStateMachine.DrawOffer offerDraw(Player player) {
        Optional<DuelMatch> found = matchOf(player.getUniqueId());
        if (found.isEmpty()) return DuelStateMachine.DrawOffer.NOT_IN_FIGHT;
        DuelMatch match = found.get();
        DuelSide side = match.sideOf(player.getUniqueId()).orElseThrow();
        DuelStateMachine.DrawOutcome result = match.machine().offerDraw(side, now());
        apply(match, result.effects());
        return result.status();
    }

    /** Staff {@code /knk duel cancel}: any phase, refund (ADMIN_CANCELLED). False when there is no such match. */
    public boolean cancel(UUID token) {
        DuelMatch match = matches.get(token);
        if (match == null || match.machine().ended()) return false;
        if (match.phase() == DuelPhase.LOCKING) return false; // the lock-in answers first
        apply(match, match.machine().abort(DuelEndReason.ADMIN_CANCELLED, now()));
        return true;
    }

    /**
     * Re-read a held result after a staff release or void (P5's {@code /knk duel review}, or the web app): settles the
     * escrow from the API's record and delivers to online players.
     */
    public CompletableFuture<Boolean> refreshEscrow(UUID token) {
        return ports.recovery().refresh(token).thenApply(done -> {
            main(() -> deliverToOnlineParties(token));
            return done;
        });
    }

    // ==================== Hooks for the listeners and the registry ====================

    /** The duelist's combatant for P3's {@link DuelCombatRules}, or null when not in a match. */
    public DuelCombatRules.Combatant combatantOf(UUID playerId) {
        return matchOf(playerId).filter(m -> !isReleased(m, playerId))
                .map(m -> DuelCombatRules.Combatant.of(m.token().toString(), m.machine())).orElse(null);
    }

    /** WorldGuard's own PvP block ({@code DisallowedPVPEvent}) is lifted for the fighting pair only (P3 rule). */
    public boolean liftsWorldGuardPvpBlock(UUID attacker, UUID defender) {
        return DuelCombatRules.overrideWorldGuardPvpBlock(combatantOf(attacker), combatantOf(defender));
    }

    /** In a match and not yet given their gear back (LOCKING..ENDING): guards, held deliveries. */
    public boolean isParticipating(UUID playerId) {
        return matchOf(playerId).filter(m -> !isReleased(m, playerId)).isPresent();
    }

    /** Frozen to the spawn block in the countdown (DESIGN §8.3). */
    public Optional<Location> frozenAt(UUID playerId) {
        return matchOf(playerId).filter(m -> m.phase() == DuelPhase.BATTLE_COUNTDOWN)
                .flatMap(m -> m.sideOf(playerId).map(m.spawnBlock::get));
    }

    /** The countdown and the fight: the command allow-list and the confinement apply. */
    public boolean isOnBattleground(UUID playerId) {
        return matchOf(playerId).filter(m -> !isReleased(m, playerId))
                .map(m -> m.phase() == DuelPhase.BATTLE_COUNTDOWN || m.phase() == DuelPhase.FIGHT).orElse(false);
    }

    /** Kit consumables are blocked in WARMUP (DESIGN §5.1, §8.4). */
    public boolean blocksKitConsumables(UUID playerId) {
        return matchOf(playerId).map(m -> m.gearMode().isKitMode() && m.phase() == DuelPhase.WARMUP).orElse(false);
    }

    /** Projectiles and pearls are blocked in the countdown (DESIGN §5.1). */
    public boolean blocksProjectiles(UUID playerId) {
        return matchOf(playerId).map(m -> m.phase() == DuelPhase.BATTLE_COUNTDOWN || blocksKitConsumables(playerId))
                .orElse(false);
    }

    /** Fight telemetry: an attack on the opponent was tried; {@code dealt} &gt; 0 when it landed. */
    public void recordHit(UUID attacker, UUID victim, boolean landed, double dealt) {
        Optional<DuelMatch> found = matchOf(attacker);
        if (found.isEmpty() || found.get().phase() != DuelPhase.FIGHT) return;
        DuelMatch match = found.get();
        Optional<DuelSide> a = match.sideOf(attacker);
        Optional<DuelSide> v = match.sideOf(victim);
        if (a.isEmpty() || v.isEmpty() || a.get() == v.get()) return;
        DuelMatch.Counters mine = match.counters.get(a.get());
        mine.attackAttempts++;
        if (landed) {
            mine.hits++;
            mine.damageDealt += dealt;
            match.counters.get(v.get()).damageTaken += dealt;
        }
    }

    /**
     * A duelist died (PlayerDeathEvent at HIGHEST, DESIGN §8.4–8.6): keep the inventory and level, no drops, except
     * the loser in drop mode from the countdown on, whose drops become spoils (written to the spool first).
     */
    public DeathHandling onDeath(Player victim, List<ItemStack> drops) {
        Optional<DuelMatch> found = matchOf(victim.getUniqueId());
        if (found.isEmpty() || isReleased(found.get(), victim.getUniqueId())) return DeathHandling.NONE;
        DuelMatch match = found.get();
        DuelSide side = match.sideOf(victim.getUniqueId()).orElseThrow();
        DuelPhase phase = match.phase();
        boolean keepInventory = true;
        if (match.gearMode().dropsInventory() && phase.quitForfeits() && !match.machine().ended()) {
            if (ports.spool().addSpoils(match.token(), side, drops)) {
                keepInventory = false;
            } else {
                ports.logger().warning("[Duel] Could not spool the spoils of " + victim.getName()
                        + "; they keep their inventory");
            }
        }
        exitLocation(match).ifPresent(exit -> respawnAt.put(victim.getUniqueId(), exit));
        apply(match, match.machine().onDeath(side, now()));
        ports.scheduler().nextTick(() -> advance(match));
        return new DeathHandling(true, keepInventory, true);
    }

    /** Where a duelist respawns (the arena exit), or empty when the duel has no say. */
    public Optional<Location> respawnLocation(Player player) {
        Optional<Location> pending = ports.vault().takePendingRespawn(player.getUniqueId());
        Location exit = respawnAt.remove(player.getUniqueId());
        if (exit != null) return Optional.of(exit);
        return pending;
    }

    /** After the respawn (next tick): a loser of a finished match is given their gear back now. */
    public void afterRespawn(Player player) {
        Optional<DuelMatch> found = matchOf(player.getUniqueId());
        if (found.isEmpty()) return;
        DuelMatch match = found.get();
        if (match.machine().ended() && !isReleased(match, player.getUniqueId())) {
            releaseSide(match, match.sideOf(player.getUniqueId()).orElseThrow(), Exit.IN_PLACE);
            if (bothReleased(match)) finishRelease(match);
        }
    }

    /**
     * A duelist quit (DESIGN D10): before the countdown it cancels the match, from the countdown on it forfeits.
     * Gear comes back inside the quit event; a forfeiting quitter in drop mode loses their inventory as spoils (O2).
     * Negotiations of the player are closed.
     */
    public void onQuit(Player player) {
        UUID id = player.getUniqueId();
        List<DuelOfferBook.Closed> closed = offers.forget(id);
        if (!closed.isEmpty()) notify(o -> o.negotiationsClosed(closed));
        stakeItems.forget(id);
        Optional<DuelMatch> found = matchOf(id);
        if (found.isEmpty()) return;
        DuelMatch match = found.get();
        DuelSide side = match.sideOf(id).orElseThrow();
        if (isReleased(match, id)) return;
        if (match.phase() == DuelPhase.LOCKING) return; // the lock-in sees the player gone and refunds
        if (match.gearMode().dropsInventory() && match.phase().quitForfeits() && !match.machine().ended()) {
            List<ItemStack> inventory = Arrays.stream(player.getInventory().getContents())
                    .filter(s -> s != null && !s.isEmpty()).toList();
            if (ports.spool().addSpoils(match.token(), side, inventory)) {
                player.getInventory().clear();
            }
        }
        // Gear back inside the quit event, while the player is still valid (vault: restoreOnQuit).
        releaseSide(match, side, Exit.QUIT);
        apply(match, match.machine().onQuit(side, now()));
        if (matches.containsKey(match.token())) ports.scheduler().nextTick(() -> advance(match));
    }

    /**
     * Join (two ticks later): a stray loaned item is swept, a vault left by a crash or a quit is restored, and what
     * the escrow owes is delivered (DESIGN §7.3, §8.4).
     */
    public void onJoin(Player player) {
        UUID id = player.getUniqueId();
        if (matchOfPlayer.containsKey(id)) return;
        int swept = ports.kits().removeLoans(player);
        if (swept > 0) ports.logger().info("[Duel] Removed " + swept + " loaned kit item(s) from " + player.getName());
        if (ports.vault().hasFile(id) && ports.vault().restoreOnJoin(player)) {
            ports.afterRestore().accept(player);
        }
        for (DuelEscrowSpool.Entry entry : ports.spool().entriesOf(id)) {
            if (entry.state() == net.knightsandkings.knk.core.duel.DuelEscrowState.HELD) {
                player.sendMessage(ChatColor.YELLOW + "A duel result of yours is under staff review; its stakes are kept safe.");
                refreshEscrow(entry.token());
            }
        }
        DuelEscrowSpool.Delivery delivery = ports.spool().deliver(player);
        tellDelivery(player, delivery);
    }

    /**
     * The API is down and an outage action will move this player (hub DESIGN §16.4): the match is aborted
     * ({@code API_OUTAGE}), gear and items come back at once, the coin refund is spooled until the API is back.
     */
    public boolean releaseForOutage(UUID playerId) {
        Optional<DuelMatch> found = matchOf(playerId);
        if (found.isEmpty()) return true;
        DuelMatch match = found.get();
        if (match.phase() == DuelPhase.LOCKING) return false; // the lock-in answers first; asked again later
        if (!match.machine().ended()) apply(match, match.machine().abort(DuelEndReason.API_OUTAGE, now()));
        if (matches.containsKey(match.token())) {
            match.sideOf(playerId).ifPresent(side -> releaseSide(match, side, Exit.IN_PLACE));
            if (bothReleased(match)) finishRelease(match);
        }
        return true;
    }

    /** The API is back: send the spooled results now. */
    public void resumeAfterOutage() {
        replayResults();
    }

    /**
     * The 1 s ticker: deadlines, the arena queues, expired offers, and (every few seconds) the spooled results, the
     * startup recovery and held results.
     */
    public void tick() {
        long now = now();
        for (DuelMatch match : List.copyOf(matches.values())) advance(match);
        for (ArenaQueue queue : List.copyOf(queues.values())) {
            ArenaQueue.Update update = queue.advance(now);
            for (ArenaQueue.Assignment assignment : update.assigned()) {
                DuelMatch match = matches.get(UUID.fromString(assignment.matchToken()));
                if (match == null) {
                    queue.releaseBattleground(assignment.matchToken());
                    continue;
                }
                List<DuelEffect> effects = match.machine().battlegroundAssigned(assignment.battlegroundId(), now);
                if (effects.isEmpty()) queue.releaseBattleground(assignment.matchToken());
                else apply(match, effects);
            }
            for (String timedOut : update.timedOut()) {
                DuelMatch match = matches.get(UUID.fromString(timedOut));
                if (match != null) apply(match, match.machine().noBattleground(now));
            }
        }
        List<DuelOfferBook.Closed> expired = offers.tick(now);
        if (!expired.isEmpty()) notify(o -> o.negotiationsClosed(expired));
        long retryTicks = DuelTicks.ofSeconds(config.resultRetrySeconds());
        if (lastRetryTick == Long.MIN_VALUE || now - lastRetryTick >= retryTicks) {
            lastRetryTick = now;
            if (ports.recovery().needsRetry()) ports.recovery().recoverOnStartup();
            else if (!ports.results().isEmpty()) replayResults();
        }
        long heldTicks = DuelTicks.ofSeconds(config.heldRefreshSeconds());
        if (lastHeldRefreshTick == Long.MIN_VALUE || now - lastHeldRefreshTick >= heldTicks) {
            lastHeldRefreshTick = now;
            for (DuelEscrowSpool.Entry entry : ports.spool().entries()) {
                if (entry.state() == net.knightsandkings.knk.core.duel.DuelEscrowState.HELD) refreshEscrow(entry.token());
            }
        }
    }

    /**
     * Plugin disable: every running match is aborted ({@code SERVER_RESTART}); gear and items come back now, while
     * the players still exist. The coin refunds follow from {@code abort-unfinished} on the next start.
     */
    public void shutdown() {
        shuttingDown = true;
        for (DuelMatch match : List.copyOf(matches.values())) {
            try {
                if (match.phase() == DuelPhase.LOCKING) {
                    refundItems(match);
                    dropMatch(match);
                    continue;
                }
                if (!match.machine().ended()) apply(match, match.machine().abort(DuelEndReason.SERVER_RESTART, now()));
                for (DuelSide side : DuelSide.values()) releaseSide(match, side, Exit.IN_PLACE);
                if (matches.containsKey(match.token())) finishRelease(match);
            } catch (RuntimeException e) {
                ports.logger().log(Level.SEVERE, "[Duel] Shutdown of match " + match.token() + " failed", e);
            }
        }
    }

    /** Whether the vault is restoring {@code player} without its own teleport (the vault teleporter asks). */
    public boolean restoresInPlace(Player player) {
        return restoringInPlace.contains(player.getUniqueId());
    }

    // ==================== Lock-in ====================

    private void takeStakesAndLock(DuelMatch match, Player inviter, Player invitee, CompletableFuture<LockOutcome> outcome) {
        Map<DuelSide, Player> players = Map.of(DuelSide.CHALLENGER, inviter, DuelSide.OPPONENT, invitee);
        Map<DuelSide, List<DuelStakeItems.Found>> found = new EnumMap<>(DuelSide.class);
        for (DuelSide side : DuelSide.values()) {
            Player owner = players.get(side);
            if (!owner.isOnline()) {
                outcome.complete(failLock(match, null, owner.getName() + " went offline."));
                return;
            }
            List<StakeItem> stake = match.contract().stakeOf(side).items();
            Optional<List<DuelStakeItems.Found>> located = stakeItems.locate(owner, stake);
            if (located.isEmpty()) {
                outcome.complete(failLock(match, null, owner.getName() + " no longer has every staked item."));
                return;
            }
            for (DuelStakeItems.Found f : located.get()) {
                Optional<String> refusal = ports.items().stakeRefusal(f.taken());
                if (refusal.isPresent()) {
                    outcome.complete(failLock(match, null, owner.getName() + ": " + refusal.get()));
                    return;
                }
            }
            found.put(side, located.get());
        }
        for (DuelSide side : DuelSide.values()) {
            match.stakeStacks.put(side, found.get(side).stream().map(DuelStakeItems.Found::taken).toList());
        }
        DuelEscrowSpool.Entry entry = new DuelEscrowSpool.Entry(match.token(), match.arena().id(),
                party(match, DuelSide.CHALLENGER), party(match, DuelSide.OPPONENT),
                match.stakeStacks.get(DuelSide.CHALLENGER), match.stakeStacks.get(DuelSide.OPPONENT));
        if (!ports.spool().open(entry)) {
            outcome.complete(failLock(match, null, "The duel couldn't be prepared; nothing was taken."));
            return;
        }
        for (DuelSide side : DuelSide.values()) DuelStakeItems.take(players.get(side), found.get(side));

        List<ItemSummary> summaries = new ArrayList<>();
        for (DuelSide side : DuelSide.values()) {
            for (ItemStack stack : match.stakeStacks.get(side)) summaries.add(ports.items().summary(stack, match.userIdOf(side)));
        }
        StakeContract contract = match.contract();
        LockRequest request = new LockRequest(match.token(), match.arena().id(), match.userIdOf(DuelSide.CHALLENGER),
                match.userIdOf(DuelSide.OPPONENT), contract.gearMode(), contract.fixedKitId(),
                contract.challenger().coins(), contract.opponent().coins(), summaries, match.offerCount, Instant.now());
        CompletableFuture<MatchRecord> lock;
        try {
            lock = ports.api().lock(request);
        } catch (RuntimeException e) {
            lock = CompletableFuture.failedFuture(e);
        }
        lock.orTimeout(config.apiTimeoutSeconds(), TimeUnit.SECONDS).whenComplete((record, error) -> main(() -> {
            if (error == null && record != null) {
                lockSucceeded(match, record, outcome);
                return;
            }
            DuelRefusedException refused = DuelRefusedException.find(error);
            if (refused != null) {
                outcome.complete(failLock(match, true, refused.playerMessage()));
                return;
            }
            ports.logger().log(Level.WARNING, "[Duel] Lock-in of " + match.token() + " got no answer; reading it by token", error);
            lockTimedOut(match, outcome);
        }));
    }

    private void lockSucceeded(DuelMatch match, MatchRecord record, CompletableFuture<LockOutcome> outcome) {
        match.matchId = record.id();
        ports.spool().escrowed(match.token(), record.id());
        List<DuelOfferBook.Closed> superseded = offers.lockSucceeded(match.negotiationId());
        if (!superseded.isEmpty()) notify(o -> o.negotiationsClosed(superseded));
        for (DuelSide side : DuelSide.values()) stakeItems.forget(match.playerOf(side));
        // A player who left while the API answered fails the lobby teleport: cancelled and refunded (D6).
        apply(match, match.machine().lockSucceeded(now()));
        outcome.complete(new LockOutcome(true, null, match));
    }

    /** Timeout (DESIGN §6.5 step 4): a create that did land is cancelled and refunded rather than leaked. */
    private void lockTimedOut(DuelMatch match, CompletableFuture<LockOutcome> outcome) {
        CompletableFuture<Optional<MatchRecord>> lookup;
        try {
            lookup = ports.api().findByToken(match.token());
        } catch (RuntimeException e) {
            lookup = CompletableFuture.failedFuture(e);
        }
        lookup.orTimeout(config.apiTimeoutSeconds(), TimeUnit.SECONDS).whenComplete((record, error) -> main(() -> {
            if (error == null && record != null && record.isPresent()) {
                match.matchId = record.get().id();
                ports.spool().escrowed(match.token(), record.get().id());
                ports.results().report(new DuelPendingResults.Pending(match.token(), record.get().id(),
                        DuelEndReason.API_OUTAGE, null, null, List.of(), Instant.now())).exceptionally(e -> null);
            }
            outcome.complete(failLock(match, true, "Duels aren't available right now; nothing was taken."));
        }));
    }

    /**
     * The lock-in failed: items back (when they were taken), the negotiation closed, the match gone.
     *
     * @param itemsTaken true/false when known, null to read the spool
     */
    private LockOutcome failLock(DuelMatch match, Boolean itemsTaken, String message) {
        apply(match, match.machine().lockFailed(now()));
        if (!Boolean.FALSE.equals(itemsTaken) && ports.spool().find(match.token()).isPresent()) {
            ports.spool().settle(match.token(), DuelEscrowPlan.refund(null));
            deliverToOnlineParties(match.token());
        }
        dropMatch(match);
        DuelOfferBook.Closed closed = offers.lockFailed(match.negotiationId());
        DuelNegotiation negotiation = closed != null ? closed.negotiation() : null;
        if (closed != null) notify(o -> o.negotiationsClosed(List.of(closed)));
        if (negotiation != null) notify(o -> o.lockRefused(negotiation, message));
        for (DuelSide side : DuelSide.values()) {
            Player p = ports.players().apply(match.playerOf(side));
            if (p != null && p.isOnline()) p.sendMessage(ChatColor.RED + "Duel not started: " + message);
        }
        return LockOutcome.refused(message);
    }

    private LockOutcome failLockBeforeMatch(DuelNegotiation negotiation, String message) {
        DuelOfferBook.Closed closed = offers.lockFailed(negotiation.negotiationId());
        if (closed != null) notify(o -> o.negotiationsClosed(List.of(closed)));
        notify(o -> o.lockRefused(negotiation, message));
        return LockOutcome.refused(message);
    }

    // ==================== Effects ====================

    private void advance(DuelMatch match) {
        if (!matches.containsKey(match.token())) return;
        apply(match, match.machine().advance(now()));
    }

    private void apply(DuelMatch match, List<DuelEffect> effects) {
        for (DuelEffect effect : effects) {
            try {
                applyOne(match, effect);
            } catch (RuntimeException e) {
                ports.logger().log(Level.SEVERE, "[Duel] Applying " + effect + " to match " + match.token() + " failed", e);
            }
        }
    }

    private void applyOne(DuelMatch match, DuelEffect effect) {
        switch (effect) {
            case DuelEffect.PhaseChanged changed -> notify(o -> o.phaseChanged(match, changed.from(), changed.to()));
            case DuelEffect.StartLobbyTeleports ignored -> startLobbyTeleports(match);
            case DuelEffect.WarmupStarted started -> {
                tellBoth(match, ChatColor.GOLD + "Warmup: the duel starts in " + started.warmupSeconds() + " seconds.");
                notify(o -> o.warmupStarted(match, started.warmupSeconds()));
            }
            case DuelEffect.GrantKit grant -> grantKit(match, grant.side(), grant.kitId());
            case DuelEffect.SwapKit swap -> {
                Player p = online(match, swap.side());
                if (p != null) {
                    ports.kits().removeLoans(p);
                    grantKit(match, swap.side(), swap.toKitId());
                }
            }
            case DuelEffect.AwaitBattleground ignored -> {
                ArenaQueue queue = queues.get(match.arena().id());
                if (queue != null) queue.readyForBattleground(match.token().toString(), now());
            }
            case DuelEffect.KitsLocked ignored -> { }
            case DuelEffect.EnterBattleground enter -> enterBattleground(match, enter.battlegroundId(), enter.countdownSeconds());
            case DuelEffect.FightStarted ignored -> startFight(match);
            case DuelEffect.DrawOffered offered -> {
                Player other = online(match, offered.side().other());
                if (other != null) {
                    other.sendMessage(ChatColor.YELLOW + match.nameOf(offered.side())
                            + " offers a draw. Type /duel draw within " + config.agreedDrawWindowSeconds() + " seconds to agree.");
                }
                notify(o -> o.drawOffered(match, offered.side(), offered.expiresAtTick()));
            }
            case DuelEffect.MatchEnded ended -> matchEnded(match, ended.result());
            case DuelEffect.Release release -> releaseMatch(match);
        }
    }

    private void startLobbyTeleports(DuelMatch match) {
        Location lobby = Optional.ofNullable(match.arena().lobbyLocation()).flatMap(ports.locations()).orElse(null);
        Map<DuelSide, CompletableFuture<TeleportOutcome>> moves = new EnumMap<>(DuelSide.class);
        for (DuelSide side : DuelSide.values()) {
            Player p = online(match, side);
            CompletableFuture<TeleportOutcome> move = p == null || lobby == null
                    ? CompletableFuture.completedFuture(TeleportOutcome.failed("unavailable"))
                    : ports.teleporter().lobby(p, lobby, subject -> prepareForLobby(match, side, subject));
            moves.put(side, move);
        }
        for (DuelSide side : DuelSide.values()) {
            moves.get(side).whenComplete((outcome, error) -> main(() -> {
                boolean ok = error == null && outcome != null && outcome.isTeleported();
                if (ok) {
                    match.inArena.put(side, true);
                } else {
                    undoPreparation(match, side);
                    ports.teleporter().cancelWarmup(match.playerOf(side.other()));
                    Player p = online(match, side);
                    if (p != null && outcome != null && outcome.message() != null) p.sendMessage(ChatColor.RED + outcome.message());
                }
            }));
        }
        CompletableFuture.allOf(moves.values().toArray(CompletableFuture[]::new)).handle((ignored, error) -> {
            main(() -> {
                boolean both = moves.values().stream().allMatch(f -> {
                    TeleportOutcome o = f.getNow(null);
                    return !f.isCompletedExceptionally() && o != null && o.isTeleported();
                });
                if (both) {
                    apply(match, match.machine().travelCompleted(now()));
                } else {
                    tellBoth(match, ChatColor.RED + "The duel was cancelled: a teleport to the arena didn't happen. Your stakes are refunded.");
                    apply(match, match.machine().travelFailed(now()));
                }
            });
            return null;
        });
    }

    /**
     * The lobby teleport's commit step (DESIGN §8.4 step 1): own gear takes the small restore snapshot; kit modes
     * write the vault and clear the inventory. False refuses the move (the vault couldn't be written).
     */
    private boolean prepareForLobby(DuelMatch match, DuelSide side, Player player) {
        if (!matches.containsKey(match.token()) || match.machine().ended()) return false;
        match.stats.put(side, new DuelMatch.StatSnapshot(player.getHealth(), player.getFoodLevel(), player.getSaturation(),
                List.copyOf(player.getActivePotionEffects())));
        if (!match.gearMode().isKitMode()) return true;
        if (!ports.vault().snapshot(player, match.token().toString(), match.arena().id())) {
            player.sendMessage(ChatColor.RED + "Your items couldn't be stored safely; the duel is cancelled.");
            return false;
        }
        player.closeInventory();
        player.setItemOnCursor(null);
        player.getInventory().clear();
        player.updateInventory();
        return true;
    }

    /** The lobby teleport didn't happen after its commit step ran: give the vault back on the spot. */
    private void undoPreparation(DuelMatch match, DuelSide side) {
        Player p = online(match, side);
        if (p == null) return;
        if (match.gearMode().isKitMode() && ports.vault().has(p.getUniqueId())) {
            ports.kits().removeLoans(p);
            restoreVaultInPlace(p);
        }
        match.stats.remove(side);
    }

    private void grantKit(DuelMatch match, DuelSide side, int kitId) {
        Player p = online(match, side);
        if (p == null) return;
        ports.kits().grant(p, match.token(), kitId).whenComplete((placed, error) -> {
            if (error != null || !Boolean.TRUE.equals(placed)) {
                ports.logger().warning("[Duel] Kit " + kitId + " was not granted to " + match.nameOf(side));
            }
        });
    }

    private void enterBattleground(DuelMatch match, int battlegroundId, int countdownSeconds) {
        KnkArenaBattleground battleground = match.arena().battlegrounds().stream()
                .filter(bg -> bg.id() == battlegroundId).findFirst().orElse(null);
        Map<DuelSide, Location> spawns = new EnumMap<>(DuelSide.class);
        if (battleground != null) {
            ports.locations().apply(battleground.spawnA()).ifPresent(l -> spawns.put(DuelSide.CHALLENGER, l));
            ports.locations().apply(battleground.spawnB()).ifPresent(l -> spawns.put(DuelSide.OPPONENT, l));
        }
        match.battleground = battleground;
        if (battleground == null || spawns.size() != 2) {
            ports.logger().warning("[Duel] Battleground " + battlegroundId + " of arena " + match.arena().key() + " has no usable spawns");
            apply(match, match.machine().battlegroundTeleportFailed(now()));
            return;
        }
        Map<DuelSide, CompletableFuture<TeleportOutcome>> moves = new EnumMap<>(DuelSide.class);
        for (DuelSide side : DuelSide.values()) {
            Player p = online(match, side);
            if (p == null) {
                moves.put(side, CompletableFuture.completedFuture(TeleportOutcome.failed("offline")));
                continue;
            }
            ports.access().grant(p.getUniqueId(), battleground.wgRegionId(), this::isOnBattleground);
            match.spawnBlock.put(side, spawns.get(side));
            moves.put(side, ports.teleporter().system(p, spawns.get(side), "duel spawn"));
        }
        CompletableFuture.allOf(moves.values().toArray(CompletableFuture[]::new)).handle((ignored, error) -> {
            main(() -> {
                boolean both = moves.values().stream().allMatch(f -> {
                    TeleportOutcome o = f.getNow(null);
                    return !f.isCompletedExceptionally() && o != null && o.isTeleported();
                });
                if (!both) {
                    apply(match, match.machine().battlegroundTeleportFailed(now()));
                    return;
                }
                for (DuelSide side : DuelSide.values()) {
                    Player p = online(match, side);
                    if (p != null) match.spawnBlock.put(side, p.getLocation().clone());
                }
                tellBoth(match, ChatColor.GOLD + "Get ready: the fight starts in " + countdownSeconds + " seconds.");
            });
            return null;
        });
    }

    private void startFight(DuelMatch match) {
        match.fightStartTick = now();
        for (DuelSide side : DuelSide.values()) {
            Player p = online(match, side);
            if (p == null) continue;
            p.sendTitle(ChatColor.RED + "FIGHT!", "", 0, 30, 10);
            if (!match.gearMode().isKitMode()) {
                DuelMatch.Counters counters = match.counters.get(side);
                counters.armorPoints = armorPoints(p);
                ItemStack hand = p.getInventory().getItemInMainHand();
                counters.mainHand = hand == null || hand.isEmpty() ? null : hand.getType().name();
                counters.hadWeapon = counters.mainHand != null && isWeapon(counters.mainHand);
            }
        }
        notify(o -> o.fightStarted(match));
        if (match.matchId != null) {
            boolean choice = match.gearMode() == net.knightsandkings.knk.core.duel.GearMode.KIT_CHOICE;
            FightStart start = new FightStart(match.battleground != null ? match.battleground.id() : 0,
                    choice ? match.kitOf(DuelSide.CHALLENGER) : null, choice ? match.kitOf(DuelSide.OPPONENT) : null,
                    Instant.now());
            try {
                ports.api().startFight(match.matchId, match.token(), start).exceptionally(error -> {
                    ports.logger().log(Level.WARNING, "[Duel] start-fight of match " + match.token() + " failed", error);
                    return null;
                });
            } catch (RuntimeException e) {
                ports.logger().log(Level.WARNING, "[Duel] start-fight of match " + match.token() + " failed", e);
            }
        }
    }

    private void matchEnded(DuelMatch match, DuelResult result) {
        notify(o -> o.matchEnded(match, result));
        for (DuelSide side : DuelSide.values()) ports.teleporter().clearCombatTag(match.playerOf(side));
        if (result.reason() == DuelEndReason.LOCK_REFUSED) return;
        match.spoilsFrom = result.isDecisive() ? result.loser() : null;
        tellResult(match, result);
        if (!result.isDecisive()) {
            // Nothing can move between the players: their items come back now; the coins follow the API (§7.5).
            ports.spool().settle(match.token(), DuelEscrowPlan.refund(null));
        }
        if (match.matchId == null) return;
        DuelPendingResults.Pending pending = new DuelPendingResults.Pending(match.token(), match.matchId,
                result.reason(), result.isDecisive() ? match.userIdOf(result.winner()) : null, telemetry(match),
                spoilsSummary(match), Instant.now());
        ports.results().report(pending).whenComplete((answer, error) -> main(() -> {
            if (error == null) {
                settle(match, result, answer);
            } else if (DuelRefusedException.find(error) != null) {
                ports.recovery().refresh(match.token()).thenRun(() -> main(() -> deliverIfReleased(match)));
            } else if (result.isDecisive()) {
                Player winner = online(match, result.winner());
                if (winner != null) {
                    winner.sendMessage(ChatColor.YELLOW + "The server's services can't be reached; your winnings follow when they are back.");
                }
                notify(o -> o.settled(match, null));
            }
        }));
    }

    private void settle(DuelMatch match, DuelResult result, Result answer) {
        match.settlement = answer.settlement();
        if (result.isDecisive()) {
            DuelEscrowPlan.Decision decision = switch (answer.settlement()) {
                case HELD -> DuelEscrowPlan.Decision.HELD;
                case REFUNDED -> DuelEscrowPlan.Decision.VOIDED;
                default -> DuelEscrowPlan.Decision.PAID;
            };
            ports.spool().settle(match.token(), DuelEscrowPlan.forResult(result, decision, match.spoilsFrom));
            if (answer.settlement() == Settlement.HELD) {
                tellBoth(match, ChatColor.YELLOW + "This result is pending staff review; the stakes are kept safe until then.");
            }
        }
        notify(o -> o.settled(match, answer.settlement()));
        deliverIfReleased(match);
    }

    // ==================== Release ====================

    private void releaseMatch(DuelMatch match) {
        DuelResult result = match.result();
        boolean teleport = result == null || (result.reason() != DuelEndReason.API_OUTAGE
                && result.reason() != DuelEndReason.SERVER_RESTART && result.reason() != DuelEndReason.LOCK_REFUSED);
        for (DuelSide side : DuelSide.values()) {
            if (!isReleased(match, match.playerOf(side))) releaseSide(match, side, teleport && !shuttingDown ? Exit.TELEPORT : Exit.IN_PLACE);
        }
        finishRelease(match);
    }

    /** Give one side its gear back and send it out (DESIGN §8.4 restore table). Idempotent. */
    private void releaseSide(DuelMatch match, DuelSide side, Exit exit) {
        UUID id = match.playerOf(side);
        Map<DuelSide, Boolean> released = releasedSides.computeIfAbsent(match.token(), t -> new EnumMap<>(DuelSide.class));
        if (Boolean.TRUE.equals(released.get(side))) return;
        released.put(side, true);
        Player p = ports.players().apply(id);
        if (p != null && (p.isOnline() || exit == Exit.QUIT)) {
            restoreGear(match, side, p, exit == Exit.QUIT);
        }
        boolean wasInArena = Boolean.TRUE.equals(match.inArena.get(side));
        Location exitLocation = exitLocation(match).orElse(null);
        if (exit == Exit.TELEPORT && wasInArena && p != null && p.isOnline() && exitLocation != null) {
            if (p.isDead()) {
                respawnAt.put(id, exitLocation);
                ports.access().revoke(id);
            } else {
                ports.access().passExit(id);
                ports.teleporter().system(p, exitLocation, "arena exit").whenComplete((o, e) -> main(() -> ports.access().revoke(id)));
            }
        } else {
            ports.access().revoke(id);
        }
    }

    private void restoreGear(DuelMatch match, DuelSide side, Player player, boolean quitting) {
        if (match.gearMode().isKitMode()) {
            ports.kits().removeLoans(player);
            if (ports.vault().has(player.getUniqueId())) {
                if (quitting) ports.vault().restoreOnQuit(player);
                else restoreVaultInPlace(player);
            }
        } else {
            DuelMatch.StatSnapshot stats = match.stats.remove(side);
            if (stats != null && !player.isDead()) {
                player.setHealth(Math.max(0.5, Math.min(maxHealth(player), stats.health())));
                player.setFoodLevel(stats.foodLevel());
                player.setSaturation(stats.saturation());
                for (PotionEffect effect : List.copyOf(player.getActivePotionEffects())) player.removePotionEffect(effect.getType());
                player.addPotionEffects(stats.effects());
            }
            player.setFireTicks(0);
        }
    }

    private void restoreVaultInPlace(Player player) {
        restoringInPlace.add(player.getUniqueId());
        try {
            ports.vault().restore(player);
            ports.vault().takePendingRespawn(player.getUniqueId());
        } finally {
            restoringInPlace.remove(player.getUniqueId());
        }
    }

    private void finishRelease(DuelMatch match) {
        if (match.released) return;
        match.released = true;
        dropMatch(match);
        notify(o -> o.released(match));
        deliverIfReleased(match);
    }

    private void dropMatch(DuelMatch match) {
        ArenaQueue queue = queues.get(match.arena().id());
        if (queue != null) {
            queue.releaseBattleground(match.token().toString());
            queue.remove(match.token().toString());
        }
        matches.remove(match.token());
        releasedSides.remove(match.token());
        for (DuelSide side : DuelSide.values()) {
            matchOfPlayer.remove(match.playerOf(side), match.token());
            ports.access().revoke(match.playerOf(side));
        }
    }

    private void deliverIfReleased(DuelMatch match) {
        if (matches.containsKey(match.token()) || shuttingDown) return;
        deliverToOnlineParties(match.token());
    }

    private void deliverToOnlineParties(UUID token) {
        Optional<DuelEscrowSpool.Entry> entry = ports.spool().find(token);
        if (entry.isEmpty()) return;
        for (DuelSide side : DuelSide.values()) {
            UUID id = entry.get().party(side).playerId();
            Player p = ports.players().apply(id);
            if (p == null || !p.isOnline() || p.isDead() || matchOfPlayer.containsKey(id)) continue;
            tellDelivery(p, ports.spool().deliver(p));
            ports.afterRestore().accept(p);
        }
    }

    private void refundItems(DuelMatch match) {
        if (ports.spool().find(match.token()).isEmpty()) return;
        ports.spool().settle(match.token(), DuelEscrowPlan.refund(null));
    }

    // ==================== Helpers ====================

    private void register(DuelMatch match) {
        matches.put(match.token(), match);
        for (DuelSide side : DuelSide.values()) matchOfPlayer.put(match.playerOf(side), match.token());
    }

    private ArenaQueue queueFor(KnkArena arena, DuelArenaSettings settings) {
        List<Integer> battlegrounds = arena.usableBattlegrounds().stream().map(KnkArenaBattleground::id).toList();
        ArenaQueue queue = queues.computeIfAbsent(arena.id(),
                id -> new ArenaQueue(new BattlegroundAllocator(battlegrounds), settings.maxQueuedMatches(), settings.maxWaitSeconds()));
        queue.configure(settings.maxQueuedMatches(), settings.maxWaitSeconds());
        queue.allocator().setBattlegrounds(battlegrounds);
        return queue;
    }

    private boolean isReleased(DuelMatch match, UUID playerId) {
        Map<DuelSide, Boolean> released = releasedSides.get(match.token());
        if (released == null) return false;
        return match.sideOf(playerId).map(side -> Boolean.TRUE.equals(released.get(side))).orElse(true);
    }

    private boolean bothReleased(DuelMatch match) {
        Map<DuelSide, Boolean> released = releasedSides.get(match.token());
        return released != null && Boolean.TRUE.equals(released.get(DuelSide.CHALLENGER))
                && Boolean.TRUE.equals(released.get(DuelSide.OPPONENT)) && match.machine().ended();
    }

    private Player online(DuelMatch match, DuelSide side) {
        Player p = ports.players().apply(match.playerOf(side));
        return p != null && p.isOnline() ? p : null;
    }

    private Optional<Location> exitLocation(DuelMatch match) {
        return Optional.ofNullable(match.arena().exitOrDomainLocation()).flatMap(ports.locations());
    }

    private static DuelEscrowSpool.Party party(DuelMatch match, DuelSide side) {
        return new DuelEscrowSpool.Party(match.playerOf(side), match.userIdOf(side), match.nameOf(side));
    }

    private Telemetry telemetry(DuelMatch match) {
        double duration = match.fightStartTick < 0 ? 0 : (now() - match.fightStartTick) / (double) DuelTicks.PER_SECOND;
        return new Telemetry(duration, side(match.counters.get(DuelSide.CHALLENGER)), side(match.counters.get(DuelSide.OPPONENT)));
    }

    private static SideTelemetry side(DuelMatch.Counters c) {
        return new SideTelemetry(c.damageDealt, c.damageTaken, c.hits, c.attackAttempts, c.armorPoints, c.mainHand, c.hadWeapon);
    }

    private List<ItemSummary> spoilsSummary(DuelMatch match) {
        if (match.spoilsFrom == null) return List.of();
        return ports.spool().find(match.token())
                .map(e -> e.spoilsFrom(match.spoilsFrom).stream()
                        .map(stack -> ports.items().summary(stack, match.userIdOf(match.spoilsFrom))).toList())
                .orElse(List.of());
    }

    private void tellResult(DuelMatch match, DuelResult result) {
        for (DuelSide side : DuelSide.values()) {
            Player p = online(match, side);
            if (p == null) continue;
            String text;
            if (!result.isDecisive()) {
                text = ChatColor.YELLOW + DuelTexts.noWinner(result.reason()) + " Stakes are refunded.";
            } else if (result.winner() == side) {
                text = ChatColor.GREEN + "You won the duel against " + match.nameOf(side.other()) + "!";
            } else {
                text = ChatColor.RED + "You lost the duel against " + match.nameOf(side.other()) + ".";
            }
            p.sendMessage(text);
        }
    }

    private void tellBoth(DuelMatch match, String message) {
        for (DuelSide side : DuelSide.values()) {
            Player p = online(match, side);
            if (p != null) p.sendMessage(message);
        }
    }

    private static void tellDelivery(Player player, DuelEscrowSpool.Delivery delivery) {
        if (delivery.toEnderChest() > 0) {
            player.sendMessage(ChatColor.YELLOW + "Your inventory was full: " + delivery.toEnderChest()
                    + " duel item(s) went to your ender chest.");
        }
        if (delivery.stillOwed() > 0) {
            player.sendMessage(ChatColor.YELLOW + "" + delivery.stillOwed()
                    + " duel item(s) didn't fit anywhere. Make room and use /duel claim.");
        }
    }

    private void replayResults() {
        ports.results().replay((pending, result) -> main(() -> {
            ports.recovery().settleFromResult(pending.token(), result);
            deliverToOnlineParties(pending.token());
        }));
    }

    private void notify(Consumer<DuelObserver> call) {
        for (DuelObserver observer : observers) {
            try {
                call.accept(observer);
            } catch (RuntimeException e) {
                ports.logger().log(Level.WARNING, "[Duel] Observer " + observer.getClass().getName() + " failed", e);
            }
        }
    }

    private void main(Runnable task) {
        ports.scheduler().mainThread().execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                ports.logger().log(Level.SEVERE, "[Duel] A duel step failed", e);
            }
        });
    }

    private static <T> CompletableFuture<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private static Integer armorPoints(Player player) {
        try {
            AttributeInstance armor = player.getAttribute(Attribute.ARMOR);
            return armor == null ? null : (int) Math.round(armor.getValue());
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static double maxHealth(Player player) {
        try {
            AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
            return max == null ? 20.0 : max.getValue();
        } catch (RuntimeException | LinkageError e) {
            return 20.0;
        }
    }

    private static boolean isWeapon(String material) {
        return material.endsWith("_SWORD") || material.endsWith("_AXE") || material.equals("BOW")
                || material.equals("CROSSBOW") || material.equals("TRIDENT") || material.equals("MACE");
    }

    /** Matches by token (tests). */
    Collection<UUID> tokens() {
        return List.copyOf(matches.keySet());
    }
}
