package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaArea;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaAreaRegion;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaKit;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.ItemSummary;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.MatchRecord;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Result;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Review;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Settlement;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Status;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.UndeliveredItems;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.duel.DuelArenaSettings;
import net.knightsandkings.knk.core.duel.DuelOfferBook;
import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.duel.DuelTicks;
import net.knightsandkings.knk.core.duel.GearMode;
import net.knightsandkings.knk.core.duel.Stake;
import net.knightsandkings.knk.core.duel.StakeContract;
import net.knightsandkings.knk.core.duel.StakeItem;
import net.knightsandkings.knk.core.minigame.MinigameRegistry;
import net.knightsandkings.knk.core.ports.api.DuelMatchesApi;
import net.knightsandkings.knk.core.teleport.TeleportDenial;
import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.paper.minigame.MinigamePlayerVault;
import net.knightsandkings.knk.paper.teleport.TeleportPlan;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A real {@link DuelService} with a real vault, spool, pending results and recovery, around fakes: the API (a mock
 * answering like P2's contract), the teleport engine (moves at once, running the plan's commit step), the kit loans
 * (a tagged "kit item"), a server tick and scheduler driven by the test.
 */
final class DuelHarness {

    static final int ARENA_ID = 7;
    static final int BATTLEGROUND_ID = 70;
    static final int LOBBY_AREA_ID = 80;
    static final String BATTLEGROUND_REGION = "arena_7_battleground_700";
    static final String BATTLEGROUND_REGION_2 = "arena_7_battleground_701";
    static final String LOBBY_REGION = "arena_7_lobby_800";
    static final int KIT_A = 11;
    static final int KIT_B = 12;

    final DuelTestItems items = new DuelTestItems();
    // Location only keeps its World weakly: the mock stays in a field (CLAUDE.md test rule).
    final World world = mock(World.class);
    final Location home = new Location(world, 100, 64, 100);
    final Location lobby = new Location(world, 0, 64, 0);
    final Location exit = new Location(world, 0, 64, 50);
    final Location spawnA = new Location(world, 10, 64, 10);
    final Location spawnB = new Location(world, 20, 64, 20);
    final DuelMatchesApi api = mock(DuelMatchesApi.class);
    final DuelTestItems.TestPlayer alice = items.player("Alice");
    final DuelTestItems.TestPlayer bob = items.player("Bob");
    final Map<UUID, DuelTestItems.TestPlayer> players = new HashMap<>();
    final Set<ItemStack> loans = new HashSet<>();
    final List<String> vaultTeleports = new ArrayList<>();
    final List<Player> restored = new ArrayList<>();
    final List<String> lobbyMoves = new ArrayList<>();
    final List<String> systemMoves = new ArrayList<>();
    final MinigameRegistry registry = new MinigameRegistry();
    long tick = 1_000;
    private final Deque<Runnable> nextTick = new ArrayDeque<>();
    /** Per player: the lobby teleport's outcome (TELEPORTED when absent). */
    final Map<UUID, TeleportOutcome> lobbyOutcome = new HashMap<>();
    /** The lobby teleport of these players stays in its warmup until {@link #finishLobby}. */
    final Map<UUID, CompletableFuture<TeleportOutcome>> heldLobby = new HashMap<>();
    final Map<UUID, TeleportPlan.BeforeMove> heldBeforeMove = new HashMap<>();
    /** These players' lobby teleport fails after its commit step ran (blocked by another plugin). */
    final Set<UUID> failAfterCommit = new HashSet<>();
    Settlement completeSettlement = Settlement.PAID;
    boolean apiDown;
    int nextMatchId = 41;

    final DuelEscrowSpool spool;
    final DuelPendingResults results;
    final DuelRecovery recovery;
    final MinigamePlayerVault vault;
    final ArenaAreaAccess access = new ArenaAreaAccess();
    final DuelService service;
    KnkArena arena;

    final DuelItems duelItems = new DuelItems() {
        @Override
        public Optional<String> stakeRefusal(ItemStack item) {
            return loans.contains(item) ? Optional.of("loaned") : Optional.empty();
        }

        @Override
        public boolean isLoan(ItemStack item) {
            return loans.contains(item);
        }

        @Override
        public void markLoan(ItemStack item, UUID token) {
            loans.add(item);
        }

        @Override
        public ItemSummary summary(ItemStack item, int ownerUserId) {
            return new ItemSummary(ownerUserId, "DIAMOND_SWORD", 1, item.toString(), null, null);
        }
    };

    final DuelKitLoans kits = new DuelKitLoans() {
        @Override
        public CompletableFuture<Boolean> grant(Player player, UUID token, int kitId) {
            ItemStack kitItem = items.item("kit-" + kitId + "-" + player.getName());
            duelItems.markLoan(kitItem, token);
            players.get(player.getUniqueId()).give(kitItem);
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public int removeLoans(Player player) {
            return DuelKitLoans.removeTagged(player, duelItems);
        }
    };

    DuelHarness(Path dataFolder) {
        when(world.getName()).thenReturn("arena");
        players.put(alice.id(), alice);
        players.put(bob.id(), bob);
        alice.location = home;
        bob.location = home;
        arena = arena(true, true, true, true);
        Logger logger = Logger.getLogger("test");
        spool = new DuelEscrowSpool(dataFolder.resolve("duel-escrow").toFile(), logger, items.codec());
        results = new DuelPendingResults(api, dataFolder.resolve("duel-escrow/pending-results").toFile(), logger);
        Executor main = Runnable::run;
        recovery = new DuelRecovery(api, spool, results, main, logger);
        DuelService[] self = new DuelService[1];
        vault = new MinigamePlayerVault("duel", dataFolder.resolve("duel-vault").toFile(), (player, to) -> {
            if (self[0] == null || !self[0].restoresInPlace(player)) vaultTeleports.add(player.getName());
        }, logger, items.codec(), name -> world, () -> home, player -> 20.0);
        DuelScheduler scheduler = new DuelScheduler() {
            @Override
            public long currentTick() {
                return tick;
            }

            @Override
            public void nextTick(Runnable task) {
                nextTick.add(task);
            }

            @Override
            public void later(long ticks, Runnable task) {
                nextTick.add(task);
            }

            @Override
            public Executor mainThread() {
                return main;
            }
        };
        DuelTeleporter teleporter = new DuelTeleporter() {
            @Override
            public CompletableFuture<TeleportOutcome> lobby(Player player, Location to, TeleportPlan.BeforeMove beforeMove) {
                lobbyMoves.add(player.getName());
                if (heldLobby.containsKey(player.getUniqueId())) {
                    heldBeforeMove.put(player.getUniqueId(), beforeMove);
                    return heldLobby.get(player.getUniqueId());
                }
                return CompletableFuture.completedFuture(move(player, to, beforeMove));
            }

            @Override
            public CompletableFuture<TeleportOutcome> system(Player player, Location to, String label) {
                systemMoves.add(player.getName() + "->" + label);
                players.get(player.getUniqueId()).location = to;
                return CompletableFuture.completedFuture(TeleportOutcome.teleported());
            }

            @Override
            public CompletableFuture<Optional<TeleportDenial>> checkLobby(Player player, Location to) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public void cancelWarmup(UUID player) {
                CompletableFuture<TeleportOutcome> held = heldLobby.remove(player);
                if (held != null) held.complete(TeleportOutcome.cancelled(net.knightsandkings.knk.core.teleport.WarmupCancelReason.REPLACED));
            }

            @Override
            public boolean isCombatTagged(UUID player) {
                return false;
            }

            @Override
            public void clearCombatTag(UUID player) {
            }
        };
        Map<KnkLocation, Location> locations = new HashMap<>();
        locations.put(arena.lobbyLocation(), lobby);
        locations.put(arena.exitLocation(), exit);
        locations.put(arena.battlegrounds().getFirst().spawnA(), spawnA);
        locations.put(arena.battlegrounds().getFirst().spawnB(), spawnB);
        DuelService.Ports ports = new DuelService.Ports(api, teleporter, kits, duelItems, vault, spool, results, recovery,
            access, scheduler, id -> players.containsKey(id) ? players.get(id).player() : null,
            id -> id.equals(alice.id()) ? 1 : id.equals(bob.id()) ? 2 : null,
            id -> id == ARENA_ID ? Optional.of(arena) : Optional.empty(),
            location -> Optional.ofNullable(locations.get(location)),
            id -> false, id -> false, restored::add, logger);
        service = new DuelService(ports, DuelConfig.defaults());
        access.setArenas(() -> arena == null ? List.of() : List.of(arena));
        self[0] = service;
        registry.register(new DuelParticipation(service));
        stubApi();
        service.start().join();
    }

    private TeleportOutcome move(Player player, Location to, TeleportPlan.BeforeMove beforeMove) {
        TeleportOutcome outcome = lobbyOutcome.getOrDefault(player.getUniqueId(), TeleportOutcome.teleported());
        if (!outcome.isTeleported()) return outcome;
        if (beforeMove != null && !beforeMove.prepare(player)) return TeleportOutcome.failed("prepare");
        if (failAfterCommit.contains(player.getUniqueId())) return TeleportOutcome.failed("The teleport was blocked.");
        players.get(player.getUniqueId()).location = to;
        return outcome;
    }

    /** A held lobby teleport ends: TELEPORTED runs the commit step and moves; anything else fails. */
    void finishLobby(DuelTestItems.TestPlayer who, TeleportOutcome outcome) {
        CompletableFuture<TeleportOutcome> held = heldLobby.remove(who.id());
        TeleportPlan.BeforeMove before = heldBeforeMove.remove(who.id());
        if (outcome.isTeleported()) {
            lobbyOutcome.remove(who.id());
            held.complete(move(who.player(), lobby, before));
        } else {
            held.complete(outcome);
        }
        runNextTicks();
    }

    private void stubApi() {
        when(api.abortUnfinished(any())).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(api.findByToken(any())).thenAnswer(inv -> CompletableFuture.completedFuture(Optional.empty()));
        when(api.lock(any())).thenAnswer(inv -> {
            if (apiDown) return CompletableFuture.failedFuture(new net.knightsandkings.knk.core.exception.ApiException("down"));
            KnkDuelRecords.LockRequest request = inv.getArgument(0);
            return CompletableFuture.completedFuture(record(request.token(), nextMatchId++, Status.CREATED, null));
        });
        when(api.startFight(anyInt(), any(), any())).thenAnswer(inv ->
            CompletableFuture.completedFuture(record(inv.getArgument(1), inv.getArgument(0), Status.IN_PROGRESS, null)));
        when(api.complete(anyInt(), any(), any())).thenAnswer(inv -> {
            if (apiDown) return CompletableFuture.failedFuture(new net.knightsandkings.knk.core.exception.ApiException("down"));
            KnkDuelRecords.Completion completion = inv.getArgument(2);
            return CompletableFuture.completedFuture(new Result(record(inv.getArgument(1), inv.getArgument(0),
                Status.COMPLETED, completion.winnerUserId()), completeSettlement, false, null));
        });
        when(api.cancel(anyInt(), any(), any(), any())).thenAnswer(inv -> {
            if (apiDown) return CompletableFuture.failedFuture(new net.knightsandkings.knk.core.exception.ApiException("down"));
            return CompletableFuture.completedFuture(new Result(record(inv.getArgument(1), inv.getArgument(0),
                Status.CANCELLED, null), Settlement.REFUNDED, false, null));
        });
    }

    static MatchRecord record(UUID token, int id, Status status, Integer winner) {
        return new MatchRecord(id, token, ARENA_ID, null, 1, 2, GearMode.OWN_GEAR_KEEP, null, null, null, status, null,
            winner, 0, 0, 5, 0, true, Review.NONE, null, List.of(), UndeliveredItems.NONE, List.of(), List.of(), null, null);
    }

    static KnkLocation loc(String name, double x) {
        return new KnkLocation(null, name, x, 64.0, x, 0f, 0f, "arena");
    }

    static KnkArena arena(boolean keep, boolean drop, boolean fixed, boolean choice) {
        // Rev. 6: the battleground (two parts) and the lobby are arena areas with their regions.
        KnkArenaArea bg = new KnkArenaArea(BATTLEGROUND_ID, ARENA_ID, KnkArenaArea.Kind.BATTLEGROUND, "bg1", "Battleground 1",
            1, true, loc("spawnA", 10), loc("spawnB", 20), null, null, null,
            List.of(new KnkArenaAreaRegion(700, BATTLEGROUND_REGION, "arena"), new KnkArenaAreaRegion(701, BATTLEGROUND_REGION_2, "arena")));
        KnkArenaArea lobby = new KnkArenaArea(LOBBY_AREA_ID, ARENA_ID, KnkArenaArea.Kind.LOBBY, "lobby", "Lobby", 0, true,
            null, null, loc("lobby", 0), null, null, List.of(new KnkArenaAreaRegion(800, LOBBY_REGION, "arena")));
        return new KnkArena(ARENA_ID, "arena_2", "Arena", "arena_2", "arena", 1, loc("domain", 1),
            loc("exit", 50), true, 3, 3, 60, true, true, 10_000, 9, 0, 5, null, null, 10, 0, 100_000,
            keep, drop, fixed, choice, KnkArena.DuelGearMode.OWN_GEAR_KEEP,
            1, 1, 0, 60, 1, true, true,
            List.of(new KnkArenaKit(KIT_A, "Knight", 1), new KnkArenaKit(KIT_B, "Archer", 2)), List.of(bg, lobby), List.of());
    }

    DuelArenaSettings settings() {
        return DuelArenas.settingsOf(arena).orElseThrow();
    }

    /** Alice invites Bob with {@code contract}; Bob accepts. Runs the lock-in and the lobby teleports. */
    DuelService.LockOutcome duel(StakeContract contract) {
        boolean bobStakesItems = !contract.opponent().items().isEmpty();
        StakeContract first = bobStakesItems
            ? new StakeContract(contract.gearMode(), contract.fixedKitId(), contract.challenger(),
                new Stake(contract.opponent().coins(), List.of()))
            : contract;
        DuelOfferBook.Outcome offer = service.offers().invite(alice.id(), bob.id(), settings(), first, tick);
        if (!offer.ok()) throw new AssertionError("invite refused: " + offer.status() + " " + offer.violations());
        Player accepter = bob.player();
        if (bobStakesItems) {
            // Each side adds only its own items (P3): Bob counters with his.
            offer = service.offers().counter(bob.id(), offer.offer().offerId(), contract, tick);
            if (!offer.ok()) throw new AssertionError("counter refused: " + offer.status() + " " + offer.violations());
            accepter = alice.player();
        }
        DuelService.LockOutcome outcome = service.accept(accepter, offer.offer().offerId()).join();
        runNextTicks();
        return outcome;
    }

    StakeContract contract(GearMode mode, Integer kit, Stake challenger, Stake opponent) {
        return new StakeContract(mode, kit, challenger, opponent);
    }

    StakeItem stake(DuelTestItems.TestPlayer owner, ItemStack stack) {
        int slot = -1;
        for (int i = 0; i < owner.contents.length; i++) if (owner.contents[i] == stack) slot = i;
        return service.stakeItems().register(owner.id(), stack, slot, 0);
    }

    void seconds(int seconds) {
        for (int i = 0; i < seconds; i++) {
            tick += DuelTicks.PER_SECOND;
            service.tick();
            runNextTicks();
        }
    }

    void runNextTicks() {
        while (!nextTick.isEmpty()) {
            tick++;
            List<Runnable> now = new ArrayList<>(nextTick);
            nextTick.clear();
            now.forEach(Runnable::run);
        }
    }

    /** Warmup over, battleground allocated, countdown over: FIGHT. */
    void toFight() {
        seconds(1); // warmup → battleground → countdown
        seconds(1); // countdown → FIGHT
    }

    DuelTestItems.TestPlayer side(DuelSide side) {
        return side == DuelSide.CHALLENGER ? alice : bob;
    }

    /** {@code who} dies with what is in their inventory as the drops. */
    DuelService.DeathHandling kill(DuelTestItems.TestPlayer who) {
        who.dead = true;
        DuelService.DeathHandling handling = service.onDeath(who.player(), who.received());
        if (!handling.keepInventory()) who.contents = new ItemStack[41];
        runNextTicks();
        return handling;
    }

    void respawn(DuelTestItems.TestPlayer who) {
        who.dead = false;
        service.respawnLocation(who.player()).ifPresent(l -> who.location = l);
        service.afterRespawn(who.player());
        runNextTicks();
    }
}
