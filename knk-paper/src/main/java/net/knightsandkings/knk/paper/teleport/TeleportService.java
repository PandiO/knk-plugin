package net.knightsandkings.knk.paper.teleport;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import net.knightsandkings.knk.core.teleport.BlockProbe;
import net.knightsandkings.knk.core.teleport.CombatTagBook;
import net.knightsandkings.knk.core.teleport.SafeLocationFinder;
import net.knightsandkings.knk.core.teleport.TeleportCooldowns;
import net.knightsandkings.knk.core.teleport.TeleportDenial;
import net.knightsandkings.knk.core.teleport.TeleportKind;
import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.core.teleport.TeleportSettings;
import net.knightsandkings.knk.core.teleport.WarmupBook;
import net.knightsandkings.knk.core.teleport.WarmupCancelReason;
import net.knightsandkings.knk.core.teleport.WarmupPolicy;
import net.knightsandkings.knk.paper.utils.ColorOptions;
import net.kyori.adventure.text.Component;

/**
 * The teleport engine (docs/specs/teleport/DESIGN.md §3.4): every teleport command and menu click
 * builds a {@link TeleportPlan} and calls {@link #start}. Steps:
 * <ol>
 *   <li><b>Guards</b> - every registered {@link TeleportRestriction} (freeze, region entry/exit,
 *       and the siege guards once the siege branch registers them), then for player teleports the
 *       combat tag and the cooldown.</li>
 *   <li><b>Warmup</b> (player teleports only): 5 s, 3 s with {@code knk.teleport.warmup.short},
 *       none with {@code knk.teleport.bypass.warmup}. Cancelled by a block move, damage, another
 *       teleport, death, quit or being frozen ({@code listeners/TeleportWarmupListener} and
 *       {@link #tick}). One warmup per player; a new one replaces the old one.</li>
 *   <li><b>Commit</b>: guards again (things change during a warmup), destination re-read, then - for
 *       a plan with a {@link TeleportCharge} (warps, paid requests) - the server authorizes and
 *       charges it (and it is refunded if the teleport then doesn't happen), safe spot
 *       (player teleports only - staff land exactly where they asked), then
 *       {@code teleportAsync(loc, TeleportCause.COMMAND)} - never the default {@code PLUGIN} cause,
 *       so the region and siege-lockdown listeners see these teleports like vanilla ones.</li>
 *   <li><b>After</b>: cooldown (player teleports), a log line (INFO for staff teleports), and for
 *       staff teleports an audit entry in the web API ({@link TeleportAuditor}, Phase 2).</li>
 * </ol>
 * The returned future completes on the main thread with the {@link TeleportOutcome}; the caller
 * reports it. Warmup notices and cancel reasons are sent to the moving player by the engine itself.
 * <p>
 * Threading: {@link #start}, {@link #tick}, {@link #cancelWarmup} run on the main thread; permission
 * lookups are async and hop back through {@code mainThread}.
 */
public class TeleportService {

    private static final Logger LOGGER = Logger.getLogger(TeleportService.class.getName());
    private static final long PURGE_INTERVAL_MILLIS = 60_000L;
    private static final String UNAVAILABLE = "unavailable";
    /** Guard nodes the engine itself reads for player teleports. */
    private static final Set<String> PLAYER_KIND_NODES = Set.of(
        TeleportNodes.WARMUP_SHORT, TeleportNodes.BYPASS_WARMUP, TeleportNodes.BYPASS_COOLDOWN, TeleportNodes.BYPASS_COMBAT);

    /** Async permission check, e.g. {@code KnkPermissible::hasPermissionAsync}. */
    @FunctionalInterface
    public interface PermissionLookup {
        CompletableFuture<Boolean> has(Player player, String node);
    }

    private final Executor mainThread;
    private final PermissionLookup permissions;
    private final LongSupplier clock;
    private final Function<World, BlockProbe> probes;
    private final CombatTagBook combatTags;
    private final TeleportCooldowns cooldowns = new TeleportCooldowns();
    private final WarmupBook<Warmup> warmups = new WarmupBook<>();
    private final List<TeleportRestriction> restrictions = new CopyOnWriteArrayList<>();
    /** Authority of teleports whose {@code teleportAsync} is in flight, for the region listener's bypass. */
    private final Map<UUID, Authority> inFlight = new ConcurrentHashMap<>();
    private volatile TeleportSettings settings;
    private volatile WarmupPolicy warmupPolicy;
    /** Null when the API client isn't available - staff teleports are then only logged locally. */
    private volatile TeleportAuditor auditor;
    private long lastPurgeMillis;

    public TeleportService(Executor mainThread, PermissionLookup permissions, TeleportSettings settings,
                           LongSupplier clock, Function<World, BlockProbe> probes) {
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread must not be null");
        this.permissions = Objects.requireNonNull(permissions, "permissions must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.probes = Objects.requireNonNull(probes, "probes must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.warmupPolicy = new WarmupPolicy(settings);
        this.combatTags = new CombatTagBook(settings.combatTagSeconds());
        this.lastPurgeMillis = clock.getAsLong();
    }

    /** Add a guard (see {@link TeleportRestriction}); later registrations are checked after earlier ones. */
    public void registerRestriction(TeleportRestriction restriction) {
        restrictions.add(Objects.requireNonNull(restriction, "restriction must not be null"));
    }

    /** Where staff teleports are audited (docs/specs/teleport/DESIGN.md §3.10); null turns auditing off. */
    public void setAuditor(TeleportAuditor auditor) {
        this.auditor = auditor;
    }

    public TeleportSettings settings() {
        return settings;
    }

    public void updateSettings(TeleportSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.warmupPolicy = new WarmupPolicy(settings);
        combatTags.setTagSeconds(settings.combatTagSeconds());
    }

    public CombatTagBook combatTags() {
        return combatTags;
    }

    public long now() {
        return clock.getAsLong();
    }

    public boolean isWarmingUp(UUID player) {
        return warmups.isWarmingUp(player);
    }

    /**
     * Whether the teleport currently in flight for {@code player} was authorized with {@code node} -
     * lets {@code WorldGuardRegionListener} honour a staff member's {@code knk.region.bypass} when
     * the moved player doesn't hold it themselves.
     */
    public boolean hasInFlightBypass(UUID player, String node) {
        Authority authority = inFlight.get(player);
        return authority != null && authority.has(node);
    }

    /** Check, warm up and carry out a teleport. Main thread only. */
    public CompletableFuture<TeleportOutcome> start(TeleportPlan plan) {
        Objects.requireNonNull(plan, "plan must not be null");
        CompletableFuture<TeleportOutcome> result = new CompletableFuture<>();
        resolveAuthority(plan).whenComplete((authority, ex) -> mainThread.execute(() -> {
            if (ex != null) {
                LOGGER.log(Level.WARNING, "Teleport permission lookup failed for " + plan.subject().getName(), ex);
            }
            Authority resolved = ex != null || authority == null ? Authority.NONE : authority;
            guarded(plan, result, () -> begin(plan, resolved, result));
        }));
        return result;
    }

    /**
     * Run the guards {@link #start} would run first, without starting anything - so a command can
     * refuse up front (a {@code /tpa} while in combat, frozen or on cooldown) instead of only after
     * the other player said yes. Main thread only; completes on the main thread, empty when allowed.
     */
    public CompletableFuture<Optional<TeleportDenial>> check(TeleportPlan plan) {
        Objects.requireNonNull(plan, "plan must not be null");
        CompletableFuture<Optional<TeleportDenial>> result = new CompletableFuture<>();
        resolveAuthority(plan).whenComplete((authority, ex) -> mainThread.execute(() -> {
            try {
                Authority resolved = ex != null || authority == null ? Authority.NONE : authority;
                Location to = plan.destination().get();
                if (!plan.subject().isOnline() || to == null || to.getWorld() == null) {
                    result.complete(Optional.of(TeleportDenial.of(UNAVAILABLE, "The destination is no longer available.")));
                    return;
                }
                result.complete(checkGuards(plan, resolved, to));
            } catch (RuntimeException failure) {
                LOGGER.log(Level.SEVERE, "Teleport check for " + plan.subject().getName() + " failed", failure);
                result.complete(Optional.of(TeleportDenial.of("error", "Teleporting isn't available right now.")));
            }
        }));
        return result;
    }

    /** Stop {@code player}'s warmup, telling them why (unless they quit). Main thread only. */
    public void cancelWarmup(UUID player, WarmupCancelReason reason) {
        warmups.cancel(player).ifPresent(pending -> finishCancelled(pending.payload(), reason));
    }

    /** Stop every warmup (plugin disable). */
    public void cancelAll(WarmupCancelReason reason) {
        for (WarmupBook.Pending<Warmup> pending : warmups.snapshot()) {
            cancelWarmup(pending.subject(), reason);
        }
    }

    /** Called when a player leaves: their warmup ends; cooldowns and combat tags outlive a relog. */
    public void forget(UUID player) {
        cancelWarmup(player, WarmupCancelReason.QUIT);
        inFlight.remove(player);
    }

    /**
     * The engine's heartbeat (every 5 ticks): countdown in the action bar, cancel warmups of players
     * who left or were frozen, commit the due ones. Main thread only.
     *
     * @param isFrozen whether a player is admin-frozen right now
     */
    public void tick(Predicate<UUID> isFrozen) {
        long now = clock.getAsLong();
        for (WarmupBook.Pending<Warmup> pending : warmups.snapshot()) {
            Player subject = pending.payload().plan.subject();
            if (!subject.isOnline()) {
                cancelWarmup(pending.subject(), WarmupCancelReason.QUIT);
            } else if (isFrozen.test(pending.subject())) {
                cancelWarmup(pending.subject(), WarmupCancelReason.FROZEN);
            } else {
                int left = pending.secondsLeft(now);
                if (left > 0 && left != pending.payload().lastShownSeconds) {
                    pending.payload().lastShownSeconds = left;
                    subject.sendActionBar(Component.text("Teleporting in " + left + "...").color(ColorOptions.message));
                }
            }
        }
        for (WarmupBook.Pending<Warmup> due : warmups.takeDue(now)) {
            Warmup warmup = due.payload();
            guarded(warmup.plan, warmup.result, () -> commit(warmup.plan, warmup.authority, warmup.result, true));
        }
        if (now - lastPurgeMillis >= PURGE_INTERVAL_MILLIS) {
            lastPurgeMillis = now;
            cooldowns.purgeExpired(now);
            combatTags.purgeExpired(now);
        }
    }

    // ----- steps -----

    private void begin(TeleportPlan plan, Authority authority, CompletableFuture<TeleportOutcome> result) {
        Player subject = plan.subject();
        if (!subject.isOnline()) {
            result.complete(TeleportOutcome.failed(subject.getName() + " went offline."));
            return;
        }
        Location to = plan.destination().get();
        if (to == null || to.getWorld() == null) {
            result.complete(TeleportOutcome.failed("The destination is no longer available."));
            return;
        }
        Optional<TeleportDenial> denial = checkGuards(plan, authority, to);
        if (denial.isPresent()) {
            result.complete(TeleportOutcome.denied(denial.get()));
            return;
        }

        int seconds = warmupPolicy.warmupSeconds(plan.kind(),
            authority.has(TeleportNodes.WARMUP_SHORT), authority.has(TeleportNodes.BYPASS_WARMUP));
        if (seconds <= 0) {
            commit(plan, authority, result, false);
            return;
        }
        Warmup warmup = new Warmup(plan, authority, result);
        warmups.start(subject.getUniqueId(), warmup, clock.getAsLong(), seconds)
            .ifPresent(previous -> finishCancelled(previous.payload(), WarmupCancelReason.REPLACED));
        subject.sendMessage(ChatColor.YELLOW + "You need to wait " + seconds + " seconds before teleporting... Don't move.");
    }

    private void commit(TeleportPlan plan, Authority authority, CompletableFuture<TeleportOutcome> result, boolean recheck) {
        Player subject = plan.subject();
        if (!subject.isOnline()) {
            result.complete(TeleportOutcome.failed(subject.getName() + " went offline."));
            return;
        }
        Location to = plan.destination().get();
        if (to == null || to.getWorld() == null) {
            result.complete(TeleportOutcome.failed("The destination is no longer available."));
            return;
        }
        if (recheck) {
            Optional<TeleportDenial> denial = checkGuards(plan, authority, to);
            if (denial.isPresent()) {
                result.complete(TeleportOutcome.denied(denial.get()));
                return;
            }
        }
        if (plan.kind().isStaff()) {
            teleport(plan, authority, to, result);
            return;
        }
        TeleportCharge charge = plan.charge();
        if (charge == null) {
            place(plan, authority, to, result);
            return;
        }
        chargeThenPlace(plan, authority, charge, result);
    }

    /**
     * Ask the server to allow and charge the teleport (DESIGN §3.4 step 3) - only now, after the
     * warmup, so a cancelled warmup costs nothing. Once it is paid, every ending but TELEPORTED
     * refunds it.
     */
    private void chargeThenPlace(TeleportPlan plan, Authority authority, TeleportCharge charge,
                                 CompletableFuture<TeleportOutcome> result) {
        CompletableFuture<TeleportCharge.Authorization> authorization;
        try {
            authorization = charge.authorize();
        } catch (RuntimeException ex) {
            authorization = CompletableFuture.failedFuture(ex);
        }
        authorization.whenComplete((answer, ex) -> mainThread.execute(() -> guarded(plan, result, () -> {
            if (ex != null || answer == null) {
                LOGGER.log(Level.WARNING, "[KnK Teleport] Charge for " + plan.subject().getName() + " failed", ex);
                charge.refund("the charge failed");
                result.complete(TeleportOutcome.failed("Teleporting isn't available right now. Try again."));
                return;
            }
            if (!answer.isAllowed()) {
                result.complete(TeleportOutcome.denied(answer.denial()));
                return;
            }
            // Paid from here on: anything but arriving gives it back.
            result.whenComplete((outcome, failure) -> {
                if (failure != null || outcome == null || !outcome.isTeleported()) {
                    charge.refund(outcome != null && outcome.message() != null ? outcome.message() : "the teleport didn't happen");
                }
            });
            Player subject = plan.subject();
            if (!subject.isOnline()) {
                result.complete(TeleportOutcome.failed(subject.getName() + " went offline."));
                return;
            }
            Location to = answer.destination() != null ? answer.destination() : plan.destination().get();
            if (to == null || to.getWorld() == null) {
                result.complete(TeleportOutcome.failed("The destination is no longer available."));
                return;
            }
            // The charge took a moment: things may have changed (frozen, joined a siege...).
            Optional<TeleportDenial> denial = checkGuards(plan, authority, to);
            if (denial.isPresent()) {
                result.complete(TeleportOutcome.denied(denial.get()));
                return;
            }
            place(plan, authority, to, result);
        })));
    }

    /** Load the chunk, find a safe spot near {@code to} and teleport there (player teleports). */
    private void place(TeleportPlan plan, Authority authority, Location to, CompletableFuture<TeleportOutcome> result) {
        Player subject = plan.subject();
        World world = to.getWorld();
        world.getChunkAtAsync(to).whenComplete((chunk, ex) -> mainThread.execute(() -> guarded(plan, result, () -> {
            if (ex != null) {
                LOGGER.log(Level.WARNING, "Could not load the destination chunk for " + subject.getName(), ex);
                result.complete(TeleportOutcome.failed("The destination couldn't be loaded. Try again."));
                return;
            }
            Optional<SafeLocationFinder.Spot> spot = SafeLocationFinder.find(probes.apply(world),
                to.getBlockX(), to.getBlockY(), to.getBlockZ(), settings.safeSearchRadius());
            if (spot.isEmpty()) {
                LOGGER.warning("[KnK Teleport] No safe spot within " + settings.safeSearchRadius() + " blocks of "
                    + describe(to) + " (" + plan.destinationLabel() + ") for " + subject.getName());
                result.complete(TeleportOutcome.denied(TeleportDenial.of(TeleportDenial.UNSAFE,
                    "The destination isn't safe right now.")));
                return;
            }
            teleport(plan, authority, toLocation(to, spot.get()), result);
        })));
    }

    private void teleport(TeleportPlan plan, Authority authority, Location to, CompletableFuture<TeleportOutcome> result) {
        Player subject = plan.subject();
        UUID id = subject.getUniqueId();
        Location from = subject.getLocation();
        inFlight.put(id, authority);
        CompletableFuture<Boolean> teleported;
        try {
            teleported = subject.teleportAsync(to, TeleportCause.COMMAND);
        } catch (RuntimeException ex) {
            inFlight.remove(id, authority);
            throw ex;
        }
        teleported.whenComplete((ok, ex) -> mainThread.execute(() -> guarded(plan, result, () -> {
            inFlight.remove(id, authority);
            if (ex != null || !Boolean.TRUE.equals(ok)) {
                if (ex != null) {
                    LOGGER.log(Level.WARNING, "Teleport of " + subject.getName() + " failed", ex);
                }
                result.complete(TeleportOutcome.failed("The teleport was blocked."));
                return;
            }
            if (!plan.kind().isStaff()) {
                cooldowns.start(id, plan.kind(), clock.getAsLong(), settings.cooldownSeconds());
            }
            if (plan.charge() != null) {
                try {
                    plan.charge().completed(subject);
                } catch (RuntimeException chargeMessage) {
                    LOGGER.log(Level.WARNING, "[KnK Teleport] Could not report the charge to " + subject.getName(), chargeMessage);
                }
            }
            log(plan, from, to);
            if (plan.kind().isStaff()) {
                audit(plan, from, to);
            }
            result.complete(TeleportOutcome.teleported());
        })));
    }

    /** Hands a finished staff teleport to the auditor; a failure there never touches the teleport's outcome. */
    private void audit(TeleportPlan plan, Location from, Location to) {
        TeleportAuditor current = auditor;
        if (current == null) {
            return;
        }
        try {
            current.record(plan, from, to);
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, "[KnK Teleport] Could not audit the teleport of " + plan.subject().getName(), ex);
        }
    }

    /** Runs a step so that whatever it throws still completes the teleport's result (never left hanging). */
    private static void guarded(TeleportPlan plan, CompletableFuture<TeleportOutcome> result, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException failure) {
            LOGGER.log(Level.SEVERE, "Teleport of " + plan.subject().getName() + " failed", failure);
            result.complete(TeleportOutcome.failed("Teleport failed - see the server log."));
        }
    }

    private Optional<TeleportDenial> checkGuards(TeleportPlan plan, Authority authority, Location to) {
        Player subject = plan.subject();
        TeleportCheck check = new TeleportCheck(subject, subject.getLocation(), to, plan.kind(), plan.actor(),
            plan.visited(), authority::has);
        for (TeleportRestriction restriction : restrictions) {
            Optional<TeleportDenial> denial;
            try {
                denial = restriction.deny(check);
            } catch (RuntimeException ex) {
                // Fail closed: a broken guard (e.g. the siege one) must not let a teleport through.
                LOGGER.log(Level.SEVERE, "Teleport restriction " + restriction.getClass().getSimpleName() + " failed", ex);
                denial = Optional.of(TeleportDenial.of("error", "Teleporting isn't available right now."));
            }
            if (denial != null && denial.isPresent()) {
                return denial;
            }
        }
        if (plan.kind().isStaff()) {
            return Optional.empty();
        }
        UUID id = subject.getUniqueId();
        long now = clock.getAsLong();
        if (!authority.has(TeleportNodes.BYPASS_COMBAT) && combatTags.isTagged(id, now)) {
            return Optional.of(TeleportDenial.of(TeleportDenial.COMBAT, "You were in combat "
                + combatTags.secondsSinceCombat(id, now) + " s ago; wait " + combatTags.remainingSeconds(id, now) + " s."));
        }
        if (!authority.has(TeleportNodes.BYPASS_COOLDOWN)) {
            int remaining = cooldowns.remainingSeconds(id, plan.kind(), now);
            if (remaining > 0) {
                return Optional.of(TeleportDenial.of(TeleportDenial.COOLDOWN, "You can teleport again in " + remaining + " s."));
            }
        }
        return Optional.empty();
    }

    private void finishCancelled(Warmup warmup, WarmupCancelReason reason) {
        Player subject = warmup.plan.subject();
        if (reason != WarmupCancelReason.QUIT && subject.isOnline()) {
            subject.sendMessage(ChatColor.RED + reason.message());
        }
        warmup.result.complete(TeleportOutcome.cancelled(reason));
    }

    /**
     * Resolve every node the guards may read, against the teleport's authority: the staff member (or
     * console) for staff teleports, the subject for player teleports.
     */
    private CompletableFuture<Authority> resolveAuthority(TeleportPlan plan) {
        CommandSender authoritySender = plan.kind().isStaff() ? plan.actor() : plan.subject();
        if (!(authoritySender instanceof Player player)) {
            return CompletableFuture.completedFuture(Authority.ALL);
        }
        Set<String> nodes = new LinkedHashSet<>();
        if (!plan.kind().isStaff()) {
            nodes.addAll(PLAYER_KIND_NODES);
        }
        for (TeleportRestriction restriction : restrictions) {
            nodes.addAll(restriction.bypassNodes());
        }
        Map<String, CompletableFuture<Boolean>> checks = new HashMap<>();
        for (String node : nodes) {
            checks.put(node, permissions.has(player, node).exceptionally(ex -> false));
        }
        return CompletableFuture.allOf(checks.values().toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            Set<String> granted = new LinkedHashSet<>();
            checks.forEach((node, check) -> {
                if (Boolean.TRUE.equals(check.join())) {
                    granted.add(node);
                }
            });
            return new Authority(false, Set.copyOf(granted));
        });
    }

    private static Location toLocation(Location requested, SafeLocationFinder.Spot spot) {
        if (spot.x() == requested.getBlockX() && spot.y() == requested.getBlockY() && spot.z() == requested.getBlockZ()) {
            return requested;
        }
        return new Location(requested.getWorld(), spot.x() + 0.5, spot.y(), spot.z() + 0.5,
            requested.getYaw(), requested.getPitch());
    }

    private static void log(TeleportPlan plan, Location from, Location to) {
        String line = "[KnK Teleport] " + plan.actor().getName() + " -> " + plan.subject().getName()
            + " (" + plan.kind() + (plan.silent() ? ", silent" : "") + ") from " + describe(from)
            + " to " + describe(to) + " [" + plan.destinationLabel() + "]";
        if (plan.kind().isStaff()) {
            LOGGER.info(line);
        } else {
            LOGGER.fine(line);
        }
    }

    static String describe(Location location) {
        if (location == null) {
            return "?";
        }
        String world = location.getWorld() != null ? location.getWorld().getName() : "?";
        return world + " " + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }

    /** The resolved bypass permissions a teleport runs with. */
    private record Authority(boolean all, Set<String> granted) {
        static final Authority ALL = new Authority(true, Set.of());
        static final Authority NONE = new Authority(false, Set.of());

        boolean has(String node) {
            return all || granted.contains(node);
        }
    }

    private static final class Warmup {
        final TeleportPlan plan;
        final Authority authority;
        final CompletableFuture<TeleportOutcome> result;
        int lastShownSeconds = -1;

        Warmup(TeleportPlan plan, Authority authority, CompletableFuture<TeleportOutcome> result) {
            this.plan = plan;
            this.authority = authority;
            this.result = result;
        }
    }
}
