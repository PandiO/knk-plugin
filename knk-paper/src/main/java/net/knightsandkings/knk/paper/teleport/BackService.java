package net.knightsandkings.knk.paper.teleport;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.teleport.BackKind;
import net.knightsandkings.knk.core.teleport.BackLocationBook;
import net.knightsandkings.knk.core.teleport.TeleportBackSettings;
import net.knightsandkings.knk.core.teleport.TeleportDenial;
import net.knightsandkings.knk.core.teleport.TeleportOutcome;

/**
 * {@code /back} (docs/specs/teleport/IMPLEMENTATION_PLAN.md Phase 7; Linear KNG-42): back to the
 * latest place the player may return to, among the {@link BackKind}s their nodes allow - where they
 * died ({@value TeleportNodes#BACK}), or where they stood before a warp ({@value TeleportNodes#BACK_WARPS}),
 * a teleport ({@value TeleportNodes#BACK_TELEPORT}) or {@code /spawn} ({@value TeleportNodes#BACK_SPAWN});
 * {@value TeleportNodes#BACK_ALL} (or {@code knk.teleport.back.*}) allows every kind.
 * <p>
 * Recording: deaths come from {@code listeners/BackDeathListener}; teleport origins from the engine
 * ({@link TeleportArrivalListener}) - {@code /warp} and menu warps as WARPS, {@code /spawn} as SPAWN,
 * {@code /tpa}/{@code /tpahere} (whoever moves) and a staff member's own {@code /tp} as TELEPORT.
 * Being moved by staff and a {@code /back} itself record nothing (developer decisions 2026-10-05).
 * Siege teleports never pass through the engine and siege deaths are excluded ({@link BackDeathExclusion}),
 * so neither is recorded - and neither wipes an older entry, so a place from before a siege can still
 * be used after it while it lasts. Everything is recorded whatever the player's nodes (it costs no API
 * call); the nodes are checked when {@code /back} is used.
 * <p>
 * {@link #start}: a player's own {@code /back} - claims the latest usable entry and runs a
 * {@link TeleportPlan#back} through the {@link TeleportService} (warmup, cooldown, combat tag,
 * freeze/region/siege guards, safe spot), paying {@code teleport.back.price-coins} after the warmup
 * when set (holders of {@value TeleportNodes#BYPASS_COST} pay nothing). {@link #startFor}: staff
 * {@code /back <player>} - the player's latest entry of any kind, as an instant, audited staff teleport
 * that still looks for a safe spot. Arriving uses the entry up; any other ending gives it back, so it
 * may be tried again while it lasts. In memory only; a relog keeps the entries, a restart forgets them.
 * Main thread only.
 */
public class BackService {

    private static final Logger LOGGER = Logger.getLogger(BackService.class.getName());

    /** Nothing to go back to (none recorded, expired, used, or not among the allowed kinds). */
    public static final String NOWHERE = "nowhere";
    public static final String IN_PROGRESS = "in-progress";
    public static final String DISABLED = "disabled";
    /** {@code /back} costs coins but there is no API client to charge them. */
    public static final String FEE_UNAVAILABLE = "fee-unavailable";

    /** The nodes that let a player use {@code /back} at all - {@link #access} sorts out which kinds. */
    public static final List<String> PLAYER_NODES = List.of(TeleportNodes.BACK_ALL, TeleportNodes.BACK,
        TeleportNodes.BACK_WARPS, TeleportNodes.BACK_TELEPORT, TeleportNodes.BACK_SPAWN);

    /** Where a player was - the world by id, not a Bukkit Location (which would pin or lose an unloaded world). */
    public record Spot(UUID worldId, String worldName, double x, double y, double z, float yaw, float pitch) {

        public static Spot of(Location location) {
            World world = location.getWorld();
            return new Spot(world.getUID(), world.getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
        }

        public String label(BackKind kind) {
            return "back (" + kind.configKey() + ") " + worldName + " " + (int) Math.floor(x) + ","
                + (int) Math.floor(y) + "," + (int) Math.floor(z);
        }
    }

    /**
     * What a player's own {@code /back} may use.
     *
     * @param kinds the kinds their nodes allow; empty = no {@code /back} at all
     * @param free  they hold {@value TeleportNodes#BYPASS_COST}: no fee
     */
    public record Access(Set<BackKind> kinds, boolean free) {
        public Access {
            kinds = kinds == null || kinds.isEmpty() ? Set.of() : Set.copyOf(kinds);
        }

        public boolean allowsAny() {
            return !kinds.isEmpty();
        }
    }

    /**
     * How a {@code /back} ended.
     *
     * @param outcome the engine's outcome
     * @param kind    the kind of place it went (or tried to go) to; null when nothing was claimed
     */
    public record Trip(TeleportOutcome outcome, BackKind kind) {
        static Trip refused(String code, String message) {
            return new Trip(TeleportOutcome.denied(TeleportDenial.of(code, message)), null);
        }

        public boolean isTeleported() {
            return outcome.isTeleported();
        }

        public String code() {
            return outcome.code();
        }
    }

    private final TeleportService engine;
    private final Executor mainThread;
    private final TeleportService.PermissionLookup permissions;
    private final Function<UUID, World> worlds;
    private final BackLocationBook<Spot> book = new BackLocationBook<>();
    private final List<BackDeathExclusion> exclusions = new CopyOnWriteArrayList<>();
    /** Charges {@code teleport.back.price-coins}; null = no API client (a paid /back is then refused). */
    private volatile TeleportCharges charges;

    /**
     * Also registers itself with {@code engine} to record teleport origins.
     *
     * @param worlds a loaded world by id, e.g. {@code Bukkit::getWorld}; null when it's gone
     */
    public BackService(TeleportService engine, Executor mainThread, TeleportService.PermissionLookup permissions,
                       Function<UUID, World> worlds) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread must not be null");
        this.permissions = Objects.requireNonNull(permissions, "permissions must not be null");
        this.worlds = Objects.requireNonNull(worlds, "worlds must not be null");
        engine.addArrivalListener(this::recordOrigin);
    }

    /** How the {@code teleport.back.price-coins} fee is charged; null refuses a paid {@code /back}. */
    public void setCharges(TeleportCharges charges) {
        this.charges = charges;
    }

    /** Add a rule that keeps a death from giving a {@code /back} (the siege's match deaths). */
    public void registerDeathExclusion(BackDeathExclusion exclusion) {
        exclusions.add(Objects.requireNonNull(exclusion, "exclusion must not be null"));
    }

    public TeleportBackSettings settings() {
        return engine.settings().back();
    }

    public boolean isEnabled() {
        return settings().enabled();
    }

    // ----- recording -----

    /**
     * Whether {@code player}'s death, happening now, must not give a {@code /back}. Asked by the death
     * listener before anything else handles the death; a failing exclusion counts as excluded.
     */
    public boolean isExcluded(Player player) {
        for (BackDeathExclusion exclusion : exclusions) {
            try {
                if (exclusion.excludes(player)) {
                    return true;
                }
            } catch (RuntimeException ex) {
                LOGGER.log(Level.SEVERE, "/back death exclusion " + exclusion.getClass().getName() + " failed", ex);
                return true;
            }
        }
        return false;
    }

    /**
     * {@code player} died at {@code location}. An excluded death (siege) records nothing and leaves the
     * older entries alone. Holders of {@value TeleportNodes#BACK} are told how long {@code /back} works.
     */
    public void recordDeath(Player player, Location location, boolean excluded) {
        if (excluded || !isEnabled() || location == null || location.getWorld() == null) {
            return;
        }
        UUID id = player.getUniqueId();
        int seconds = settings().expireSeconds(BackKind.DEATH);
        BackLocationBook.Entry<Spot> entry = book.record(id, BackKind.DEATH, Spot.of(location), engine.now(), seconds);
        CompletableFuture<Boolean> holds = has(player, TeleportNodes.BACK)
            .thenCombine(has(player, TeleportNodes.BACK_ALL), (death, all) -> death || all);
        holds.thenAccept(allowed -> mainThread.execute(() -> {
            if (Boolean.TRUE.equals(allowed) && player.isOnline()
                    && book.available(id, Set.of(BackKind.DEATH), engine.now()).map(current -> current.id() == entry.id()).orElse(false)) {
                player.sendMessage(ChatColor.GRAY + "Use " + ChatColor.YELLOW + "/back" + ChatColor.GRAY + " within "
                    + describeSeconds(seconds) + " to return to where you died.");
            }
        }));
    }

    /**
     * The engine moved someone ({@link TeleportArrivalListener}): remember where they stood, for the
     * kind this teleport counts as. Staff moving someone else and {@code /back}s record nothing.
     */
    void recordOrigin(TeleportPlan plan, Location from, Location to) {
        if (!isEnabled() || plan.backTrip() || from == null || from.getWorld() == null) {
            return;
        }
        BackKind kind = kindOf(plan);
        if (kind == null) {
            return;
        }
        book.record(plan.subject().getUniqueId(), kind, Spot.of(from), engine.now(), settings().expireSeconds(kind));
    }

    /** The kind of place a teleport leaves behind, or null when it isn't one {@code /back} returns to. */
    static BackKind kindOf(TeleportPlan plan) {
        return switch (plan.kind()) {
            case WARP -> BackKind.WARPS;
            case SPAWN -> BackKind.SPAWN;
            case REQUEST -> BackKind.TELEPORT;
            case STAFF -> plan.movesActor() ? BackKind.TELEPORT : null;
            case BACK -> null;
        };
    }

    // ----- using -----

    /** Which kinds {@code player}'s own {@code /back} may use, and whether it's free. Completes on any thread. */
    public CompletableFuture<Access> access(Player player) {
        CompletableFuture<Boolean> all = has(player, TeleportNodes.BACK_ALL);
        Map<BackKind, CompletableFuture<Boolean>> perKind = new java.util.EnumMap<>(BackKind.class);
        for (BackKind kind : BackKind.values()) {
            perKind.put(kind, has(player, TeleportNodes.backNode(kind)));
        }
        CompletableFuture<Boolean> free = settings().isPaid()
            ? has(player, TeleportNodes.BYPASS_COST) : CompletableFuture.completedFuture(true);
        CompletableFuture<?>[] waits = perKind.values().toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(CompletableFuture.allOf(waits), all, free).thenApply(ignored -> {
            Set<BackKind> kinds = EnumSet.noneOf(BackKind.class);
            perKind.forEach((kind, check) -> {
                if (all.join() || check.join()) {
                    kinds.add(kind);
                }
            });
            return new Access(kinds, free.join());
        });
    }

    /**
     * Take {@code player} back to the latest place {@code access} allows, through the engine. Completes
     * on the main thread; without a usable entry it is {@code DENIED} with code {@link #NOWHERE},
     * {@link #IN_PROGRESS}, {@link #DISABLED} or {@link #FEE_UNAVAILABLE}. The caller resolves
     * {@code access} ({@link #access}) and refuses when it allows nothing.
     */
    public CompletableFuture<Trip> start(Player player, Access access) {
        if (!isEnabled()) {
            return CompletableFuture.completedFuture(Trip.refused(DISABLED, "/back is turned off on this server."));
        }
        TeleportBackSettings settings = settings();
        boolean paid = settings.isPaid() && !access.free();
        TeleportCharges fees = charges;
        if (paid && fees == null) {
            return CompletableFuture.completedFuture(Trip.refused(FEE_UNAVAILABLE, "/back isn't available right now."));
        }
        return claimAndGo(player, access.kinds(), (entry, destination) -> {
            TeleportPlan plan = TeleportPlan.back(player, destination, entry.location().label(entry.kind()));
            if (paid) {
                // The engine tells the player the price during the warmup (KNG-41).
                plan = plan.withCharge(fees.backFee(player, settings.priceCoins(), entry.kind()));
            }
            return plan;
        }, nowhereMessage(access.kinds()), "You're already on your way back.");
    }

    /**
     * Staff {@code /back <player>}: send {@code target} to their latest entry of any kind, whatever
     * nodes they hold, as an instant, audited staff teleport (safe spot still searched). Uses the entry
     * up. The caller checks {@value TeleportNodes#STAFF_BACK_OTHERS} and the rank. Completes on the main
     * thread.
     */
    public CompletableFuture<Trip> startFor(CommandSender actor, Player target, boolean silent) {
        if (!isEnabled()) {
            return CompletableFuture.completedFuture(Trip.refused(DISABLED, "/back is turned off on this server."));
        }
        return claimAndGo(target, EnumSet.allOf(BackKind.class), (entry, destination) ->
                TeleportPlan.staffBack(actor, target, destination, entry.location().label(entry.kind()), silent),
            target.getName() + " has nowhere to go back to.", target.getName() + " is already on the way back.");
    }

    @FunctionalInterface
    private interface PlanFactory {
        TeleportPlan plan(BackLocationBook.Entry<Spot> entry, Supplier<Location> destination);
    }

    private CompletableFuture<Trip> claimAndGo(Player subject, Set<BackKind> kinds, PlanFactory plans, String nowhere,
                                             String busy) {
        UUID id = subject.getUniqueId();
        Optional<BackLocationBook.Entry<Spot>> claimed = book.claim(id, kinds, engine.now());
        if (claimed.isEmpty()) {
            if (book.isClaimed(id)) {
                return CompletableFuture.completedFuture(Trip.refused(IN_PROGRESS, busy));
            }
            return CompletableFuture.completedFuture(Trip.refused(NOWHERE, nowhere));
        }
        BackLocationBook.Entry<Spot> entry = claimed.get();
        CompletableFuture<TeleportOutcome> outcome;
        try {
            outcome = engine.start(plans.plan(entry, () -> toLocation(entry.location())));
        } catch (RuntimeException ex) {
            book.release(entry);
            throw ex;
        }
        return outcome.whenComplete((result, ex) -> {
            if (ex == null && result != null && result.isTeleported()) {
                book.consume(entry);
            } else {
                book.release(entry);
            }
        }).thenApply(result -> new Trip(result, entry.kind()));
    }

    /** Seconds left to start a {@code /back} to the latest place of {@code kinds}; 0 when there's none. */
    public int secondsLeft(UUID player, Set<BackKind> kinds) {
        long now = engine.now();
        return book.available(player, kinds, now).map(entry -> entry.secondsLeft(now)).orElse(0);
    }

    /** {@link #secondsLeft(UUID, Set)} over every kind. */
    public int secondsLeft(UUID player) {
        return secondsLeft(player, EnumSet.allOf(BackKind.class));
    }

    /** Drop expired entries (from the engine's heartbeat). */
    public void purgeExpired() {
        book.purgeExpired(engine.now());
    }

    private CompletableFuture<Boolean> has(Player player, String node) {
        try {
            return permissions.has(player, node).exceptionally(ex -> false);
        } catch (RuntimeException ex) {
            return CompletableFuture.completedFuture(false);
        }
    }

    private Location toLocation(Spot spot) {
        World world = worlds.apply(spot.worldId());
        if (world == null) {
            return null;
        }
        return new Location(world, spot.x(), spot.y(), spot.z(), spot.yaw(), spot.pitch());
    }

    /** "You have nowhere to go back to. /back works for 5 minutes after you die or warp." */
    private String nowhereMessage(Set<BackKind> kinds) {
        TeleportBackSettings settings = settings();
        List<BackKind> ordered = EnumSet.copyOf(kinds.isEmpty() ? EnumSet.allOf(BackKind.class) : kinds).stream().toList();
        String after = joinOr(ordered.stream().map(BackService::verb).toList());
        Set<Integer> windows = ordered.stream().map(settings::expireSeconds).collect(Collectors.toSet());
        String window = windows.size() == 1 ? describeSeconds(windows.iterator().next()) : "a few minutes";
        return "You have nowhere to go back to. /back works for " + window + " after you " + after + ".";
    }

    private static String verb(BackKind kind) {
        return switch (kind) {
            case DEATH -> "die";
            case WARPS -> "warp";
            case TELEPORT -> "teleport";
            case SPAWN -> "use /spawn";
        };
    }

    private static String joinOr(List<String> parts) {
        if (parts.size() <= 1) {
            return parts.isEmpty() ? "" : parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " or " + parts.get(parts.size() - 1);
    }

    /** "5 minutes", "90 seconds", "1 minute". */
    static String describeSeconds(int seconds) {
        if (seconds % 60 == 0) {
            int minutes = seconds / 60;
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        return seconds + (seconds == 1 ? " second" : " seconds");
    }
}
