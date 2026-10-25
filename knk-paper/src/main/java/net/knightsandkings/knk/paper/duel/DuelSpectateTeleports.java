package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaArea;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.teleport.TeleportChargeResult;
import net.knightsandkings.knk.core.teleport.TeleportCharger;
import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.paper.teleport.TeleportPlan;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The spectate teleport (arena duels DESIGN §9.3, rev. 6, developer 2026-10-24): [Spectate] in the arena menu, the
 * warmup announcement and the match detail, and {@code /duels spectate}, start an engine teleport of kind
 * {@code ARENA} (player warmup, no cooldown) to the arena's spectator area's arrival location. Before it starts, the
 * area's own conditions are checked and paid through the teleport fee path ({@code POST teleport-destinations/
 * spectate-fee}): its minimum title and its gem price; nothing is asked of the API for an area without either. A paid
 * teleport that doesn't happen is refunded. A spectator area in {@code /warp} is a future improvement (KNG-240).
 */
public final class DuelSpectateTeleports {

    static final String IN_DUEL = "You can't spectate during your own duel.";
    static final String NO_ACCOUNT = "Your profile is still loading; try again in a moment.";

    /** The teleport engine's start (TeleportService::start). */
    @FunctionalInterface
    public interface Engine {
        CompletableFuture<TeleportOutcome> start(TeleportPlan plan);
    }

    /** The player's warp bypass nodes ({@code knk.teleport.bypass.requirements} / {@code .cost}). */
    public record Bypass(boolean requirements, boolean cost) {
        public static final Bypass NONE = new Bypass(false, false);
    }

    private final Engine engine;
    private final TeleportCharger charger;
    private final Function<UUID, Integer> userIds;
    private final Function<KnkLocation, Optional<Location>> locations;
    private final Predicate<UUID> inDuel;
    private final Function<Player, CompletableFuture<Bypass>> bypass;
    private final Executor mainThread;

    /**
     * @param charger null: only areas without conditions can be reached (the API isn't wired)
     * @param inDuel  a participant of a running match (their own engine guard lets {@code ARENA} through)
     */
    public DuelSpectateTeleports(Engine engine, TeleportCharger charger, Function<UUID, Integer> userIds,
                                 Function<KnkLocation, Optional<Location>> locations, Predicate<UUID> inDuel,
                                 Function<Player, CompletableFuture<Bypass>> bypass, Executor mainThread) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.charger = charger;
        this.userIds = Objects.requireNonNull(userIds, "userIds");
        this.locations = Objects.requireNonNull(locations, "locations");
        this.inDuel = inDuel != null ? inDuel : id -> false;
        this.bypass = bypass != null ? bypass : p -> CompletableFuture.completedFuture(Bypass.NONE);
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
    }

    /** Whether the area asks the API anything (a minimum title or a gem price). */
    public static boolean hasConditions(KnkArenaArea area) {
        return area.teleportMinTitleBracketId() != null
                || (area.teleportPriceGems() != null && area.teleportPriceGems() > 0);
    }

    /** Spectate {@code arena} from {@code area} (its spectate target). Main thread; completes with the outcome or null. */
    public CompletableFuture<TeleportOutcome> spectate(Player player, KnkArena arena, KnkArenaArea area) {
        if (inDuel.test(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + IN_DUEL);
            return CompletableFuture.completedFuture(null);
        }
        Location arrival = area.arrival() == null ? null : locations.apply(area.arrival()).orElse(null);
        if (arrival == null) {
            player.sendMessage(ChatColor.RED + area.displayName() + " can't be reached by teleport right now.");
            return CompletableFuture.completedFuture(null);
        }
        String label = area.displayName() + " (" + arena.name() + ")";
        if (!hasConditions(area)) {
            return start(player, arrival, label, null, null);
        }
        Integer userId = userIds.apply(player.getUniqueId());
        if (userId == null || charger == null) {
            player.sendMessage(ChatColor.RED + (userId == null ? NO_ACCOUNT : "Teleports aren't available right now."));
            return CompletableFuture.completedFuture(null);
        }
        String key = TeleportCharger.newKey("spectate");
        CompletableFuture<TeleportOutcome> done = new CompletableFuture<>();
        bypass.apply(player).exceptionally(ex -> Bypass.NONE)
                .thenCompose(b -> charger.chargeSpectateFee(area.id(), userId, key, b.requirements(), b.cost()))
                .whenComplete((charge, error) -> mainThread.execute(() -> {
                    if (error != null || charge == null || !charge.allowed()) {
                        player.sendMessage(ChatColor.RED + refusal(charge));
                        done.complete(null);
                        return;
                    }
                    if (charge.charged() > 0) {
                        player.sendMessage(ChatColor.GRAY + "Paid " + charge.charged() + " " + currency(charge)
                                + " to watch; refunded if you don't arrive.");
                    }
                    start(player, arrival, label, userId, charge.charged() > 0 ? key : null)
                            .whenComplete((outcome, e) -> done.complete(outcome));
                }));
        return done;
    }

    private CompletableFuture<TeleportOutcome> start(Player player, Location arrival, String label, Integer userId,
                                                     String paidKey) {
        Location target = arrival.clone();
        TeleportPlan plan = TeleportPlan.arena(player, target::clone, label, null);
        CompletableFuture<TeleportOutcome> started;
        try {
            started = engine.start(plan);
        } catch (RuntimeException e) {
            started = CompletableFuture.failedFuture(e);
        }
        return started.handle((outcome, error) -> {
            mainThread.execute(() -> report(player, label, outcome, error));
            boolean arrived = error == null && outcome != null && outcome.isTeleported();
            if (!arrived && paidKey != null && charger != null && userId != null) {
                charger.refund(userId, paidKey, "the spectate teleport didn't happen");
            }
            return outcome;
        });
    }

    private static void report(Player player, String label, TeleportOutcome outcome, Throwable error) {
        if (!player.isOnline()) return;
        if (error != null || outcome == null) {
            player.sendMessage(ChatColor.RED + "The teleport didn't happen.");
            return;
        }
        switch (outcome.status()) {
            case TELEPORTED -> player.sendMessage(ChatColor.GREEN + "You are watching the duels from " + label + ".");
            case CANCELLED -> { }
            case DENIED, FAILED -> player.sendMessage(ChatColor.RED
                    + (outcome.message() != null ? outcome.message() : "The teleport didn't happen."));
        }
    }

    private static String refusal(TeleportChargeResult charge) {
        if (charge != null && charge.refusalMessage() != null && !charge.refusalMessage().isBlank()) {
            return charge.refusalMessage();
        }
        return "You can't teleport to the spectator area right now.";
    }

    private static String currency(TeleportChargeResult charge) {
        return charge.currency() == null ? "gems" : charge.currency().toLowerCase(java.util.Locale.ROOT);
    }
}
